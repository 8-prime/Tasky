package dev.tasky.android.e2e

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tasky.android.api.ApiClient
import dev.tasky.android.api.ApiException
import dev.tasky.android.data.Session
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.android.domain.Expenses
import dev.tasky.android.domain.MealPlanning
import dev.tasky.android.domain.ShoppingList
import dev.tasky.android.domain.Units
import dev.tasky.android.sync.Hlc
import dev.tasky.android.sync.LiveConnection
import dev.tasky.android.sync.LocalStore
import dev.tasky.android.sync.SyncEngine
import dev.tasky.android.sync.SyncTarget
import dev.tasky.api.CreateHouseholdRequest
import dev.tasky.api.ErrorCode
import dev.tasky.api.JoinHouseholdRequest
import dev.tasky.api.LiveEvent
import dev.tasky.api.LiveEventKind
import dev.tasky.api.MealSlot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Two devices (separate Room databases, clocks and sync engines) against a real Tasky server.
 *
 * Opt-in: start the server with a fixed setup token and run
 * `TASKY_TEST_SERVER=http://127.0.0.1:8080 TASKY_TEST_SETUP_TOKEN=… ./gradlew testDebugUnitTest`.
 */
@RunWith(RobolectricTestRunner::class)
class ServerEndToEndTest {
    private val server = System.getProperty("tasky.server").orEmpty()
    private val setupToken = System.getProperty("tasky.setupToken").orEmpty()
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val devices = mutableListOf<Device>()

    inner class Device(val session: Session, node: String) {
        val db: TaskyDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskyDatabase::class.java)
            .allowMainThreadQueries().build()
        var unauthorized = false
        val api = ApiClient(http, session.serverUrl, session.token) { unauthorized = true }
        val hlc = Hlc(node)
        val store = LocalStore(db) { session.userId }
        val engine = SyncEngine(store, hlc, target = { SyncTarget(api, session.householdId) })
        val repo = TaskyRepository(store, hlc) {}
        val q = db.queries()

        suspend fun groceries() = q.lists().first().single { it.name == "Groceries" }
        suspend fun lines(listId: String) =
            ShoppingList.lines(q.activeContributionsNow(listId), q.items().first().associateBy { it.id })
    }

    @Before
    fun requireServer() {
        assumeTrue("set TASKY_TEST_SERVER and TASKY_TEST_SETUP_TOKEN to run", server.isNotEmpty() && setupToken.isNotEmpty())
    }

    @After
    fun tearDown() {
        scope.cancel()
        devices.forEach { it.db.close() }
    }

    private fun unique() = UUID.randomUUID().toString().take(8)

    /** Creates a household with user A and lets user B join it with an invite. */
    private suspend fun household(): Pair<Device, Device> {
        val anon = ApiClient(http, server)
        val info = anon.serverInfo()
        assertEquals("tasky", info.name)
        assertEquals(1, info.api_version)
        val a = anon.createHousehold(
            CreateHouseholdRequest(setupToken, "Test home ${unique()}", "anna_${unique()}", "Anna", "password-a", "test A"),
        )
        val sessionA = Session(anon.serverUrl, a.session_token, a.user.id, a.user.username, "Anna", a.households[0].id, a.households[0].name)
        val invite = ApiClient(http, server, a.session_token).createInvite(sessionA.householdId)
        assertTrue(Regex("[A-Z0-9]{4}-[A-Z0-9]{4}").matches(invite.code))
        // Codes ignore case and dashes.
        val b = anon.join(JoinHouseholdRequest(invite.code.lowercase().replace("-", ""), "ben_${unique()}", "Ben", "password-b", "test B"))
        assertEquals(sessionA.householdId, b.households.single().id)
        val sessionB = Session(anon.serverUrl, b.session_token, b.user.id, b.user.username, "Ben", b.households[0].id, b.households[0].name)
        return Device(sessionA, "deva").also(devices::add) to Device(sessionB, "devb").also(devices::add)
    }

    private suspend fun syncAll(vararg ds: Device) {
        // Twice around, so everyone sees everyone's changes.
        repeat(2) { ds.forEach { it.engine.sync() } }
    }

    @Test
    fun expensesSyncAndSettleUpToZero() = runBlocking {
        val (a, b) = household()
        syncAll(a, b)
        val (anna, ben) = a.session.userId to b.session.userId

        // A pays the rent for both; B (offline) buys groceries for both.
        val rent = a.repo.createExpense(TaskyRepository.ExpenseInput("Rent", 100_000, "EUR", "2026-11-01", anna, listOf(anna, ben), "Home"))
        b.repo.createExpense(TaskyRepository.ExpenseInput("Weekly shop", 4_251, "EUR", "2026-11-02", ben, listOf(anna, ben)))
        syncAll(a, b)

        // Concurrent edits to different fields of the rent both survive.
        a.repo.updateExpense(a.q.expenses().first().single { it.id == rent }, TaskyRepository.ExpenseInput("Rent", 110_000, "EUR", "2026-11-01", anna, listOf(anna, ben), "Home"))
        b.repo.updateExpense(b.q.expenses().first().single { it.id == rent }, TaskyRepository.ExpenseInput("Rent", 100_000, "EUR", "2026-11-01", anna, listOf(anna, ben), "Housing"))
        syncAll(a, b)

        for (d in listOf(a, b)) {
            val r = d.q.expenses().first().single { it.id == rent }
            assertEquals(110_000L to "Housing", r.amountMinor to r.category)
        }
        val balances = Expenses.balances(a.q.expenses().first())["EUR"]!!
        assertEquals(balances, Expenses.balances(b.q.expenses().first())["EUR"])
        assertEquals(0L, balances.values.sum())
        val transfer = Expenses.settleUp(balances, "EUR").single()
        assertEquals(ben to anna, transfer.from to transfer.to)

        // B pays it back; both devices agree everyone is settled.
        b.repo.recordSettlement(transfer.from, transfer.to, transfer.amountMinor, "EUR", "2026-11-08")
        syncAll(b, a)
        for (d in listOf(a, b)) {
            assertTrue(Expenses.balances(d.q.expenses().first())["EUR"]!!.values.all { it == 0L })
            assertEquals(mapOf("EUR" to 114_251L), Expenses.spent(d.q.expenses().first()))
            assertEquals(0, d.db.outbox().count())
        }
    }

    @Test
    fun onboardingErrors() = runBlocking {
        val anon = ApiClient(http, server)
        val e = runCatching {
            anon.createHousehold(CreateHouseholdRequest("tsk_wrong", "Home", "x_${unique()}", "X", "password-x", "t"))
        }.exceptionOrNull() as ApiException
        assertEquals(ErrorCode.InvalidSetupToken, e.code)
        val bad = runCatching { anon.join(JoinHouseholdRequest("AAAA-BBBB", "y_${unique()}", "Y", "password-y", "t")) }
            .exceptionOrNull() as ApiException
        assertEquals(ErrorCode.InvalidInvite, bad.code)
        val login = runCatching { anon.login(dev.tasky.api.LoginRequest("nobody_${unique()}", "password", "t")) }
            .exceptionOrNull() as ApiException
        assertEquals(ErrorCode.InvalidCredentials, login.code)
        assertFalse(login.isUnauthorized)
    }

    @Test
    fun sameItemFromTwoDevicesMergesAndAmountsAddUp() = runBlocking {
        val (a, b) = household()
        syncAll(a, b)
        val listA = a.groceries().id
        assertEquals(listA, b.groceries().id)

        // Both add pasta while offline; B uses a different spelling.
        a.repo.addToList(listA, "Pasta", 500.0, "g", category = "Dry goods")
        b.repo.addToList(listA, " pasta ", 0.2, "kg")
        a.engine.sync()
        b.engine.sync() // B's item is merged into A's (alias)
        a.engine.sync()

        for (d in listOf(a, b)) {
            val line = d.lines(listA).single()
            assertEquals("Pasta", line.name)
            assertEquals("Dry goods", line.category)
            assertEquals("700 g", Units.format(line.open))
            assertEquals(1, d.q.items().first().size)
            assertEquals(0, d.db.outbox().count())
        }
    }

    @Test
    fun concurrentCheckOffKeepsTheNewAmountOpen() = runBlocking {
        val (a, b) = household()
        syncAll(a, b)
        val list = a.groceries().id
        a.repo.addToList(list, "Milk", 1.0, "l")
        syncAll(a, b)

        // A checks the line while B (not yet synced) adds another litre.
        a.repo.checkLine(list, b.lines(list).single().itemId)
        b.repo.addToList(list, "milk", 1.0, "l")
        a.engine.sync()
        b.engine.sync()
        a.engine.sync()

        for (d in listOf(a, b)) {
            val line = d.lines(list).single()
            assertFalse(line.checked)
            assertEquals("1 l", Units.format(line.open))
            assertEquals(1, line.purchasedIds.size)
        }
        // B checks the rest: the line is checked, by both people.
        b.repo.checkLine(list, b.lines(list).single().itemId)
        syncAll(b, a)
        val line = a.lines(list).single()
        assertTrue(line.checked)
        assertEquals(setOf(a.session.userId, b.session.userId), line.checkedBy.toSet())

        // Clear checked archives everything; uncheck brings nothing back after that.
        a.repo.clearChecked(list)
        syncAll(a, b)
        assertTrue(b.lines(list).isEmpty())
    }

    @Test
    fun plannedMealsAddToTheListOnceWithScaledAmounts() = runBlocking {
        val (a, b) = household()
        syncAll(a, b)
        val list = a.groceries().id

        val meal = a.repo.createMeal("Spaghetti", defaultServings = 2, notes = null, url = "https://example.com/spaghetti")
        a.repo.addIngredient(meal, "Spaghetti", 250.0, "g", null, category = "Dry goods")
        a.repo.addIngredient(meal, "Tomato sauce", 1.0, "can", null)
        val entry = a.repo.createPlanEntry(
            TaskyRepository.PlanEntryInput("2026-11-09", MealSlot.Dinner, ownerUserId = null, mealId = meal, freeText = null, servings = null, note = null),
        )
        syncAll(a, b)

        // Both people press "add to list" for the same entry: deterministic IDs, no doubling.
        for (d in listOf(a, b)) {
            val members = 2
            val entries = d.q.planEntries("2026-11-09", "2026-11-15").first()
            assertEquals(entry, entries.single().id)
            val proposals = MealPlanning.propose(
                entries, d.q.meals().first().associateBy { it.id },
                d.q.allIngredients().first().groupBy { it.mealId }, d.q.items().first().associateBy { it.id }, members,
            )
            assertEquals(2, proposals.size)
            d.repo.addToList(list, proposals)
        }
        syncAll(a, b)
        for (d in listOf(a, b)) {
            val lines = d.lines(list).associateBy { it.name }
            assertEquals("250 g", Units.format(lines.getValue("Spaghetti").open)) // 250 g * 2 servings / 2
            assertEquals("1 can", Units.format(lines.getValue("Tomato sauce").open))
            assertEquals(2, d.q.contributionsOfPlanEntry(entry).size)
        }

        // Spaghetti bought; then the entry is deleted: only the unpurchased sauce goes away.
        val spaghettiItem = b.lines(list).first { it.name == "Spaghetti" }.itemId
        b.repo.checkLine(list, spaghettiItem)
        syncAll(b, a)
        a.repo.deletePlanEntry(entry)
        syncAll(a, b)
        for (d in listOf(a, b)) {
            assertTrue(d.q.planEntries("2026-11-09", "2026-11-15").first().isEmpty())
            val remaining = d.q.contributionsOfPlanEntry(entry)
            assertEquals(1, remaining.size)
            assertTrue(remaining.single().purchased)
        }
    }

    @Test
    fun invalidOpsAreRejectedAndRolledBack() = runBlocking {
        val (a, _) = household()
        a.engine.sync()
        val rejected = CompletableDeferred<dev.tasky.api.RejectedOp>()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launchCollect(a.engine) { rejected.complete(it) }
        // Servings out of range: optimistic row appears, server refuses, row disappears.
        val id = a.repo.createMeal("Huge pot", defaultServings = 5000, notes = null, url = null)
        assertEquals("Huge pot", a.q.meal(id).first()?.name)
        a.engine.sync()
        assertNull(a.q.meal(id).first())
        assertEquals(dev.tasky.api.RejectCode.Validation, withTimeout(5_000) { rejected.await() }.code)
        assertEquals(0, a.db.outbox().count())
        job.cancel()
    }

    @Test
    fun liveSocketAnnouncesChanges() = runBlocking {
        val (a, b) = household()
        syncAll(a, b)
        val hello = CompletableDeferred<LiveEvent>()
        val changed = CompletableDeferred<LiveEvent>()
        val live = LiveConnection(http, scope, { b.session }, onEvent = { e ->
            if (e.kind == LiveEventKind.Hello) hello.complete(e)
            if (e.kind == LiveEventKind.Changed) changed.complete(e)
        }, onUnauthorized = {})
        live.start()
        assertEquals(b.engine.cursor(), withTimeout(10_000) { hello.await() }.cursor)
        a.repo.addToList(a.groceries().id, "Bread", 1.0, "pcs")
        a.engine.sync()
        val event = withTimeout(10_000) { changed.await() }
        assertTrue(event.cursor > b.engine.cursor())
        b.engine.sync()
        assertEquals(event.cursor, b.engine.cursor())
        assertEquals("Bread", b.lines(b.groceries().id).single().name)
        live.stop()
    }

    @Test
    fun revokedSessionReportsUnauthorized() = runBlocking {
        val (a, b) = household()
        a.api.logout()
        val e = runCatching { a.engine.sync() }.exceptionOrNull()
        assertTrue(e is ApiException && e.isUnauthorized)
        assertTrue(a.unauthorized)
        // The other device is unaffected.
        assertTrue(b.engine.sync())
        assertNotEquals(0L, b.engine.cursor())
    }

    @Test
    fun checklistsAndNotesSyncBetweenDevices() = runBlocking {
        val (a, b) = household()
        syncAll(a, b)
        assertEquals(dev.tasky.android.data.db.LIST_GROCERY, a.groceries().kind)

        val checklist = a.repo.createList("Weekend", dev.tasky.api.ListKind.Checklist)
        a.repo.addEntry(checklist, "Call plumber")
        a.repo.addEntry(checklist, "Water plants")
        val note = a.repo.createList("Ideas", dev.tasky.api.ListKind.Note)
        a.repo.setNoteBody(note, "Paint the hallway")
        syncAll(a, b)

        val listsB = b.q.lists().first().associateBy { it.id }
        assertTrue(listsB.getValue(checklist).isChecklist)
        assertEquals("Paint the hallway", listsB.getValue(note).body)
        val entriesB = b.q.listEntriesNow(checklist)
        assertEquals(listOf("Call plumber", "Water plants"), entriesB.map { it.text })
        // Checklist entries stay out of the grocery catalog.
        assertTrue(b.q.items().first().isEmpty())

        // B checks an entry while A renames it: both edits survive, and A sees who checked.
        b.repo.setEntryChecked(entriesB[0].id, true)
        a.repo.setEntryText(entriesB[0].id, "Call the plumber")
        b.repo.setNoteBody(note, "Paint the hallway\nFix the bike")
        syncAll(a, b)
        for (d in listOf(a, b)) {
            val e = d.q.listEntriesNow(checklist).first()
            assertEquals("Call the plumber", e.text)
            assertTrue(e.checked)
            assertEquals(b.session.userId, e.checkedBy)
            assertEquals("Paint the hallway\nFix the bike", d.q.lists().first().single { it.id == note }.body)
            assertEquals(0, d.db.outbox().count())
        }

        // Clear checked deletes the checked entry everywhere; deleting a list removes its entries.
        a.repo.clearCheckedEntries(checklist)
        syncAll(a, b)
        assertEquals(listOf("Water plants"), b.q.listEntriesNow(checklist).map { it.text })
        b.repo.deleteList(checklist)
        syncAll(a, b)
        assertTrue(a.q.listEntriesNow(checklist).isEmpty())
        assertNull(a.q.lists().first().firstOrNull { it.id == checklist })
    }
}

private fun CoroutineScope.launchCollect(engine: SyncEngine, onEach: (dev.tasky.api.RejectedOp) -> Unit) =
    launch { engine.rejections.collect { onEach(it) } }
