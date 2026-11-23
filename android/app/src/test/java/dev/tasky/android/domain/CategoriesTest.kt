package dev.tasky.android.domain

import dev.tasky.android.data.db.CategoryRow
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.StoreRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {
    private val drinks = CategoryRow("d", "Drinks", "drinks")
    private val produce = CategoryRow("p", "Fruit & vegetables", "produce")
    private val dairy = CategoryRow("m", "Dairy & eggs", "dairy")
    private val asian = CategoryRow("a", "asian", null)
    private val bio = CategoryRow("b", "Bio", null)
    private val index = CategoryIndex(listOf(asian, drinks, bio, dairy, produce))

    @Test
    fun defaultOrderIsBuiltInsThenOwnByName() {
        assertEquals(listOf(produce, dairy, drinks, asian, bio), index.all)
        assertEquals(index.all, index.ordered(null))
    }

    @Test
    fun storeLayoutComesFirstThenTheRest() {
        val store = StoreRow("s", "Aldi", "d,gone,a,d")
        assertEquals(listOf(drinks, asian, produce, dairy, bio), index.ordered(store))
        assertEquals(index.all, index.ordered(StoreRow("e", "Empty", "")))
    }

    @Test
    fun handPickedCategoryWinsUnlessItWasDeleted() {
        val auto = index.of(ItemRow("1", "Vollmilch", "vollmilch", null))
        assertEquals(dairy, auto.category)
        assertTrue(auto.automatic)

        val picked = index.of(ItemRow("2", "Vollmilch", "vollmilch", null, categoryId = drinks.id))
        assertEquals(drinks, picked.category)
        assertFalse(picked.automatic)

        assertEquals(ItemCategory(dairy, automatic = true), index.of(ItemRow("3", "Milk", "milk", null, categoryId = "gone")))
        // Automatic only maps to built-in categories the household still has.
        assertNull(index.of(ItemRow("4", "Bread", "bread", null)).category)
    }
}
