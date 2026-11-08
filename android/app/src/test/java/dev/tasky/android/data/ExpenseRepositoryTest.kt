package dev.tasky.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tasky.android.api.TaskyJson
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.android.sync.Hlc
import dev.tasky.android.sync.LocalStore
import dev.tasky.api.ExpenseField
import dev.tasky.api.ExpenseKind
import dev.tasky.api.Op
import dev.tasky.api.OpAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseRepositoryTest {
    private lateinit var db: TaskyDatabase
    private lateinit var repo: TaskyRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskyDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TaskyRepository(LocalStore(db) { "a" }, Hlc("t")) {}
    }

    @After
    fun tearDown() = db.close()

    private suspend fun outbox(): List<OpAction> =
        db.outbox().all().map { TaskyJson.decodeFromString(Op.serializer(), it.op).action }

    private fun input(amount: Long = 4_250, split: List<String> = listOf("a", "b")) =
        TaskyRepository.ExpenseInput(" Weekly shop ", amount, "EUR", "2026-11-07", "a", split, category = " ", note = null)

    @Test
    fun createStoresTheEntryAndQueuesOneOp() = runBlocking {
        val id = repo.createExpense(input())
        val row = db.queries().expenses().first().single()
        assertEquals(id, row.id)
        assertEquals("Weekly shop", row.description)
        assertEquals(listOf("a", "b"), row.participants)
        assertNull("blank category is stored as null", row.category)
        assertEquals("expense", row.kind)
        assertEquals(1, outbox().size)
    }

    @Test
    fun updateSendsOnlyChangedFields() = runBlocking {
        repo.createExpense(input())
        val row = db.queries().expenses().first().single()
        // Same people in another order is no change.
        repo.updateExpense(row, input(amount = 4_390, split = listOf("b", "a")))
        val sets = outbox().drop(1)
        assertEquals(listOf<OpAction>(OpAction.SetExpense(ExpenseField.AmountMinor(4_390))), sets)
        assertEquals(4_390L, db.queries().expenses().first().single().amountMinor)
    }

    @Test
    fun reversingASettlementReplacesIt() = runBlocking {
        val first = repo.recordSettlement(from = "b", to = "a", amountMinor = 2_000, currency = "EUR", date = "2026-11-07")
        val row = db.queries().expenses().first().single()
        repo.updateExpense(row, TaskyRepository.ExpenseInput(row.description, 2_000, "EUR", row.date, "a", listOf("b")))
        val now = db.queries().expenses().first().single()
        assertTrue(now.id != first)
        assertEquals("a" to listOf("b"), now.paidBy to now.participants)
        assertEquals(ExpenseKind.Settlement.string, now.kind)
        assertTrue(outbox().any { it is OpAction.Delete })
    }
}
