package dev.tasky.android.sync

import android.util.Log
import dev.tasky.android.api.SyncApi
import dev.tasky.api.RejectCode
import dev.tasky.api.RejectedOp
import dev.tasky.api.SyncRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Who we sync as. */
data class SyncTarget(val api: SyncApi, val householdId: String)

/**
 * Sends the outbox and pulls changes with `POST /households/{hid}/sync` until caught up.
 * Only one sync runs at a time.
 */
class SyncEngine(
    private val store: LocalStore,
    private val hlc: Hlc,
    private val target: () -> SyncTarget?,
    private val batchSize: Int = 1000,
    private val pageSize: Int = 500,
) {
    private val mutex = Mutex()
    private val _rejections = MutableSharedFlow<RejectedOp>(extraBufferCapacity = 16)

    /** Rejected ops worth telling the user about (`validation`, `invalid_reference`). */
    val rejections: SharedFlow<RejectedOp> = _rejections.asSharedFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Latest cursor announced by the live socket; a sync is due while it's ahead of ours. */
    @Volatile
    var knownServerCursor: Long = 0

    suspend fun cursor(): Long = store.cursor()

    /**
     * Runs one full sync. Throws [java.io.IOException] (including
     * [dev.tasky.android.api.ApiException]) when the server can't be reached or refuses.
     * Returns false if not signed in.
     */
    suspend fun sync(): Boolean = mutex.withLock {
        val t = target() ?: return false
        _syncing.value = true
        try {
            // Leftovers from an interrupted sync are simply sent again; ops are idempotent.
            store.releaseInFlight()
            var rounds = 0
            while (true) {
                val batch = store.takeBatch(batchSize)
                val request = SyncRequest(
                    since = store.cursor(),
                    ops = batch.map { TaskyJsonOps.decode(it.op) }.ifEmpty { null },
                    limit = pageSize,
                )
                val response = try {
                    t.api.sync(t.householdId, request)
                } catch (e: Exception) {
                    store.releaseInFlight()
                    throw e
                }
                hlc.observeServerTime(response.server_time_ms)
                // Signed out (or switched accounts) while the request was running: drop the answer.
                if (target()?.householdId != t.householdId) return@withLock false
                val applied = store.applySyncResponse(batch, response)
                for ((_, r) in applied.rejected) {
                    Log.w(TAG, "op rejected: ${r.code} ${r.entity_id}: ${r.message}")
                    if (r.code == RejectCode.Validation || r.code == RejectCode.InvalidReference) {
                        _rejections.tryEmit(r)
                    }
                }
                rounds++
                if (response.has_more) continue
                // Edits made while this request was in flight go out right away, within reason.
                if (store.hasUnsent() && rounds < MAX_ROUNDS) continue
                break
            }
            true
        } finally {
            _syncing.value = false
        }
    }

    private companion object {
        const val TAG = "TaskySync"
        const val MAX_ROUNDS = 20
    }
}

internal object TaskyJsonOps {
    fun decode(json: String): dev.tasky.api.Op =
        dev.tasky.android.api.TaskyJson.decodeFromString(dev.tasky.api.Op.serializer(), json)
}
