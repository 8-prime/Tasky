package dev.tasky.android.ui.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.MealIngredientRow
import dev.tasky.android.data.db.MealRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MealSummary(val meal: MealRow, val ingredientCount: Int)

class MealsViewModel(repo: TaskyRepository) : ViewModel() {
    val meals: StateFlow<List<MealSummary>?> = combine(repo.queries.meals(), repo.queries.allIngredients()) { meals, ings ->
        val counts = ings.groupingBy { it.mealId }.eachCount()
        meals.map { MealSummary(it, counts[it.id] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

data class IngredientLine(val row: MealIngredientRow, val itemName: String)

data class MealEditState(
    val mealId: String? = null,
    /** Null while loading or for a new meal. */
    val meal: MealRow? = null,
    /** True once a meal that existed was deleted (by us or someone else). */
    val gone: Boolean = false,
    val ingredients: List<IngredientLine> = emptyList(),
    val items: List<ItemRow> = emptyList(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MealEditViewModel(private val repo: TaskyRepository, initialId: String?) : ViewModel() {
    private val q = repo.queries
    private val mealId = MutableStateFlow(initialId)
    private var seen = false

    val state: StateFlow<MealEditState> = mealId.flatMapLatest { id ->
        if (id == null) {
            q.items().map { items -> MealEditState(items = items) }
        } else {
            combine(q.meal(id), q.ingredients(id), q.items()) { meal, ings, items ->
                if (meal != null) seen = true
                val names = items.associate { it.id to it.name }
                MealEditState(
                    mealId = id,
                    meal = meal,
                    gone = meal == null && seen,
                    ingredients = ings.map { IngredientLine(it, names[it.itemId] ?: "?") }
                        .sortedBy { it.itemName.lowercase() },
                    items = items,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealEditState(mealId = initialId))

    fun save(name: String, servings: Int, notes: String?, url: String?) = viewModelScope.launch {
        val cur = state.value.meal
        if (cur == null) {
            mealId.value = repo.createMeal(name, servings, notes, url)
        } else {
            repo.updateMeal(cur, name, servings, notes, url)
        }
    }

    fun delete() = viewModelScope.launch {
        val meal = state.value.meal ?: return@launch
        repo.deleteMeal(meal, q.planEntriesForMeal(meal.id))
    }

    fun addIngredient(itemName: String, amount: Double?, unit: String?, note: String?) = viewModelScope.launch {
        val id = mealId.value ?: return@launch
        repo.addIngredient(id, itemName, amount, unit, note)
    }

    fun updateIngredient(row: MealIngredientRow, itemName: String, amount: Double?, unit: String?, note: String?) =
        viewModelScope.launch { repo.updateIngredient(row, itemName, amount, unit, note) }

    fun deleteIngredient(row: MealIngredientRow) = viewModelScope.launch { repo.deleteIngredient(row.id) }
}
