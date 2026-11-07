package dev.tasky.android.sync

import dev.tasky.api.ContributionData
import dev.tasky.api.ContributionField
import dev.tasky.api.EntityData
import dev.tasky.api.EntityKind
import dev.tasky.api.ItemData
import dev.tasky.api.ItemField
import dev.tasky.api.MealIngredientData
import dev.tasky.api.MealIngredientField
import dev.tasky.api.Op
import dev.tasky.api.OpAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OpApplierTest {
    private var t = 0
    private fun op(id: String, action: OpAction) = Op(id, Hlc.format(1000, t++, "n"), action)
    private val milk = EntityData.Item(ItemData("Milk", "Dairy", "l"))
    private fun contribution(purchased: Boolean = false) =
        EntityData.Contribution(ContributionData(list_id = "L", item_id = "I", amount = 1.0, unit = "l", purchased = purchased, archived = false))

    @Test
    fun createSetDelete() {
        val ops = listOf(
            op("i", OpAction.Create(milk)),
            op("i", OpAction.SetItem(ItemField.Category(null))),
            op("i", OpAction.SetItem(ItemField.Name("Oat milk"))),
        )
        assertEquals(EntityData.Item(ItemData("Oat milk", null, "l")), OpApplier.materialize(null, ops, "me"))
        assertNull(OpApplier.materialize(milk, listOf(op("i", OpAction.Delete(EntityKind.Item))), "me"))
    }

    @Test
    fun setOnMissingEntityIsIgnored() {
        assertNull(OpApplier.apply(null, OpAction.SetItem(ItemField.Name("x")), "me"))
    }

    @Test
    fun kindMismatchIsIgnored() {
        assertSame(milk, OpApplier.apply(milk, OpAction.SetContribution(ContributionField.Purchased(true)), "me"))
    }

    @Test
    fun purchasedByFollowsPurchased() {
        val checked = OpApplier.apply(contribution(), OpAction.SetContribution(ContributionField.Purchased(true)), "me")
        assertEquals("me", (checked as EntityData.Contribution).value.purchased_by)
        val unchecked = OpApplier.apply(checked, OpAction.SetContribution(ContributionField.Purchased(false)), "me")
        assertNull((unchecked as EntityData.Contribution).value.purchased_by)
        val created = OpApplier.apply(null, OpAction.Create(contribution(purchased = true)), "me")
        assertEquals("me", (created as EntityData.Contribution).value.purchased_by)
    }

    @Test
    fun remapItemRewritesReferences() {
        val createContribution = op("c", OpAction.Create(contribution()))
        assertEquals("T", ((OpApplier.remapItem(createContribution, "I", "T").action as OpAction.Create).value as EntityData.Contribution).value.item_id)

        val createIngredient = op("m", OpAction.Create(EntityData.MealIngredient(MealIngredientData("M", "I"))))
        assertEquals("T", ((OpApplier.remapItem(createIngredient, "I", "T").action as OpAction.Create).value as EntityData.MealIngredient).value.item_id)

        val setIngredient = op("m", OpAction.SetMealIngredient(MealIngredientField.ItemId("I")))
        assertEquals(OpAction.SetMealIngredient(MealIngredientField.ItemId("T")), OpApplier.remapItem(setIngredient, "I", "T").action)

        val setItem = op("I", OpAction.SetItem(ItemField.Category("Dairy")))
        assertEquals("T", OpApplier.remapItem(setItem, "I", "T").entity_id)

        val unrelated = op("x", OpAction.SetItem(ItemField.Category("Dairy")))
        assertSame(unrelated, OpApplier.remapItem(unrelated, "I", "T"))
    }

    @Test
    fun normalizesNamesLikeTheServer() {
        assertEquals("cherry tomatoes", normalizeName("  Cherry \t  Tomatoes "))
        assertEquals("milk", normalizeName("MILK"))
    }
}
