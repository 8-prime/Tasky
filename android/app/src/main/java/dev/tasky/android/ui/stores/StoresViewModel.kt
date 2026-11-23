package dev.tasky.android.ui.stores

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.CategoryRow
import dev.tasky.android.data.db.StoreRow
import dev.tasky.android.domain.CategoryIndex
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StoresState(
    val loading: Boolean = true,
    val stores: List<StoreRow> = emptyList(),
    /** In the default order. */
    val categories: List<CategoryRow> = emptyList(),
)

/** The household's stores and categories. */
class StoresViewModel(private val repo: TaskyRepository) : ViewModel() {
    val state: StateFlow<StoresState> = combine(repo.queries.stores(), repo.queries.categories()) { stores, categories ->
        StoresState(loading = false, stores = stores, categories = CategoryIndex(categories).all)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoresState())

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Creates a store, then hands its id to [then] (on the main thread). */
    fun createStore(name: String, then: (String) -> Unit) = launch { then(repo.createStore(name)) }
    fun createCategory(name: String) = launch { repo.createCategory(name) }
    fun renameCategory(id: String, name: String) = launch { repo.renameCategory(id, name) }
    fun deleteCategory(id: String) = launch { repo.deleteCategory(id) }
}

data class StoreEditState(
    val loading: Boolean = true,
    val store: StoreRow? = null,
    /** Every category, in this store's order. */
    val categories: List<CategoryRow> = emptyList(),
)

/** One store's layout: the order of its categories. */
class StoreEditViewModel(private val repo: TaskyRepository, private val storeId: String) : ViewModel() {
    val state: StateFlow<StoreEditState> = combine(repo.queries.store(storeId), repo.queries.categories()) { store, categories ->
        StoreEditState(loading = false, store = store, categories = CategoryIndex(categories).ordered(store))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoreEditState())

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private val moves = Mutex()

    /** Moves a category up (-1) or down (+1). Taps are applied one after another. */
    fun move(categoryId: String, by: Int) = launch { moves.withLock { repo.moveStoreCategory(storeId, categoryId, by) } }

    fun resetOrder() = launch { repo.setStoreOrder(storeId, emptyList()) }
    fun rename(name: String) = launch { repo.renameStore(storeId, name) }
    fun delete() = launch { repo.deleteStore(storeId) }
}
