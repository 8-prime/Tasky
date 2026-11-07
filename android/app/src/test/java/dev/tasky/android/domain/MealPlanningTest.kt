package dev.tasky.android.domain

import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.MealIngredientRow
import dev.tasky.android.data.db.MealRow
import dev.tasky.android.data.db.PlanEntryRow
import dev.tasky.android.sync.Uuids
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MealPlanningTest {
    @Test
    fun weeksStartOnMonday() {
        assertEquals(LocalDate.of(2026, 11, 2), MealPlanning.weekStart(LocalDate.of(2026, 11, 7))) // Saturday
        assertEquals(LocalDate.of(2026, 11, 2), MealPlanning.weekStart(LocalDate.of(2026, 11, 2))) // Monday
        assertEquals(LocalDate.of(2026, 11, 2), MealPlanning.weekStart(LocalDate.of(2026, 11, 8))) // Sunday
        assertEquals(7, MealPlanning.weekDays(LocalDate.of(2026, 11, 2)).size)
    }

    private fun entry(id: String, owner: String?, servings: Int?, meal: String? = "m") =
        PlanEntryRow(id, "2026-11-03", "dinner", owner, meal, null, servings, null)

    @Test
    fun defaultServings() {
        assertEquals(3, MealPlanning.servings(entry("e", null, null), memberCount = 3))
        assertEquals(1, MealPlanning.servings(entry("e", "u1", null), memberCount = 3))
        assertEquals(5, MealPlanning.servings(entry("e", "u1", 5), memberCount = 3))
        assertEquals(1, MealPlanning.servings(entry("e", null, null), memberCount = 0))
    }

    @Test
    fun proposalsScaleAndUseDeterministicIds() {
        val meals = mapOf("m" to MealRow("m", "Pasta", 2, null, null))
        val ingredients = mapOf(
            "m" to listOf(
                MealIngredientRow("i1", "m", "pasta", 250.0, "g", null),
                MealIngredientRow("i2", "m", "tom", 1.0, null, "ripe"),
                MealIngredientRow("i3", "m", "salt", null, null, null),
                MealIngredientRow("i4", "m", "unknown", 1.0, "g", null),
            ),
        )
        val items = listOf(
            ItemRow("pasta", "Pasta", "pasta", null, "g"),
            ItemRow("tom", "Tomato sauce", "tomato sauce", null, "can"),
            ItemRow("salt", "Salt", "salt", null, null),
        ).associateBy { it.id }
        val entries = listOf(entry("e1", null, null), entry("e2", "u", 1), entry("e3", null, 2, meal = null))
        val p = MealPlanning.propose(entries, meals, ingredients, items, memberCount = 3)
        assertEquals(6, p.size)
        val pasta1 = p.first { it.planEntryId == "e1" && it.itemId == "pasta" }
        assertEquals(375.0, pasta1.amount!!, 1e-9) // 250 * 3 / 2
        assertEquals(Uuids.contributionId("e1", "i1"), pasta1.id)
        val sauce1 = p.first { it.planEntryId == "e1" && it.itemId == "tom" }
        assertEquals("can", sauce1.unit) // falls back to the item's default unit
        assertEquals(1.5, sauce1.amount!!, 1e-9)
        assertEquals("ripe", sauce1.note)
        val pasta2 = p.first { it.planEntryId == "e2" && it.itemId == "pasta" }
        assertEquals(125.0, pasta2.amount!!, 1e-9)
        assertEquals(null, p.first { it.itemId == "salt" }.amount)
    }
}
