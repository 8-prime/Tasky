package dev.tasky.android.api

import dev.tasky.api.ContributionData
import dev.tasky.api.ContributionField
import dev.tasky.api.Entity
import dev.tasky.api.EntityData
import dev.tasky.api.EntityKind
import dev.tasky.api.ErrorBody
import dev.tasky.api.ErrorCode
import dev.tasky.api.ItemData
import dev.tasky.api.ItemField
import dev.tasky.api.LiveEvent
import dev.tasky.api.LiveEventKind
import dev.tasky.api.MealSlot
import dev.tasky.api.Op
import dev.tasky.api.OpAction
import dev.tasky.api.PlanEntryData
import dev.tasky.api.PlanEntryField
import dev.tasky.api.RejectCode
import dev.tasky.api.SyncRequest
import dev.tasky.api.SyncResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors server/crates/tasky-api/tests/wire_format.rs: the exact JSON shapes the server expects. */
class WireFormatTest {
    private val id = "0193a4f1-c2d0-7000-8000-000000000001"

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private inline fun <reified T> encode(value: T): JsonElement = TaskyJson.encodeToJsonElement(value)

    @Test
    fun createOp() {
        val op = Op(
            entity_id = id,
            hlc = "0193a4f1c2d0-0000-7f3a9c01e5b24d88",
            action = OpAction.Create(EntityData.Item(ItemData(name = "Milk", category = "Dairy"))),
        )
        // The server writes `"default_unit": null`; we leave the null default out, which it accepts
        // (see optionalFieldsMayBeOmitted below).
        assertEquals(
            json(
                """{"entity_id":"$id","hlc":"0193a4f1c2d0-0000-7f3a9c01e5b24d88",
                "action":{"type":"create","value":{"type":"item","value":{"name":"Milk","category":"Dairy"}}}}""",
            ),
            encode(op),
        )
        // And the server's form (with explicit null) decodes to the same value.
        val serverForm = """{"entity_id":"$id","hlc":"0193a4f1c2d0-0000-7f3a9c01e5b24d88",
            "action":{"type":"create","value":{"type":"item","value":{"name":"Milk","category":"Dairy","default_unit":null}}}}"""
        assertEquals(op, TaskyJson.decodeFromString<Op>(serverForm))
    }

    @Test
    fun setAndDeleteOps() {
        assertEquals(
            json("""{"type":"set_contribution","value":{"type":"purchased","value":true}}"""),
            encode<OpAction>(OpAction.SetContribution(ContributionField.Purchased(true))),
        )
        // Clearing a field must send an explicit null.
        assertEquals(
            json("""{"type":"set_item","value":{"type":"category","value":null}}"""),
            encode<OpAction>(OpAction.SetItem(ItemField.Category(null))),
        )
        assertEquals(
            json("""{"type":"delete","value":"meal_ingredient"}"""),
            encode<OpAction>(OpAction.Delete(EntityKind.MealIngredient)),
        )
        assertEquals(
            json("""{"type":"set_plan_entry","value":{"type":"slot","value":"dinner"}}"""),
            encode<OpAction>(OpAction.SetPlanEntry(PlanEntryField.Slot(MealSlot.Dinner))),
        )
        assertEquals(
            json("""{"type":"set_contribution","value":{"type":"amount","value":null}}"""),
            encode<OpAction>(OpAction.SetContribution(ContributionField.Amount(null))),
        )
    }

    @Test
    fun optionalFieldsMayBeOmitted() {
        assertEquals(
            json("""{"type":"item","value":{"name":"Eggs"}}"""),
            encode<EntityData>(EntityData.Item(ItemData(name = "Eggs"))),
        )
        assertEquals(json("""{"since":0}"""), encode(SyncRequest(since = 0)))
        assertEquals(
            json("""{"type":"plan_entry","value":{"date":"2026-11-09","slot":"lunch","free_text":"Leftovers"}}"""),
            encode<EntityData>(
                EntityData.PlanEntry(PlanEntryData(date = "2026-11-09", slot = MealSlot.Lunch, free_text = "Leftovers")),
            ),
        )
    }

    @Test
    fun liveEvent() {
        val ev = TaskyJson.decodeFromString<LiveEvent>("""{"kind":"household_changed","cursor":42}""")
        assertEquals(LiveEvent(LiveEventKind.HouseholdChanged, 42), ev)
        assertEquals(json("""{"kind":"household_changed","cursor":42}"""), encode(ev))
    }

    @Test
    fun syncResponseDecodes() {
        val text = """{
          "changes": [
            {"id": "$id", "seq": 42, "deleted": false,
             "data": {"type": "contribution", "value": {"list_id": "a", "item_id": "b", "amount": 1.5, "unit": "kg",
                      "plan_entry_id": null, "note": null, "purchased": true, "purchased_by": "u", "archived": false}},
             "updated_by": "u", "updated_at_ms": 1794054187732},
            {"id": "x", "seq": 43, "deleted": true, "data": {"type": "list", "value": {"name": "Old"}},
             "updated_by": null, "updated_at_ms": 1}
          ],
          "aliases": [{"alias_id": "p", "target_id": "q", "seq": 44}],
          "rejected": [{"index": 3, "entity_id": "r", "code": "validation", "message": "name must not be empty"}],
          "cursor": 44, "has_more": false, "full_resync": false, "server_time_ms": 1794054187733,
          "some_future_field": {"nested": true}
        }"""
        val resp = TaskyJson.decodeFromString<SyncResponse>(text)
        assertEquals(2, resp.changes.size)
        val first: Entity = resp.changes[0]
        assertEquals(
            EntityData.Contribution(
                ContributionData(
                    list_id = "a", item_id = "b", amount = 1.5, unit = "kg",
                    purchased = true, purchased_by = "u", archived = false,
                ),
            ),
            first.data,
        )
        assertTrue(resp.changes[1].deleted)
        assertNull(resp.changes[1].updated_by)
        assertEquals("q", resp.aliases.single().target_id)
        assertEquals(RejectCode.Validation, resp.rejected.single().code)
        assertEquals(3, resp.rejected.single().index)
        assertFalse(resp.has_more)
    }

    @Test
    fun errorBodyDecodes() {
        val body = TaskyJson.decodeFromString<ErrorBody>("""{"error":"username_taken","message":"username is already taken"}""")
        assertEquals(ErrorCode.UsernameTaken, body.error)
    }
}
