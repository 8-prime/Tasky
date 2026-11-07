package dev.tasky.android.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.ListRow
import dev.tasky.android.domain.CategoryGroup
import dev.tasky.android.domain.ListLine
import dev.tasky.android.domain.ShoppingList
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
    val memberNames: Map<String, String> = emptyMap(),
)

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
        if (list == null) flowOf(emptyList()) else q.activeContributions(list.id)
    }

    val state: StateFlow<ShoppingListState> = combine(
        lists, currentList, contributions, q.items(), q.members(),
    ) { lists, list, contributions, items, members ->
        val lines = ShoppingList.lines(contributions, items.associateBy { it.id })
        ShoppingListState(
            loading = false,
            lists = lists,
            list = list,
            groups = ShoppingList.groups(lines),
            checked = lines.filter { it.checked },
            items = items,
            memberNames = members.associate { it.userId to it.displayName },
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

    fun add(itemName: String, amount: Double?, unit: String?, note: String?, category: String?) = launch {
        val listId = state.value.list?.id ?: return@launch
        repo.addToList(listId, itemName, amount, unit, note, category)
    }

    fun clearChecked() = launch { state.value.list?.id?.let { repo.clearChecked(it) } }
    fun removeLine(line: ListLine) = launch { state.value.list?.id?.let { repo.removeLine(it, line.itemId) } }
    fun updateItem(id: String, name: String, category: String?, defaultUnit: String?) =
        launch { repo.updateItem(id, name, category, defaultUnit) }

    fun createList(name: String) = launch { selectedId.value = repo.createList(name) }
    fun renameList(name: String) = launch { state.value.list?.id?.let { repo.renameList(it, name) } }
    fun deleteList() = launch {
        val list = state.value.list ?: return@launch
        repo.deleteList(list.id)
        selectedId.value = null
    }
}
