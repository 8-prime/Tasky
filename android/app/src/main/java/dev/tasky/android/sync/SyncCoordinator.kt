package dev.tasky.android.sync

import android.util.Log
import dev.tasky.android.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Decides when to sync: on request (debounced), periodically while in the foreground,
 * and hands off to WorkManager when the network is unavailable.
 */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val engine: SyncEngine,
    private val scheduleBackground: () -> Unit,
) {
    private val requests = Channel<Long>(Channel.CONFLATED)
    private var ticker: Job? = null

    private val _lastError = MutableStateFlow<String?>(null)
    /** Message of the last failed sync, null after a successful one. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        scope.launch {
            for (debounce in requests) {
                if (debounce > 0) delay(debounce)
                requests.tryReceive() // fold requests made while waiting
                runOnce()
            }
        }
    }

    /** Asks for a sync soon. Local edits pass a short debounce so quick taps go out together. */
    fun request(debounceMs: Long = 0) {
        requests.trySend(debounceMs)
    }

    fun onLocalEdit() = request(EDIT_DEBOUNCE_MS)

    /** Runs a sync now and reports failure as an exception message. */
    suspend fun syncNow(): Result<Unit> = runCatching { engine.sync() }.map { }.also { r ->
        _lastError.value = r.exceptionOrNull()?.message
    }

    private suspend fun runOnce() {
        try {
            engine.sync()
            _lastError.value = null
        } catch (e: ApiException) {
            Log.w(TAG, "sync refused: ${e.status} ${e.message}")
            _lastError.value = e.message
            if (e.status >= 500) scheduleBackground()
        } catch (e: IOException) {
            Log.i(TAG, "sync failed, retrying in background: ${e.message}")
            _lastError.value = e.message
            scheduleBackground()
        }
    }

    fun startForeground() {
        request()
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(PERIODIC_MS)
                request()
            }
        }
    }

    fun stopForeground() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val TAG = "TaskySync"
        const val EDIT_DEBOUNCE_MS = 300L
        const val PERIODIC_MS = 2 * 60_000L
    }
}
