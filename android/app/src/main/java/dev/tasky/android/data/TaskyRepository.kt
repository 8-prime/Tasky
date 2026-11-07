package dev.tasky.android.data

import dev.tasky.android.data.db.MealIngredientRow
import dev.tasky.android.data.db.MealRow
import dev.tasky.android.data.db.PlanEntryRow
import dev.tasky.android.data.db.mealSlotOf
import dev.tasky.android.domain.ProposedContribution
import dev.tasky.android.sync.Hlc
import dev.tasky.android.sync.LocalStore
import dev.tasky.android.sync.Uuids
import dev.tasky.android.sync.normalizeName
import dev.tasky.api.ContributionData
import dev.tasky.api.ContributionField
import dev.tasky.api.EntityData
import dev.tasky.api.EntityKind
import dev.tasky.api.ItemData
import dev.tasky.api.ItemField
import dev.tasky.api.ListData
import dev.tasky.api.ListEntryData
import dev.tasky.api.ListEntryField
import dev.tasky.api.ListField
import dev.tasky.api.ListKind
import dev.tasky.api.MealData
import dev.tasky.api.MealField
import dev.tasky.api.MealIngredientData
import dev.tasky.api.MealIngredientField
import dev.tasky.api.MealSlot
import dev.tasky.api.Op
import dev.tasky.api.OpAction
import dev.tasky.api.PlanEntryData
import dev.tasky.api.PlanEntryField

/**
 * Every user edit goes through here: it becomes ops that update Room optimistically and land
 * in the outbox (via [LocalStore.commit]), then a sync is requested.
 */
class TaskyRepository(
    private val store: LocalStore,
    private val hlc: Hlc,
    private val onEdit: () -> Unit,
) {
    val queries = store.db.queries()

    private fun op(id: String, action: OpAction) = Op(entity_id = id, hlc = hlc.next(), action = action)

    private suspend fun commit(ops: List<Op>) {
        if (ops.isEmpty()) return
        store.commit(ops)
        onEdit()
    }

    // ---------- items ----------

    /**
     * Finds the catalog item with this name (ignoring case and spaces), or prepares ops creating
     * it. Fills in an empty category/default unit of an existing item.
     */
    private suspend fun resolveItem(name: String, category: String?, defaultUnit: String?): Pair<String, List<Op>> {
        val existing = queries.itemByNormName(normalizeName(name))
        if (existing != null) {
            val ops = buildList {
                if (existing.category.isNullOrBlank() && !category.isNullOrBlank()) {
                    add(op(existing.id, OpAction.SetItem(ItemField.Category(category))))
                }
                if (existing.defaultUnit.isNullOrBlank() && !defaultUnit.isNullOrBlank()) {
                    add(op(existing.id, OpAction.SetItem(ItemField.DefaultUnit(defaultUnit))))
                }
            }
            return existing.id to ops
        }
        val id = Uuids.v7()
        val data = ItemData(name = name.trim(), category = category.clean(), default_unit = defaultUnit.clean())
        return id to listOf(op(id, OpAction.Create(EntityData.Item(data))))
    }

    suspend fun updateItem(id: String, name: String, category: String?, defaultUnit: String?) {
        val cur = store.db.entities().item(id) ?: return
        commit(
            buildList {
                if (name.trim() != cur.name && name.isNotBlank()) add(op(id, OpAction.SetItem(ItemField.Name(name.trim()))))
                if (category.clean() != cur.category) add(op(id, OpAction.SetItem(ItemField.Category(category.clean()))))
                if (defaultUnit.clean() != cur.defaultUnit) add(op(id, OpAction.SetItem(ItemField.DefaultUnit(defaultUnit.clean()))))
            },
        )
    }

    // ---------- lists ----------

    suspend fun createList(name: String, kind: ListKind = ListKind.Grocery): String {
        val id = Uuids.v7()
        commit(listOf(op(id, OpAction.Create(EntityData.List(ListData(name = name.trim(), kind = kind, body = null))))))
        return id
    }

    suspend fun renameList(id: String, name: String) =
        commit(listOf(op(id, OpAction.SetList(ListField.Name(name.trim())))))

    /** Deletes a list together with its contributions and checklist entries. */
    suspend fun deleteList(id: String) {
        val ops = queries.allContributionsOfList(id).map { op(it.id, OpAction.Delete(EntityKind.Contribution)) } +
            queries.listEntriesNow(id).map { op(it.id, OpAction.Delete(EntityKind.ListEntry)) } +
            op(id, OpAction.Delete(EntityKind.List))
        commit(ops)
    }

    // ---------- notes ----------

    /** Saves a note's text (no-op if unchanged). Blank text is stored as null. */
    suspend fun setNoteBody(listId: String, body: String) {
        val cur = store.db.entities().list(listId) ?: return
        val value = body.ifBlank { null }
        if (value != cur.body) commit(listOf(op(listId, OpAction.SetList(ListField.Body(value)))))
    }

    // ---------- checklists ----------

    suspend fun addEntry(listId: String, text: String) {
        val data = ListEntryData(list_id = listId, text = text.trim(), checked = false)
        commit(listOf(op(Uuids.v7(), OpAction.Create(EntityData.ListEntry(data)))))
    }

    suspend fun setEntryChecked(id: String, checked: Boolean) =
        commit(listOf(op(id, OpAction.SetListEntry(ListEntryField.Checked(checked)))))

    suspend fun setEntryText(id: String, text: String) =
        commit(listOf(op(id, OpAction.SetListEntry(ListEntryField.Text(text.trim())))))

    suspend fun deleteEntry(id: String) = commit(listOf(op(id, OpAction.Delete(EntityKind.ListEntry))))

    /** "Clear checked" for a checklist: deletes its checked entries. */
    suspend fun clearCheckedEntries(listId: String) = commit(
        queries.listEntriesNow(listId).filter { it.checked }.map { op(it.id, OpAction.Delete(EntityKind.ListEntry)) },
    )

    /** Adds an item by name to a list (creating the item if needed). Returns the item id. */
    suspend fun addToList(
        listId: String,
        itemName: String,
        amount: Double?,
        unit: String?,
        note: String? = null,
        category: String? = null,
    ): String {
        val (itemId, itemOps) = resolveItem(itemName, category, unit)
        val contribution = ContributionData(
            list_id = listId, item_id = itemId, amount = amount, unit = unit.clean(),
            note = note.clean(), purchased = false, archived = false,
        )
        commit(itemOps + op(Uuids.v7(), OpAction.Create(EntityData.Contribution(contribution))))
        return itemId
    }

    /** Checks off every currently open contribution of the line. */
    suspend fun checkLine(listId: String, itemId: String) = commit(
        queries.lineContributions(listId, itemId).filter { !it.purchased }
            .map { op(it.id, OpAction.SetContribution(ContributionField.Purchased(true))) },
    )

    /** Unchecks the line's purchased (non-archived) contributions. */
    suspend fun uncheckLine(listId: String, itemId: String) = commit(
        queries.lineContributions(listId, itemId).filter { it.purchased }
            .map { op(it.id, OpAction.SetContribution(ContributionField.Purchased(false))) },
    )

    /** Removes a line: deletes all of its active contributions. */
    suspend fun removeLine(listId: String, itemId: String) = commit(
        queries.lineContributions(listId, itemId).map { op(it.id, OpAction.Delete(EntityKind.Contribution)) },
    )

    /** Archives all purchased contributions of the list. */
    suspend fun clearChecked(listId: String) = commit(
        queries.activeContributionsNow(listId).filter { it.purchased }
            .map { op(it.id, OpAction.SetContribution(ContributionField.Archived(true))) },
    )

    // ---------- meals ----------

    suspend fun createMeal(name: String, defaultServings: Int, notes: String?, url: String?): String {
        val id = Uuids.v7()
        val data = MealData(name = name.trim(), default_servings = defaultServings, notes = notes.clean(), url = url.clean())
        commit(listOf(op(id, OpAction.Create(EntityData.Meal(data)))))
        return id
    }

    suspend fun updateMeal(cur: MealRow, name: String, defaultServings: Int, notes: String?, url: String?) = commit(
        buildList {
            if (name.trim() != cur.name) add(op(cur.id, OpAction.SetMeal(MealField.Name(name.trim()))))
            if (defaultServings != cur.defaultServings) add(op(cur.id, OpAction.SetMeal(MealField.DefaultServings(defaultServings))))
            if (notes.clean() != cur.notes) add(op(cur.id, OpAction.SetMeal(MealField.Notes(notes.clean()))))
            if (url.clean() != cur.url) add(op(cur.id, OpAction.SetMeal(MealField.Url(url.clean()))))
        },
    )

    /**
     * Deletes a meal and its ingredients. Plan entries that used it keep its name as free text,
     * so the plan still reads correctly.
     */
    suspend fun deleteMeal(meal: MealRow, planEntriesUsingIt: List<PlanEntryRow>) {
        val ops = buildList {
            for (e in planEntriesUsingIt) {
                if (e.freeText.isNullOrBlank()) add(op(e.id, OpAction.SetPlanEntry(PlanEntryField.FreeText(meal.name.take(200)))))
                add(op(e.id, OpAction.SetPlanEntry(PlanEntryField.MealId(null))))
            }
            queries.ingredientsNow(meal.id).forEach { add(op(it.id, OpAction.Delete(EntityKind.MealIngredient))) }
            add(op(meal.id, OpAction.Delete(EntityKind.Meal)))
        }
        commit(ops)
    }

    suspend fun addIngredient(mealId: String, itemName: String, amount: Double?, unit: String?, note: String?, category: String? = null) {
        val (itemId, itemOps) = resolveItem(itemName, category, unit)
        val data = MealIngredientData(meal_id = mealId, item_id = itemId, amount = amount, unit = unit.clean(), note = note.clean())
        commit(itemOps + op(Uuids.v7(), OpAction.Create(EntityData.MealIngredient(data))))
    }

    suspend fun updateIngredient(cur: MealIngredientRow, itemName: String, amount: Double?, unit: String?, note: String?) {
        val (itemId, itemOps) = resolveItem(itemName, null, unit)
        commit(
            itemOps + buildList {
                if (itemId != cur.itemId) add(op(cur.id, OpAction.SetMealIngredient(MealIngredientField.ItemId(itemId))))
                if (amount != cur.amount) add(op(cur.id, OpAction.SetMealIngredient(MealIngredientField.Amount(amount))))
                if (unit.clean() != cur.unit) add(op(cur.id, OpAction.SetMealIngredient(MealIngredientField.Unit(unit.clean()))))
                if (note.clean() != cur.note) add(op(cur.id, OpAction.SetMealIngredient(MealIngredientField.Note(note.clean()))))
            },
        )
    }

    suspend fun deleteIngredient(id: String) = commit(listOf(op(id, OpAction.Delete(EntityKind.MealIngredient))))

    // ---------- plan ----------

    data class PlanEntryInput(
        val date: String,
        val slot: MealSlot,
        val ownerUserId: String?,
        val mealId: String?,
        val freeText: String?,
        val servings: Int?,
        val note: String?,
    )

    suspend fun createPlanEntry(input: PlanEntryInput): String {
        val id = Uuids.v7()
        val data = PlanEntryData(
            date = input.date, slot = input.slot, owner_user_id = input.ownerUserId, meal_id = input.mealId,
            free_text = input.freeText.clean(), servings = input.servings, note = input.note.clean(),
        )
        commit(listOf(op(id, OpAction.Create(EntityData.PlanEntry(data)))))
        return id
    }

    suspend fun updatePlanEntry(cur: PlanEntryRow, input: PlanEntryInput) {
        val ops = ArrayList<Op>()
        fun set(f: PlanEntryField) = ops.add(op(cur.id, OpAction.SetPlanEntry(f)))
        if (input.date != cur.date) set(PlanEntryField.Date(input.date))
        if (input.slot != mealSlotOf(cur.slot)) set(PlanEntryField.Slot(input.slot))
        if (input.ownerUserId != cur.ownerUserId) set(PlanEntryField.OwnerUserId(input.ownerUserId))
        // The server validates after every op and an entry needs a meal or a free text, so set
        // the new one before clearing the old one.
        val text = input.freeText.clean()
        if (text != null && text != cur.freeText) set(PlanEntryField.FreeText(text))
        if (input.mealId != null && input.mealId != cur.mealId) set(PlanEntryField.MealId(input.mealId))
        if (text == null && cur.freeText != null) set(PlanEntryField.FreeText(null))
        if (input.mealId == null && cur.mealId != null) set(PlanEntryField.MealId(null))
        if (input.servings != cur.servings) set(PlanEntryField.Servings(input.servings))
        if (input.note.clean() != cur.note) set(PlanEntryField.Note(input.note.clean()))
        commit(ops)
    }

    /** Deletes a plan entry and its contributions that aren't purchased yet. */
    suspend fun deletePlanEntry(id: String) {
        val ops = queries.contributionsOfPlanEntry(id).filter { !it.purchased }
            .map { op(it.id, OpAction.Delete(EntityKind.Contribution)) } +
            op(id, OpAction.Delete(EntityKind.PlanEntry))
        commit(ops)
    }

    /** Creates the chosen contributions on a list, with their deterministic IDs. */
    suspend fun addToList(listId: String, proposals: List<ProposedContribution>) = commit(
        proposals.map { p ->
            val data = ContributionData(
                list_id = listId, item_id = p.itemId, amount = p.amount, unit = p.unit.clean(),
                plan_entry_id = p.planEntryId, note = p.note.clean(), purchased = false, archived = false,
            )
            op(p.id, OpAction.Create(EntityData.Contribution(data)))
        },
    )

    /** Contributions (by deterministic id) that already exist locally, in any state. */
    suspend fun existingContributions(ids: List<String>): Set<String> =
        ids.chunked(500).flatMap { queries.existingContributionIds(it) }.toSet()
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
