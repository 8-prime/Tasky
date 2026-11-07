package dev.tasky.android.domain

import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.MealIngredientRow
import dev.tasky.android.data.db.MealRow
import dev.tasky.android.data.db.PlanEntryRow
import dev.tasky.android.sync.Uuids
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A contribution "add to list" would create, shown in the checklist before adding. */
data class ProposedContribution(
    /** Deterministic: uuidv5("<plan_entry_id>:<meal_ingredient_id>"). */
    val id: String,
    val planEntryId: String,
    val ingredientId: String,
    val itemId: String,
    val itemName: String,
    val category: String?,
    val amount: Double?,
    val unit: String?,
    val note: String?,
    val mealName: String,
    val date: String,
)

object MealPlanning {
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun weekDays(monday: LocalDate): List<LocalDate> = (0L until 7L).map { monday.plusDays(it) }

    /** `servings ?: (household meal ? member count : 1)`. */
    fun servings(entry: PlanEntryRow, memberCount: Int): Int =
        entry.servings ?: if (entry.ownerUserId == null) memberCount.coerceAtLeast(1) else 1

    /**
     * Contributions for the given plan entries: one per ingredient of each entry's meal,
     * scaled by `servings / meal.default_servings`. Entries without a catalog meal add nothing.
     */
    fun propose(
        entries: List<PlanEntryRow>,
        meals: Map<String, MealRow>,
        ingredientsByMeal: Map<String, List<MealIngredientRow>>,
        items: Map<String, ItemRow>,
        memberCount: Int,
    ): List<ProposedContribution> = entries.sortedWith(compareBy({ it.date }, { slotOrder(it.slot) })).flatMap { entry ->
        val meal = entry.mealId?.let(meals::get) ?: return@flatMap emptyList()
        val servings = servings(entry, memberCount)
        ingredientsByMeal[meal.id].orEmpty().mapNotNull { ing ->
            val item = items[ing.itemId] ?: return@mapNotNull null
            ProposedContribution(
                id = Uuids.contributionId(entry.id, ing.id),
                planEntryId = entry.id,
                ingredientId = ing.id,
                itemId = ing.itemId,
                itemName = item.name,
                category = item.category,
                amount = ing.amount?.let { it * servings / meal.defaultServings.coerceAtLeast(1) },
                unit = ing.unit?.takeIf { it.isNotBlank() } ?: item.defaultUnit,
                note = ing.note,
                mealName = meal.name,
                date = entry.date,
            )
        }
    }

    fun slotOrder(slot: String): Int = when (slot) {
        "breakfast" -> 0
        "lunch" -> 1
        else -> 2
    }
}
