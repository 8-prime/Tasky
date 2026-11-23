package dev.tasky.android.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.ListEntryRow
import dev.tasky.android.data.db.ListRow
import dev.tasky.android.data.db.StoreRow
import dev.tasky.android.domain.CategoryIndex
import dev.tasky.android.domain.CategoryGroup
import dev.tasky.android.domain.ListLine
import dev.tasky.android.domain.ShoppingList
import dev.tasky.api.ListKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShoppingListState(
    val loading: Boolean = true,
    val lists: List<ListRow> = emptyList(),
    val list: ListRow? = null,
    val groups: List<CategoryGroup> = emptyList(),
    val checked: List<ListLine> = emptyList(),
    val items: List<ItemRow> = emptyList(),
    val categories: CategoryIndex = CategoryIndex(emptyList()),
    val stores: List<StoreRow> = emptyList(),
    /** The store this phone shops in; null for the default order. */
    val store: StoreRow? = null,
    val memberNames: Map<String, String> = emptyMap(),
    /** Checklist entries of the current list: unchecked (oldest first), then checked. */
    val entries: List<ListEntryRow> = emptyList(),
) {
    val hasChecked: Boolean
        get() = when {
            list?.isChecklist == true -> entries.any { it.checked }
            list?.isNote == true -> false
            else -> checked.isNotEmpty()
        }
}

const val DEFAULT_LIST_NAME = "Groceries"

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingListViewModel(private val repo: TaskyRepository) : ViewModel() {
    private val q = repo.queries
    private val selectedId = MutableStateFlow<String?>(null)

    /** "Groceries" first, then the others by name. */
    private val lists = q.lists().map { lists ->
        lists.sortedWith(compareBy({ !it.name.equals(DEFAULT_LIST_NAME, ignoreCase = true) }, { it.name.lowercase() }))
    }

    private val currentList = combine(lists, selectedId) { lists, id ->
        lists.firstOrNull { it.id == id } ?: lists.firstOrNull()
    }

    private val contributions = currentList.flatMapLatest { list ->
        if (list == null || !list.isGrocery) flowOf(emptyList()) else q.activeContributions(list.id)
    }

    private val entries = currentList.flatMapLatest { list ->
        if (list == null || !list.isChecklist) flowOf(emptyList()) else q.listEntries(list.id)
    }

    private val listsAndCurrent = combine(lists, currentList) { lists, list -> lists to list }

    private data class Layout(val categories: CategoryIndex, val stores: List<StoreRow>, val store: StoreRow?)

    private val layout = combine(q.categories(), q.stores(), repo.selectedStore()) { categories, stores, selected ->
        Layout(CategoryIndex(categories), stores, stores.firstOrNull { it.id == selected })
    }

    private val itemsAndMembers = combine(q.items(), q.members()) { items, members -> items to members }

    val state: StateFlow<ShoppingListState> = combine(
        listsAndCurrent, contributions, entries, itemsAndMembers, layout,
    ) { (lists, list), contributions, entries, (items, members), layout ->
        val lines = ShoppingList.lines(contributions, items.associateBy { it.id }, layout.categories, layout.store)
        ShoppingListState(
            loading = false,
            lists = lists,
            list = list,
            groups = ShoppingList.groups(lines),
            checked = lines.filter { it.checked },
            items = items,
            categories = layout.categories,
            stores = layout.stores,
            store = layout.store,
            memberNames = members.associate { it.userId to it.displayName },
            entries = entries.filter { !it.checked } + entries.filter { it.checked },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingListState())

    fun select(listId: String) {
        selectedId.value = listId
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun toggle(line: ListLine) = launch {
        val listId = state.value.list?.id ?: return@launch
        if (line.checked) repo.uncheckLine(listId, line.itemId) else repo.checkLine(listId, line.itemId)
    }

    /** [categoryId] null means automatic. */
    fun add(itemName: String, amount: Double?, unit: String?, note: String?, categoryId: String?) = launch {
        val listId = state.value.list?.id ?: return@launch
        repo.addToList(listId, itemName, amount, unit, note, categoryId)
    }

    fun clearChecked() = launch {
        val list = state.value.list ?: return@launch
        if (list.isChecklist) repo.clearCheckedEntries(list.id) else repo.clearChecked(list.id)
    }
    fun removeLine(line: ListLine) = launch { state.value.list?.id?.let { repo.removeLine(it, line.itemId) } }
    fun updateItem(id: String, name: String, categoryId: String?, defaultUnit: String?) =
        launch { repo.updateItem(id, name, categoryId, defaultUnit) }

    // Categories and stores
    fun setCategory(itemId: String, categoryId: String?) = launch { repo.setItemCategory(itemId, categoryId) }

    /** Creates a category, then hands its id to [then] (on the main thread). */
    fun createCategory(name: String, then: (String) -> Unit) = launch { then(repo.createCategory(name)) }

    fun selectStore(storeId: String?) = launch { repo.selectStore(storeId) }

    fun createList(name: String, kind: ListKind) = launch { selectedId.value = repo.createList(name, kind) }

    // Checklists
    fun addEntry(text: String) = launch { state.value.list?.id?.let { repo.addEntry(it, text) } }
    fun toggleEntry(entry: ListEntryRow) = launch { repo.setEntryChecked(entry.id, !entry.checked) }
    fun editEntry(entry: ListEntryRow, text: String) = launch { if (text.isNotBlank()) repo.setEntryText(entry.id, text) }
    fun deleteEntry(entry: ListEntryRow) = launch { repo.deleteEntry(entry.id) }

    // Notes
    fun saveNote(listId: String, text: String) = launch { repo.setNoteBody(listId, text) }
    fun renameList(name: String) = launch { state.value.list?.id?.let { repo.renameList(it, name) } }
    fun deleteList() = launch {
        val list = state.value.list ?: return@launch
        repo.deleteList(list.id)
        selectedId.value = null
    }
}
