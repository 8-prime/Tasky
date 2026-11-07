package dev.tasky.android.data.db

import dev.tasky.android.sync.normalizeName
import dev.tasky.api.ContributionData
import dev.tasky.api.EntityData
import dev.tasky.api.EntityKind
import dev.tasky.api.ItemData
import dev.tasky.api.ListData
import dev.tasky.api.MealData
import dev.tasky.api.MealIngredientData
import dev.tasky.api.MealSlot
import dev.tasky.api.PlanEntryData

fun MealSlot.code(): String = string
fun mealSlotOf(code: String): MealSlot = MealSlot.entries.firstOrNull { it.string == code } ?: MealSlot.Dinner

fun ItemRow.toData() = ItemData(name = name, category = category, default_unit = defaultUnit)
fun ListRow.toData() = ListData(name = name)
fun ContributionRow.toData() = ContributionData(
    list_id = listId, item_id = itemId, amount = amount, unit = unit, plan_entry_id = planEntryId,
    note = note, purchased = purchased, purchased_by = purchasedBy, archived = archived,
)
fun MealRow.toData() = MealData(name = name, default_servings = defaultServings, notes = notes, url = url)
fun MealIngredientRow.toData() = MealIngredientData(meal_id = mealId, item_id = itemId, amount = amount, unit = unit, note = note)
fun PlanEntryRow.toData() = PlanEntryData(
    date = date, slot = mealSlotOf(slot), owner_user_id = ownerUserId, meal_id = mealId,
    free_text = freeText, servings = servings, note = note,
)

/** Reads the visible state of an entity. */
suspend fun EntityDao.getVisible(kind: EntityKind, id: String): EntityData? = when (kind) {
    EntityKind.Item -> item(id)?.let { EntityData.Item(it.toData()) }
    EntityKind.List -> list(id)?.let { EntityData.List(it.toData()) }
    EntityKind.Contribution -> contribution(id)?.let { EntityData.Contribution(it.toData()) }
    EntityKind.Meal -> meal(id)?.let { EntityData.Meal(it.toData()) }
    EntityKind.MealIngredient -> mealIngredient(id)?.let { EntityData.MealIngredient(it.toData()) }
    EntityKind.PlanEntry -> planEntry(id)?.let { EntityData.PlanEntry(it.toData()) }
}

/** Writes the visible state of an entity; `null` removes it. */
suspend fun EntityDao.putVisible(id: String, data: EntityData?) {
    if (data == null) return deleteVisible(id)
    when (data) {
        is EntityData.Item -> data.value.let {
            upsert(ItemRow(id, it.name, normalizeName(it.name), it.category, it.default_unit))
        }
        is EntityData.List -> upsert(ListRow(id, data.value.name))
        is EntityData.Contribution -> data.value.let {
            upsert(ContributionRow(id, it.list_id, it.item_id, it.amount, it.unit, it.plan_entry_id, it.note, it.purchased, it.purchased_by, it.archived))
        }
        is EntityData.Meal -> data.value.let { upsert(MealRow(id, it.name, it.default_servings, it.notes, it.url)) }
        is EntityData.MealIngredient -> data.value.let {
            upsert(MealIngredientRow(id, it.meal_id, it.item_id, it.amount, it.unit, it.note))
        }
        is EntityData.PlanEntry -> data.value.let {
            upsert(PlanEntryRow(id, it.date, it.slot.code(), it.owner_user_id, it.meal_id, it.free_text, it.servings, it.note))
        }
    }
}

/** IDs are unique across kinds, so removing by id from every table is safe. */
suspend fun EntityDao.deleteVisible(id: String) {
    deleteItem(id)
    deleteList(id)
    deleteContribution(id)
    deleteMeal(id)
    deleteMealIngredient(id)
    deletePlanEntry(id)
}

suspend fun EntityDao.clearVisible() {
    clearItems()
    clearLists()
    clearContributions()
    clearMeals()
    clearMealIngredients()
    clearPlanEntries()
}
