package dev.tasky.android.domain

import dev.tasky.android.data.db.ContributionRow
import dev.tasky.android.data.db.ItemRow

/** One line of a list: an item and all of its active contributions on that list. */
data class ListLine(
    val itemId: String,
    val name: String,
    val category: String?,
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

data class CategoryGroup(val category: String?, val lines: List<ListLine>)

object ShoppingList {
    /**
     * Builds the lines of one list from its contributions (docs/API.md "Shopping list semantics").
     * Archived contributions are ignored. Contributions whose item is unknown are skipped.
     */
    fun lines(contributions: List<ContributionRow>, items: Map<String, ItemRow>): List<ListLine> =
        contributions
            .filter { !it.archived }
            .groupBy { it.itemId }
            .mapNotNull { (itemId, cs) ->
                val item = items[itemId] ?: return@mapNotNull null
                val open = cs.filter { !it.purchased }
                ListLine(
                    itemId = itemId,
                    name = item.name,
                    category = item.category?.trim()?.takeIf { it.isNotEmpty() },
                    open = Units.sum(open.map { it.amount to it.unit }),
                    checked = open.isEmpty(),
                    checkedBy = cs.filter { it.purchased }.mapNotNull { it.purchasedBy }.distinct(),
                    contributions = cs.sortedBy { it.id },
                    notes = open.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }.distinct(),
                )
            }
            .sortedWith(lineOrder)

    /** Unchecked lines first; then by category (uncategorized last), then name. */
    val lineOrder: Comparator<ListLine> = compareBy<ListLine> { it.checked }
        .thenBy { it.category == null }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.category ?: "" }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.itemId }

    /** Open lines grouped by category, in order. */
    fun groups(lines: List<ListLine>): List<CategoryGroup> =
        lines.filter { !it.checked }
            .groupBy { it.category?.lowercase() }
            .map { (_, ls) -> CategoryGroup(ls.first().category, ls) }
}
