package dev.tasky.android.sync

import java.security.SecureRandom

/** Persisted clock state: the last HLC issued and the estimated server clock offset. */
data class HlcState(val ms: Long, val counter: Int, val offsetMs: Long)

/**
 * Hybrid logical clock (docs/API.md "HLC"). Format `<12 hex ms>-<4 hex counter>-<node>`, so
 * string order equals time order. Wall time is corrected by the server offset learned on
 * every sync, which keeps ops inside the server's 5-minute skew window.
 */
class Hlc(
    private val node: String,
    initial: HlcState = HlcState(0, 0, 0),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val persist: (HlcState) -> Unit = {},
) {
    private var lastMs = initial.ms
    private var counter = initial.counter

    /** ≈ server time − local time. */
    @Volatile
    var offsetMs: Long = initial.offsetMs
        private set

    init {
        require(NODE.matches(node)) { "invalid HLC node id: $node" }
    }

    @Synchronized
    fun next(): String {
        val now = wallClock() + offsetMs
        if (now > lastMs || lastMs - now > MAX_AHEAD_MS) {
            // A clock that was far ahead (e.g. before the first sync fixed the offset) would get
            // every op rejected as clock_skew, so fall back to the corrected time.
            lastMs = now
            counter = 0
        } else if (counter >= 0xffff) {
            lastMs += 1
            counter = 0
        } else {
            counter += 1
        }
        persist(HlcState(lastMs, counter, offsetMs))
        return format(lastMs, counter, node)
    }

    /** Call with `server_time_ms` from each response, as soon as it arrives. */
    @Synchronized
    fun observeServerTime(serverTimeMs: Long) {
        offsetMs = serverTimeMs - wallClock()
        persist(HlcState(lastMs, counter, offsetMs))
    }

    companion object {
        private val NODE = Regex("[a-z0-9]{1,32}")
        /** The server rejects ops further ahead than this, so nothing issued beyond it can matter. */
        private const val MAX_AHEAD_MS = 5 * 60 * 1000L

        fun format(ms: Long, counter: Int, node: String): String =
            "%012x-%04x-%s".format(ms, counter, node)

        fun randomNodeId(): String {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
