package dev.tasky.android.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.tasky.android.data.db.TaskyDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Upgrading the app must keep local data, above all the outbox of unsent edits. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TaskyDatabase::class.java)

    @Test
    fun v1ToV2AddsListKindsAndKeepsTheOutbox() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO lists (id, name) VALUES ('l1', 'Groceries')")
            execSQL("INSERT INTO outbox (entityId, op, inFlight) VALUES ('e1', '{\"pending\":true}', 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true)

        db.query("SELECT kind, body FROM lists WHERE id = 'l1'").use { c ->
            c.moveToFirst()
            assertEquals("grocery", c.getString(0))
            assertNull(c.getString(1))
        }
        db.query("SELECT entityId, op FROM outbox").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("e1", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM list_entries").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun v2ToV3AddsExpensesAndKeepsTheOutbox() {
        helper.createDatabase(DB, 2).apply {
            execSQL("INSERT INTO lists (id, name, kind) VALUES ('l1', 'Groceries', 'grocery')")
            execSQL("INSERT INTO outbox (entityId, op, inFlight) VALUES ('e1', '{\"pending\":true}', 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 3, true)

        db.query("SELECT COUNT(*) FROM lists").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT entityId FROM outbox").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("e1", c.getString(0))
        }
        db.execSQL(
            "INSERT INTO expenses (id, description, amountMinor, currency, date, paidBy, splitAmong, kind) " +
                "VALUES ('x', 'Rent', 100000, 'EUR', '2026-11-01', 'a', 'a,b', 'expense')",
        )
        db.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
