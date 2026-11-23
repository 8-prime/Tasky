package dev.tasky.android.domain

import dev.tasky.android.data.db.CategoryRow
import dev.tasky.android.data.db.ContributionRow
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.StoreRow

/** One line of a list: an item and all of its active contributions on that list. */
data class ListLine(
    val itemId: String,
    val name: String,
    /** Null when the item has no category ("Other"). */
    val category: CategoryRow?,
    /** True when [category] was guessed from the name rather than picked by hand. */
    val automaticCategory: Boolean,
    /** Summed amounts of the open (unpurchased) contributions. */
    val open: List<Quantity>,
    /** True when every contribution is purchased. */
    val checked: Boolean,
    /** Users who checked off contributions of this line. */
    val checkedBy: List<String>,
    val contributions: List<ContributionRow>,
    /** Notes of open contributions, for display. */
    val notes: List<String>,
) {
    val openIds: List<String> get() = contributions.filter { !it.purchased }.map { it.id }
    val purchasedIds: List<String> get() = contributions.filter { it.purchased }.map { it.id }
}

/** Open lines of one category; [category] is null for "Other". */
data class CategoryGroup(val category: CategoryRow?, val lines: List<ListLine>)

object ShoppingList {
    /**
     * Builds the lines of one list from its contributions (docs/API.md "Shopping list semantics").
     * Archived contributions are ignored. Contributions whose item is unknown are skipped.
     * Lines are sorted along [store]'s layout (the default category order without one).
     */
    fun lines(
        contributions: List<ContributionRow>,
        items: Map<String, ItemRow>,
        categories: CategoryIndex = CategoryIndex(emptyList()),
        store: StoreRow? = null,
    ): List<ListLine> {
        val rank = categories.ordered(store).withIndex().associate { (i, c) -> c.id to i }
        return contributions
            .filter { !it.archived }
            .groupBy { it.itemId }
            .mapNotNull { (itemId, cs) ->
                val item = items[itemId] ?: return@mapNotNull null
                val open = cs.filter { !it.purchased }
                val category = categories.of(item)
                ListLine(
                    itemId = itemId,
                    name = item.name,
                    category = category.category,
                    automaticCategory = category.automatic,
                    open = Units.sum(open.map { it.amount to it.unit }),
                    checked = open.isEmpty(),
                    checkedBy = cs.filter { it.purchased }.mapNotNull { it.purchasedBy }.distinct(),
                    contributions = cs.sortedBy { it.id },
                    notes = open.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }.distinct(),
                )
            }
            .sortedWith(lineOrder(rank))
    }

    /** Unchecked lines first; then by category rank ("Other" last), then name. */
    private fun lineOrder(rank: Map<String, Int>): Comparator<ListLine> = compareBy<ListLine> { it.checked }
        .thenBy { line -> line.category?.let { rank[it.id] } ?: Int.MAX_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.itemId }

    /** Open lines grouped by category, keeping the order of [lines]. */
    fun groups(lines: List<ListLine>): List<CategoryGroup> =
        lines.filter { !it.checked }
            .groupBy { it.category?.id }
            .map { (_, ls) -> CategoryGroup(ls.first().category, ls) }
}
