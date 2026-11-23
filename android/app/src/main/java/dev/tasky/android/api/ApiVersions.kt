package dev.tasky.android.api

import dev.tasky.api.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * API versions (docs/API.md, "Versions"). The server constants live in
 * `server/crates/tasky-api` (`API_VERSION`, `MIN_CLIENT_API_VERSION`).
 */
object ApiVersions {
    /** The wire protocol this app speaks. Sent with every request. */
    const val APP = 2

    /** Oldest server protocol this app can work with. */
    const val MIN_SERVER = 2

    const val REQUEST_HEADER = "X-Tasky-Api-Version"
    const val SERVER_HEADER = "X-Tasky-Server-Api-Version"
    const val MIN_CLIENT_HEADER = "X-Tasky-Min-Client-Api-Version"

    /** Servers from before API version 2 don't state a minimum: they took any app. */
    fun evaluate(serverApi: Int, minClientApi: Int?): Compatibility = when {
        (minClientApi ?: 1) > APP -> Compatibility.AppOutdated
        serverApi < MIN_SERVER -> Compatibility.ServerOutdated
        else -> Compatibility.Ok
    }

    fun evaluate(info: ServerInfo): Compatibility = evaluate(info.api_version, info.min_client_api_version)
}

enum class Compatibility {
    /** Not checked yet in this process. */
    Unknown,
    Ok,

    /** The server needs a newer app: the user has to update Tasky. */
    AppOutdated,

    /** The server is older than this app supports: whoever runs it has to update it. */
    ServerOutdated,
}

/** Thrown instead of talking to a server this app can't work with. Local edits stay queued. */
class IncompatibleVersionException(val compatibility: Compatibility) :
    IOException("Incompatible server: $compatibility")

/**
 * Whether this app and its server can work together. Checked with `GET /server-info` before
 * the first other request, and kept up to date from the version headers on every response, so
 * a server update (or downgrade) while the app runs is noticed too.
 */
class VersionGate {
    private val _status = MutableStateFlow(Compatibility.Unknown)
    val status: StateFlow<Compatibility> = _status.asStateFlow()
    private val checking = Mutex()

    fun record(compatibility: Compatibility) {
        _status.value = compatibility
    }

    /** Signing out or switching servers: the next request checks again. */
    fun reset() = record(Compatibility.Unknown)

    /**
     * Makes sure the server is compatible before a request, asking [fetchInfo] unless the
     * last answer was fine. Throws [IncompatibleVersionException] otherwise.
     */
    suspend fun ensureCompatible(fetchInfo: suspend () -> ServerInfo) {
        if (_status.value == Compatibility.Ok) return
        checking.withLock {
            if (_status.value != Compatibility.Ok) record(ApiVersions.evaluate(fetchInfo()))
        }
        _status.value.let { if (it != Compatibility.Ok) throw IncompatibleVersionException(it) }
    }
}
