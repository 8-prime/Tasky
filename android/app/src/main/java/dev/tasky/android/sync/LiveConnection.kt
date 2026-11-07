package dev.tasky.android.sync

import android.util.Log
import dev.tasky.android.api.TaskyJson
import dev.tasky.android.data.Session
import dev.tasky.api.LiveEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Keeps `GET /households/{hid}/live` open while started (the app is in the foreground),
 * reconnecting with exponential backoff. Events only signal changes; [onEvent] decides what to do.
 */
class LiveConnection(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val session: () -> Session?,
    private val onEvent: (LiveEvent) -> Unit,
    private val onUnauthorized: () -> Unit,
) {
    private var job: Job? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoffMs = MIN_BACKOFF_MS
            while (isActive) {
                val s = session() ?: break
                when (connectOnce(s)) {
                    End.Unauthorized -> {
                        onUnauthorized()
                        break
                    }
                    End.AfterHello -> backoffMs = MIN_BACKOFF_MS
                    End.Failed -> Unit
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    private enum class End { AfterHello, Failed, Unauthorized }

    private suspend fun connectOnce(s: Session): End {
        val ended = CompletableDeferred<End>()
        var gotHello = false
        val request = Request.Builder()
            .url("${s.serverUrl}/api/v1/households/${s.householdId}/live")
            .header("Authorization", "Bearer ${s.token}")
            .build()
        val ws = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { TaskyJson.decodeFromString(LiveEvent.serializer(), text) }.getOrNull() ?: return
                    if (!gotHello) {
                        gotHello = true
                        _connected.value = true
                    }
                    onEvent(event)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ended.complete(if (gotHello) End.AfterHello else End.Failed)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.d(TAG, "live socket failed: ${t.message} (${response?.code})")
                    ended.complete(
                        when {
                            response?.code == 401 -> End.Unauthorized
                            gotHello -> End.AfterHello
                            else -> End.Failed
                        },
                    )
                }
            },
        )
        return try {
            ended.await()
        } finally {
            _connected.value = false
            ws.cancel()
        }
    }

    private companion object {
        const val TAG = "TaskyLive"
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
