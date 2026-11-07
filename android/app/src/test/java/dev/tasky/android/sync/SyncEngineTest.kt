package dev.tasky.android.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.android.data.db.getVisible
import dev.tasky.api.ContributionData
import dev.tasky.api.ContributionField
import dev.tasky.api.Entity
import dev.tasky.api.EntityData
import dev.tasky.api.EntityKind
import dev.tasky.api.ItemAlias
import dev.tasky.api.ItemData
import dev.tasky.api.ItemField
import dev.tasky.api.ListData
import dev.tasky.api.Op
import dev.tasky.api.OpAction
import dev.tasky.api.RejectCode
import dev.tasky.api.RejectedOp
import dev.tasky.api.SyncRequest
import dev.tasky.api.SyncResponse
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Outbox replay and sync-loop behaviour against a scripted server. */
@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private lateinit var db: TaskyDatabase
    private lateinit var store: LocalStore
    private var now = 1_800_000_000_000L
    private val hlc = Hlc("test", wallClock = { now })
    private val requests = mutableListOf<SyncRequest>()
    private var handler: suspend (SyncRequest) -> SyncResponse = { response() }
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = LocalStore(db) { "me" }
        engine = SyncEngine(store, hlc, target = {
            SyncTarget({ _, req -> requests += req; handler(req) }, "H")
        })
    }

    @After
    fun tearDown() = db.close()

    private fun response(
        changes: List<Entity> = emptyList(),
        cursor: Long = 1,
        rejected: List<RejectedOp> = emptyList(),
        aliases: List<ItemAlias> = emptyList(),
        hasMore: Boolean = false,
        fullResync: Boolean = false,
    ) = SyncResponse(changes, aliases, rejected, cursor, hasMore, fullResync, now + 1234)

    private fun entity(id: String, seq: Long, data: EntityData, deleted: Boolean = false) =
        Entity(id, seq, deleted, data, "u", now)

    private fun op(id: String, action: OpAction) = Op(id, hlc.next(), action)
    private fun item(name: String, category: String? = null) = EntityData.Item(ItemData(name, category))
    private suspend fun visible(kind: EntityKind, id: String) = db.entities().getVisible(kind, id)
    private suspend fun outboxSize() = db.outbox().count()

    @Test
    fun commitIsOptimisticAndSyncDropsTheBatch() = runBlocking {
        store.commit(listOf(op("i1", OpAction.Create(item("Milk")))))
        assertEquals(item("Milk"), visible(EntityKind.Item, "i1"))
        assertEquals(1, outboxSize())

        handler = { req ->
            assertEquals(0L, req.since)
            assertEquals(1, req.ops!!.size)
            response(listOf(entity("i1", 1, item("Milk", "Dairy"))), cursor = 1)
        }
        assertTrue(engine.sync())
        assertEquals(0, outboxSize())
        assertEquals(item("Milk", "Dairy"), visible(EntityKind.Item, "i1"))
        assertEquals(1L, store.cursor())
        assertEquals(1234L, hlc.offsetMs)
    }

    @Test
    fun editsMadeDuringARequestSurviveAndAreSentNext() = runBlocking {
        store.commit(listOf(op("i1", OpAction.Create(item("Milk")))))
        var round = 0
        handler = { req ->
            round++
            if (round == 1) {
                // The user edits while the request is in flight.
                store.commit(listOf(op("i1", OpAction.SetItem(ItemField.Category("Dairy")))))
                response(listOf(entity("i1", 1, item("Milk"))), cursor = 1)
            } else {
                assertEquals(1L, req.since)
                assertEquals(OpAction.SetItem(ItemField.Category("Dairy")), req.ops!!.single().action)
                response(listOf(entity("i1", 2, item("Milk", "Dairy"))), cursor = 2)
            }
        }
        engine.sync()
        assertEquals(2, round)
        assertEquals(item("Milk", "Dairy"), visible(EntityKind.Item, "i1"))
        assertEquals(0, outboxSize())
    }

    @Test
    fun pendingOpsAreReappliedOnTopOfServerChanges() = runBlocking {
        // Server knows the item; we rename it locally but haven't synced yet.
        handler = { response(listOf(entity("i1", 1, item("Milk"))), cursor = 1) }
        engine.sync()
        // Now someone else changes the category, and our rename is pending when that arrives.
        store.commit(listOf(op("i1", OpAction.SetItem(ItemField.Name("Oat milk")))))
        handler = { req ->
            response(listOf(entity("i1", 2, item("Milk", "Dairy")), entity("i1", 3, item("Oat milk", "Dairy"))), cursor = 3)
                .also { assertEquals(1, req.ops!!.size) }
        }
        engine.sync()
        assertEquals(item("Oat milk", "Dairy"), visible(EntityKind.Item, "i1"))
    }

    @Test
    fun rejectedCreateDisappearsAndIsReported() = runBlocking {
        store.commit(listOf(op("l1", OpAction.Create(EntityData.List(ListData(" "))))))
        val reported = mutableListOf<RejectedOp>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launchCollect(engine, reported)
        handler = { response(rejected = listOf(RejectedOp(0, "l1", RejectCode.Validation, "name must not be empty"))) }
        engine.sync()
        job.cancel()
        assertNull(visible(EntityKind.List, "l1"))
        assertEquals(0, outboxSize())
        assertEquals(RejectCode.Validation, reported.single().code)
    }

    @Test
    fun staleOpWithoutAChangeFallsBackToServerState() = runBlocking {
        handler = { response(listOf(entity("i1", 1, item("Milk", "Dairy"))), cursor = 1) }
        engine.sync()
        store.commit(listOf(op("i1", OpAction.SetItem(ItemField.Category("Drinks")))))
        assertEquals(item("Milk", "Drinks"), visible(EntityKind.Item, "i1"))
        // The server ignored the op as stale (someone wrote a newer value already): no change.
        handler = { response(cursor = 1) }
        engine.sync()
        assertEquals(item("Milk", "Dairy"), visible(EntityKind.Item, "i1"))
    }

    @Test
    fun deletesRemoveRows() = runBlocking {
        handler = { response(listOf(entity("i1", 1, item("Milk"))), cursor = 1) }
        engine.sync()
        handler = { response(listOf(entity("i1", 2, item("Milk"), deleted = true)), cursor = 2) }
        engine.sync()
        assertNull(visible(EntityKind.Item, "i1"))
        assertNull(db.entities().serverEntity("i1"))
    }

    @Test
    fun hasMoreKeepsTheOptimisticOverlayUntilTheLastPage() = runBlocking {
        store.commit(listOf(op("i1", OpAction.Create(item("Milk", "Dairy")))))
        var page = 0
        handler = { req ->
            page++
            when (page) {
                1 -> {
                    assertEquals(1, req.ops!!.size)
                    response(listOf(entity("x", 1, item("Eggs"))), cursor = 1, hasMore = true)
                }
                else -> {
                    // Mid-sync: our item is still shown with its optimistic value.
                    assertEquals(item("Milk", "Dairy"), visible(EntityKind.Item, "i1"))
                    assertEquals(null, req.ops)
                    assertEquals(1L, req.since)
                    response(listOf(entity("i1", 2, item("Milk", "Dairy"))), cursor = 2)
                }
            }
        }
        engine.sync()
        assertEquals(2, page)
        assertEquals(0, outboxSize())
        assertEquals(item("Eggs"), visible(EntityKind.Item, "x"))
    }

    @Test
    fun failedRequestKeepsTheOutbox() = runBlocking {
        store.commit(listOf(op("i1", OpAction.Create(item("Milk")))))
        handler = { throw java.io.IOException("offline") }
        runCatching { engine.sync() }
        assertEquals(1, outboxSize())
        assertEquals(1, db.outbox().unsentCount())
        handler = { req -> assertEquals(1, req.ops!!.size); response(listOf(entity("i1", 1, item("Milk")))) }
        engine.sync()
        assertEquals(0, outboxSize())
    }

    @Test
    fun fullResyncWipesSyncedDataButKeepsTheOutbox() = runBlocking {
        handler = { response(listOf(entity("old", 5, item("Old")), entity("i1", 6, item("Milk"))), cursor = 6) }
        engine.sync()
        // Offline edit, then the server turns out to have been restored from a backup.
        handler = { throw java.io.IOException("offline") }
        store.commit(listOf(op("i1", OpAction.SetItem(ItemField.Category("Dairy")))))
        runCatching { engine.sync() }
        handler = { req ->
            assertEquals(6L, req.since)
            response(listOf(entity("i1", 2, item("Milk"))), cursor = 2, fullResync = true)
        }
        engine.sync()
        assertNull(visible(EntityKind.Item, "old"))
        // Our edit was sent in the same request; until the server echoes it, it's still overlaid.
        assertEquals(2L, store.cursor())
        assertEquals(0, outboxSize())
        assertEquals(item("Milk"), visible(EntityKind.Item, "i1"))
    }

    @Test
    fun fullResyncReappliesQueuedOps() = runBlocking {
        handler = { response(listOf(entity("old", 5, item("Old")), entity("i1", 6, item("Milk"))), cursor = 6) }
        engine.sync()
        handler = { req ->
            // Page 1 of the resync: our op isn't reflected yet.
            if (req.since == 6L) response(listOf(entity("i1", 1, item("Milk"))), cursor = 1, hasMore = true, fullResync = true)
            else response(listOf(entity("i1", 2, item("Milk", "Dairy"))), cursor = 2)
        }
        store.commit(listOf(op("i1", OpAction.SetItem(ItemField.Category("Dairy")))))
        engine.sync()
        assertNull(visible(EntityKind.Item, "old"))
        assertEquals(item("Milk", "Dairy"), visible(EntityKind.Item, "i1"))
    }

    @Test
    fun aliasesMergeLocalItems() = runBlocking {
        // Server already has "Milk" as T. We create our own "milk" (A) and add it to a list.
        handler = { response(listOf(entity("T", 1, item("Milk")), entity("L", 2, EntityData.List(ListData("Groceries")))), cursor = 2) }
        engine.sync()
        val contribution = { item: String ->
            EntityData.Contribution(ContributionData(list_id = "L", item_id = item, amount = 1.0, unit = "l", purchased = false, archived = false))
        }
        store.commit(listOf(op("A", OpAction.Create(item("milk"))), op("c1", OpAction.Create(contribution("A")))))
        var round = 0
        handler = { req ->
            round++
            if (round == 1) {
                // A second contribution is added while the first batch is in flight.
                store.commit(listOf(op("c2", OpAction.Create(contribution("A")))))
                response(
                    listOf(entity("c1", 4, contribution("T"))),
                    aliases = listOf(ItemAlias("A", "T", 3)),
                    cursor = 4,
                )
            } else {
                // The queued op was rewritten to the merged item.
                val create = req.ops!!.single().action as OpAction.Create
                assertEquals("T", (create.value as EntityData.Contribution).value.item_id)
                response(listOf(entity("c2", 5, contribution("T"))), cursor = 5)
            }
        }
        engine.sync()
        assertNull(visible(EntityKind.Item, "A"))
        assertEquals(contribution("T"), visible(EntityKind.Contribution, "c1"))
        assertEquals(contribution("T"), visible(EntityKind.Contribution, "c2"))
    }

    @Test
    fun checkOffSetsPurchasedByOptimistically() = runBlocking {
        val c = EntityData.Contribution(ContributionData(list_id = "L", item_id = "I", purchased = false, archived = false))
        handler = { response(listOf(entity("c", 1, c)), cursor = 1) }
        engine.sync()
        store.commit(listOf(op("c", OpAction.SetContribution(ContributionField.Purchased(true)))))
        val v = visible(EntityKind.Contribution, "c") as EntityData.Contribution
        assertTrue(v.value.purchased)
        assertEquals("me", v.value.purchased_by)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCollect(engine: SyncEngine, into: MutableList<RejectedOp>) =
    launch { engine.rejections.collect { into += it } }
