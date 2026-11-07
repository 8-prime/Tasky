package dev.tasky.android.sync

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.tasky.android.api.TaskyJson
import dev.tasky.android.data.db.OutboxRow
import dev.tasky.android.data.db.ServerEntityRow
import dev.tasky.android.data.db.SyncStateRow
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.android.data.db.clearVisible
import dev.tasky.android.data.db.getVisible
import dev.tasky.android.data.db.putVisible
import dev.tasky.api.EntityData
import dev.tasky.api.ItemAlias
import dev.tasky.api.Op
import dev.tasky.api.Entity as WireEntity

/**
 * Local copy of the household's data.
 *
 * Invariant: for every entity, the visible row (what the UI reads) equals the last server state
 * (`server_entities`) with all pending outbox ops for that entity applied on top
 * ([OpApplier.materialize]). Local edits apply their op to the visible row and append it to the
 * outbox; sync results update the server state and recompute the affected visible rows.
 */
class LocalStore(
    val db: TaskyDatabase,
    /** Current user's id, used as `purchased_by` for optimistic check-offs. */
    private val me: () -> String?,
) {
    private val entities = db.entities()
    private val outbox = db.outbox()

    /** Applies ops optimistically and queues them for the server, atomically. */
    suspend fun commit(ops: List<Op>) {
        if (ops.isEmpty()) return
        db.withTransaction {
            val user = me()
            for (op in ops) {
                outbox.append(op.entity_id, TaskyJson.encodeToString(Op.serializer(), op))
                val kind = op.action.kind
                val current = entities.getVisible(kind, op.entity_id)
                entities.putVisible(op.entity_id, OpApplier.apply(current, op.action, user))
            }
        }
    }

    suspend fun cursor(): Long = entities.state(KEY_CURSOR)?.toLongOrNull() ?: 0L

    /** Takes up to [limit] unsent ops and marks them in flight. */
    suspend fun takeBatch(limit: Int): List<OutboxRow> = db.withTransaction {
        val rows = outbox.unsent(limit)
        if (rows.isNotEmpty()) outbox.markInFlight(rows.map { it.seq })
        rows
    }

    /** After a failed request: everything goes back to unsent (ops are idempotent). */
    suspend fun releaseInFlight() = outbox.resetInFlight()

    suspend fun hasUnsent(): Boolean = outbox.unsentCount() > 0

    data class Applied(val rejected: List<Pair<OutboxRow, dev.tasky.api.RejectedOp>>)

    /**
     * Applies one sync response in a single transaction.
     *
     * [batch] are the ops sent with this request. They stay in the outbox (in flight, still
     * overlaying the visible state) until the last page (`has_more = false`) arrives, so
     * optimistic values don't flicker back while later pages bring their effects.
     */
    suspend fun applySyncResponse(
        batch: List<OutboxRow>,
        response: dev.tasky.api.SyncResponse,
        nowMs: Long = System.currentTimeMillis(),
    ): Applied = db.withTransaction {
        val touched = LinkedHashSet<String>()

        // 1. Rejected ops are dropped at once; the server state will correct the visible row.
        val rejected = response.rejected.mapNotNull { r -> batch.getOrNull(r.index)?.let { it to r } }
        if (rejected.isNotEmpty()) {
            outbox.delete(rejected.map { it.first.seq })
            rejected.forEach { touched += it.first.entityId }
        }

        // 2. Full resync: drop all synced data but keep the outbox.
        if (response.full_resync) {
            entities.clearServer()
            entities.clearVisible()
            outbox.all().forEach { touched += it.entityId }
        }

        // 3. Aliases: queued ops must stop mentioning the merged item.
        for (alias in response.aliases) remapOutbox(alias)

        // 4. Server state.
        for (change in response.changes) {
            storeServerState(change)
            touched += change.id
        }

        // 5. Last page: the batches sent during this sync are now reflected in server state.
        if (!response.has_more) {
            outbox.inFlight().forEach { touched += it.entityId }
            outbox.deleteInFlight()
        }

        // 6. Recompute visible rows.
        response.aliases.forEach { touched += it.alias_id }
        recompute(touched)
        for (alias in response.aliases) {
            entities.remapContributionItems(alias.alias_id, alias.target_id)
            entities.remapIngredientItems(alias.alias_id, alias.target_id)
        }

        entities.setState(SyncStateRow(KEY_CURSOR, response.cursor.toString()))
        if (!response.has_more) entities.setState(SyncStateRow(KEY_LAST_SYNC, nowMs.toString()))
        Applied(rejected)
    }

    private suspend fun storeServerState(change: WireEntity) {
        if (change.deleted) {
            entities.deleteServer(change.id)
        } else {
            entities.upsertServer(
                ServerEntityRow(
                    id = change.id,
                    kind = change.data.kind.string,
                    data = TaskyJson.encodeToString(EntityData.serializer(), change.data),
                ),
            )
        }
    }

    private suspend fun remapOutbox(alias: ItemAlias) {
        for (row in outbox.all()) {
            val op = decode(row) ?: continue
            val mapped = OpApplier.remapItem(op, alias.alias_id, alias.target_id)
            if (mapped !== op) {
                outbox.rewrite(row.seq, mapped.entity_id, TaskyJson.encodeToString(Op.serializer(), mapped))
            }
        }
    }

    /** visible(id) = server(id) + pending ops(id). */
    suspend fun recompute(ids: Collection<String>) {
        val user = me()
        for (id in ids) {
            val base = entities.serverEntity(id)?.let {
                TaskyJson.decodeFromString(EntityData.serializer(), it.data)
            }
            val ops = outbox.forEntity(id).mapNotNull(::decode)
            entities.putVisible(id, OpApplier.materialize(base, ops, user))
        }
    }

    /** Wipes everything, including the outbox (sign-out). */
    suspend fun wipeAll() = withContext(Dispatchers.IO) { db.clearAllTables() }

    private fun decode(row: OutboxRow): Op? =
        runCatching { TaskyJson.decodeFromString(Op.serializer(), row.op) }.getOrNull()

    companion object {
        const val KEY_CURSOR = "cursor"
        const val KEY_LAST_SYNC = "last_sync_ms"
    }
}
