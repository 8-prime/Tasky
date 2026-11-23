package dev.tasky.android.domain

import dev.tasky.android.data.db.CategoryRow
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.StoreRow

/** The category an item ends up in, and whether it was guessed from the name. */
data class ItemCategory(val category: CategoryRow?, val automatic: Boolean)

/**
 * The household's categories, for looking up an item's category and sorting categories
 * (docs/API.md, "Categories").
 */
class CategoryIndex(categories: List<CategoryRow>) {
    val all: List<CategoryRow> = categories.sortedWith(defaultOrder)
    private val byId = categories.associateBy { it.id }
    private val byKey = all.filter { it.key != null }.groupBy { it.key!! }.mapValues { it.value.first() }

    operator fun get(id: String?): CategoryRow? = id?.let(byId::get)

    /** The automatic category for a name: the built-in category the [Categorizer] picks, if it still exists. */
    fun automatic(name: String): CategoryRow? = Categorizer.keyFor(name)?.let(byKey::get)

    /**
     * A category set by hand wins. If there is none, or it was deleted, the item's name decides.
     * Null when nothing matches ("Other").
     */
    fun of(item: ItemRow): ItemCategory =
        this[item.categoryId]?.let { ItemCategory(it, automatic = false) }
            ?: ItemCategory(automatic(item.name), automatic = true)

    /**
     * Categories in the order to walk through [store]: the ones its layout lists, then the rest
     * in the default order. Without a store, the default order.
     *
     * Later, a store could also know where single items are (aisle, shelf, coordinates); this
     * is the place a route through the store would replace the per-category order.
     */
    fun ordered(store: StoreRow?): List<CategoryRow> {
        if (store == null) return all
        val listed = store.order.mapNotNull(byId::get).distinctBy { it.id }
        val ids = listed.mapTo(HashSet()) { it.id }
        return listed + all.filter { it.id !in ids }
    }

    companion object {
        /** Same keys and order as `BUILTIN_CATEGORIES` in `server/crates/tasky-api`. */
        val BUILTIN_KEYS = listOf(
            "produce", "bakery", "meat_fish", "dairy", "chilled", "breakfast", "coffee_tea", "pasta_rice", "baking",
            "canned", "condiments", "international", "snacks", "drinks", "frozen", "household", "personal_care",
            "baby", "pets",
        )

        /** Built-in categories in supermarket order, then the household's own by name. */
        val defaultOrder: Comparator<CategoryRow> = compareBy<CategoryRow> { c ->
            BUILTIN_KEYS.indexOf(c.key).let { if (it < 0) Int.MAX_VALUE else it }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
    }
}
