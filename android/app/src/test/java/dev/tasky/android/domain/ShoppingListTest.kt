package dev.tasky.android.domain

import dev.tasky.android.data.db.ContributionRow
import dev.tasky.android.data.db.ItemRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListTest {
    private val items = listOf(
        ItemRow("tom", "Tomatoes", "tomatoes", "Produce", "g"),
        ItemRow("milk", "Milk", "milk", "Dairy", "l"),
        ItemRow("apple", "apples", "apples", "Produce", null),
        ItemRow("salt", "Salt", "salt", null, null),
        ItemRow("beans", "Beans", "beans", "Cans", null),
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

    @Test
    fun sortsByCategoryThenNameWithCheckedLast() {
        val lines = ShoppingList.lines(
            listOf(c("salt", 1.0, "g"), c("tom", 1.0, "g"), c("apple", 1.0, null), c("beans", 1.0, "can"), c("milk", 1.0, "l", purchased = true)),
            items,
        )
        // Cans, Produce (apples before Tomatoes ignoring case), uncategorized, then checked.
        assertEquals(listOf("Beans", "apples", "Tomatoes", "Salt", "Milk"), lines.map { it.name })
        val groups = ShoppingList.groups(lines)
        assertEquals(listOf("Cans", "Produce", null), groups.map { it.category })
    }

    @Test
    fun lineReappearsWithOnlyTheNewAmount() {
        // Someone checked the line while another person added more pasta.
        val lines = ShoppingList.lines(listOf(c("tom", 500.0, "g", purchased = true), c("tom", 200.0, "g")), items)
        assertFalse(lines.single().checked)
        assertEquals("200 g", Units.format(lines.single().open))
    }
}
