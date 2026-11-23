package dev.tasky.android.domain

import dev.tasky.android.data.db.CategoryRow
import dev.tasky.android.data.db.ContributionRow
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.StoreRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListTest {
    private val produce = CategoryRow("c-produce", "Fruit & vegetables", "produce")
    private val dairy = CategoryRow("c-dairy", "Dairy & eggs", "dairy")
    private val canned = CategoryRow("c-canned", "Canned & jarred", "canned")
    private val spices = CategoryRow("c-condiments", "Oils, spices & sauces", "condiments")
    private val pantry = CategoryRow("c-pantry", "Pantry", null)
    private val categories = CategoryIndex(listOf(spices, dairy, produce, canned, pantry))

    private val items = listOf(
        ItemRow("tom", "Tomatoes", "tomatoes", "g"),
        ItemRow("milk", "Milk", "milk", "l"),
        ItemRow("apple", "apples", "apples", null),
        // Picked by hand: wins over the automatic "condiments".
        ItemRow("salt", "Salt", "salt", null, categoryId = pantry.id),
        ItemRow("beans", "Beans", "beans", null),
        ItemRow("thing", "Thingamajig", "thingamajig", null),
        // Its category was deleted: back to automatic.
        ItemRow("pepper", "Pepper", "pepper", null, categoryId = "deleted"),
    ).associateBy { it.id }

    private var n = 0
    private fun c(item: String, amount: Double?, unit: String?, purchased: Boolean = false, archived: Boolean = false, by: String? = null) =
        ContributionRow("c${n++}", "L", item, amount, unit, null, null, purchased, if (purchased) by ?: "u1" else null, archived)

    @Test
    fun sumsAcrossUnitsWithinADimension() {
        val q = Units.sum(listOf(500.0 to "g", 0.7 to "kg", 2.0 to "pcs", 1.0 to "can", 1.0 to "Can", null to "g"))
        assertEquals("1.2 kg + 2 pcs + 1 can + 1 Can", Units.format(q))
        assertEquals("750 g", Units.format(Units.sum(listOf(500.0 to "g", 250.0 to "g"))))
        assertEquals("1.5 l", Units.format(Units.sum(listOf(1.0 to "l", 500.0 to "ml"))))
        assertEquals("35 ml", Units.format(Units.sum(listOf(2.0 to "tbsp", 1.0 to "tsp"))))
        assertEquals("3 tbsp", Units.format(Units.sum(listOf(2.0 to "tbsp", 1.0 to "tbsp"))))
        assertEquals("3 pcs", Units.format(Units.sum(listOf(2.0 to null, 1.0 to "PCS"))))
    }

    @Test
    fun formatsNumbers() {
        assertEquals("1.25", Units.number(1.25))
        assertEquals("3", Units.number(3.0))
        assertEquals("0.33", Units.number(1.0 / 3))
        assertEquals(1.5, Units.parseAmount(" 1,5 "))
        assertEquals(null, Units.parseAmount("abc"))
        assertEquals(null, Units.parseAmount("-1"))
    }

    @Test
    fun buildsLinesWithOpenAmountsAndCheckState() {
        val lines = ShoppingList.lines(
            listOf(
                c("tom", 500.0, "g"),
                c("tom", 2.0, "pcs"),
                c("tom", 250.0, "g", purchased = true),
                c("milk", 1.0, "l", purchased = true, by = "anna"),
                c("milk", 2.0, "l", archived = true),
                c("salt", null, null),
                c("ghost", 1.0, "g"), // item not known locally: skipped
            ),
            items,
            categories,
        )
        assertEquals(listOf("Tomatoes", "Salt", "Milk"), lines.map { it.name })
        val tom = lines[0]
        assertEquals("500 g + 2 pcs", Units.format(tom.open))
        assertFalse(tom.checked)
        assertEquals(2, tom.openIds.size)
        assertEquals(1, tom.purchasedIds.size)
        val salt = lines[1]
        assertTrue(salt.open.isEmpty())
        assertFalse(salt.checked)
        val milk = lines[2]
        assertTrue(milk.checked)
        assertEquals(listOf("anna"), milk.checkedBy)
        assertEquals(1, milk.contributions.size) // archived one is gone
    }

    private fun all() = listOf(
        c("salt", 1.0, "g"), c("tom", 1.0, "g"), c("apple", 1.0, null), c("beans", 1.0, "can"),
        c("thing", 1.0, null), c("pepper", 1.0, null), c("milk", 1.0, "l", purchased = true),
    )

    @Test
    fun groupsByCategoryInTheDefaultOrderWithCheckedLast() {
        val lines = ShoppingList.lines(all(), items, categories)
        // Produce (apples before Tomatoes ignoring case), canned, condiments, the household's own
        // categories, "Other", then checked.
        assertEquals(listOf("apples", "Tomatoes", "Beans", "Pepper", "Salt", "Thingamajig", "Milk"), lines.map { it.name })
        assertEquals(
            listOf(produce, canned, spices, pantry, null),
            ShoppingList.groups(lines).map { it.category },
        )
        val byName = lines.associateBy { it.name }
        assertFalse(byName.getValue("Salt").automaticCategory)
        assertTrue(byName.getValue("Pepper").automaticCategory)
        assertEquals(dairy, byName.getValue("Milk").category)
    }

    @Test
    fun followsTheStoreLayout() {
        // The layout lists pantry and canned first (and a deleted category); the rest follow in default order.
        val store = StoreRow("s", "Corner shop", listOf(pantry.id, "deleted", canned.id).joinToString(","))
        val lines = ShoppingList.lines(all(), items, categories, store)
        assertEquals(listOf("Salt", "Beans", "apples", "Tomatoes", "Pepper", "Thingamajig", "Milk"), lines.map { it.name })
        assertEquals(listOf(pantry, canned, produce, spices, null), ShoppingList.groups(lines).map { it.category })
    }

    @Test
    fun lineReappearsWithOnlyTheNewAmount() {
        // Someone checked the line while another person added more pasta.
        val lines = ShoppingList.lines(listOf(c("tom", 500.0, "g", purchased = true), c("tom", 200.0, "g")), items)
        assertFalse(lines.single().checked)
        assertEquals("200 g", Units.format(lines.single().open))
    }
}
