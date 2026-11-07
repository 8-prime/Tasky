package dev.tasky.android.sync

import dev.tasky.api.ContributionField
import dev.tasky.api.EntityData
import dev.tasky.api.EntityKind
import dev.tasky.api.ItemField
import dev.tasky.api.ListField
import dev.tasky.api.MealField
import dev.tasky.api.MealIngredientField
import dev.tasky.api.Op
import dev.tasky.api.OpAction
import dev.tasky.api.PlanEntryField

val EntityData.kind: EntityKind
    get() = when (this) {
        is EntityData.Item -> EntityKind.Item
        is EntityData.List -> EntityKind.List
        is EntityData.Contribution -> EntityKind.Contribution
        is EntityData.Meal -> EntityKind.Meal
        is EntityData.MealIngredient -> EntityKind.MealIngredient
        is EntityData.PlanEntry -> EntityKind.PlanEntry
    }

val OpAction.kind: EntityKind
    get() = when (this) {
        is OpAction.Create -> value.kind
        is OpAction.Delete -> value
        is OpAction.SetItem -> EntityKind.Item
        is OpAction.SetList -> EntityKind.List
        is OpAction.SetContribution -> EntityKind.Contribution
        is OpAction.SetMeal -> EntityKind.Meal
        is OpAction.SetMealIngredient -> EntityKind.MealIngredient
        is OpAction.SetPlanEntry -> EntityKind.PlanEntry
    }

/** Same rule as the server: lowercase, trimmed, single-spaced. */
fun normalizeName(name: String): String =
    name.split { it.isWhitespace() }.filter { it.isNotEmpty() }.joinToString(" ").lowercase()

private inline fun String.split(predicate: (Char) -> Boolean): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (c in this) {
        if (predicate(c)) { out += sb.toString(); sb.clear() } else sb.append(c)
    }
    out += sb.toString()
    return out
}

/**
 * Applies ops to local entity state, the way the server would if they were the newest writes.
 * Used for optimistic edits and to re-apply the pending outbox on top of server state.
 * `null` means the entity doesn't exist (or is deleted).
 */
object OpApplier {
    fun apply(current: EntityData?, action: OpAction, me: String?): EntityData? {
        if (current != null && current.kind != action.kind) return current
        return when (action) {
            is OpAction.Create -> when (val data = action.value) {
                is EntityData.Contribution -> EntityData.Contribution(
                    data.value.copy(purchased_by = if (data.value.purchased) me else null),
                )
                else -> data
            }
            is OpAction.Delete -> null
            else -> current?.let { set(it, action, me) }
        }
    }

    fun materialize(base: EntityData?, ops: List<Op>, me: String?): EntityData? =
        ops.fold(base) { acc, op -> apply(acc, op.action, me) }

    private fun set(cur: EntityData, action: OpAction, me: String?): EntityData = when {
        cur is EntityData.Item && action is OpAction.SetItem -> EntityData.Item(
            when (val f = action.value) {
                is ItemField.Name -> cur.value.copy(name = f.value)
                is ItemField.Category -> cur.value.copy(category = f.value)
                is ItemField.DefaultUnit -> cur.value.copy(default_unit = f.value)
            },
        )
        cur is EntityData.List && action is OpAction.SetList -> EntityData.List(
            when (val f = action.value) {
                is ListField.Name -> cur.value.copy(name = f.value)
            },
        )
        cur is EntityData.Contribution && action is OpAction.SetContribution -> EntityData.Contribution(
            when (val f = action.value) {
                is ContributionField.ListId -> cur.value.copy(list_id = f.value)
                is ContributionField.Amount -> cur.value.copy(amount = f.value)
                is ContributionField.Unit -> cur.value.copy(unit = f.value)
                is ContributionField.Note -> cur.value.copy(note = f.value)
                is ContributionField.Purchased ->
                    cur.value.copy(purchased = f.value, purchased_by = if (f.value) me else null)
                is ContributionField.Archived -> cur.value.copy(archived = f.value)
            },
        )
        cur is EntityData.Meal && action is OpAction.SetMeal -> EntityData.Meal(
            when (val f = action.value) {
                is MealField.Name -> cur.value.copy(name = f.value)
                is MealField.DefaultServings -> cur.value.copy(default_servings = f.value)
                is MealField.Notes -> cur.value.copy(notes = f.value)
                is MealField.Url -> cur.value.copy(url = f.value)
            },
        )
        cur is EntityData.MealIngredient && action is OpAction.SetMealIngredient -> EntityData.MealIngredient(
            when (val f = action.value) {
                is MealIngredientField.ItemId -> cur.value.copy(item_id = f.value)
                is MealIngredientField.Amount -> cur.value.copy(amount = f.value)
                is MealIngredientField.Unit -> cur.value.copy(unit = f.value)
                is MealIngredientField.Note -> cur.value.copy(note = f.value)
            },
        )
        cur is EntityData.PlanEntry && action is OpAction.SetPlanEntry -> EntityData.PlanEntry(
            when (val f = action.value) {
                is PlanEntryField.Date -> cur.value.copy(date = f.value)
                is PlanEntryField.Slot -> cur.value.copy(slot = f.value)
                is PlanEntryField.OwnerUserId -> cur.value.copy(owner_user_id = f.value)
                is PlanEntryField.MealId -> cur.value.copy(meal_id = f.value)
                is PlanEntryField.FreeText -> cur.value.copy(free_text = f.value)
                is PlanEntryField.Servings -> cur.value.copy(servings = f.value)
                is PlanEntryField.Note -> cur.value.copy(note = f.value)
            },
        )
        else -> cur
    }

    /**
     * Rewrites an op so it no longer mentions a merged item ID ([alias] → [target]).
     * The server does the same mapping, so this only keeps local replays consistent.
     */
    fun remapItem(op: Op, alias: String, target: String): Op {
        val id = if (op.entity_id == alias && op.action.kind == dev.tasky.api.EntityKind.Item) target else op.entity_id
        val action = when (val a = op.action) {
            is OpAction.Create -> when (val d = a.value) {
                is EntityData.Contribution ->
                    if (d.value.item_id == alias) OpAction.Create(EntityData.Contribution(d.value.copy(item_id = target))) else a
                is EntityData.MealIngredient ->
                    if (d.value.item_id == alias) OpAction.Create(EntityData.MealIngredient(d.value.copy(item_id = target))) else a
                else -> a
            }
            is OpAction.SetMealIngredient -> {
                val f = a.value
                if (f is MealIngredientField.ItemId && f.value == alias) OpAction.SetMealIngredient(MealIngredientField.ItemId(target)) else a
            }
            else -> a
        }
        return if (id == op.entity_id && action === op.action) op else op.copy(entity_id = id, action = action)
    }
}
