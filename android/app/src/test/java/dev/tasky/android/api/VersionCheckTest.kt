package dev.tasky.android.api

import dev.tasky.api.SyncRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The app checks the server's API version before talking to it, and notices when it changes. */
class VersionCheckTest {
    private val sent = mutableListOf<Request>()

    /** The server's answer: status, body, and its version headers (null = a server or proxy without them). */
    private var answer: (Request) -> Triple<Int, String, Pair<Int, Int>?> = { Triple(200, "{}", null) }

    private val http = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val req = chain.request()
            sent += req
            val (code, body, versions) = answer(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType()))
                .apply {
                    versions?.let { (api, min) ->
                        header(ApiVersions.SERVER_HEADER, api.toString())
                        header(ApiVersions.MIN_CLIENT_HEADER, min.toString())
                    }
                }
                .build()
        },
    ).build()

    private val gate = VersionGate()
    private val client = ApiClient(http, "https://tasky.example.com", "token", gate = gate)

    private fun info(api: Int, min: Int?) =
        """{"name":"tasky","version":"x","api_version":$api,${min?.let { "\"min_client_api_version\":$it," }.orEmpty()}"server_time_ms":1}"""

    private val syncOk = """{"changes":[],"aliases":[],"rejected":[],"cursor":0,"has_more":false,"full_resync":false,"server_time_ms":1}"""

    /** A server speaking [api], accepting apps from [min]. */
    private fun server(api: Int, min: Int) {
        answer = { req ->
            if (req.url.encodedPath.endsWith("/server-info")) Triple(200, info(api, min), api to min) else Triple(200, syncOk, api to min)
        }
    }

    private fun sync() = runBlocking { client.sync("h", SyncRequest(since = 0)) }

    private fun syncCalls() = sent.count { it.url.encodedPath.endsWith("/sync") }

    private fun assertRefused(expected: Compatibility) {
        try {
            sync()
            fail("expected $expected")
        } catch (e: IncompatibleVersionException) {
            assertEquals(expected, e.compatibility)
        }
        assertEquals(expected, gate.status.value)
    }

    @Test
    fun compatibleServerIsCheckedOnceAndGetsTheAppVersion() {
        server(ApiVersions.APP, ApiVersions.APP)
        sync()
        sync()
        assertEquals(Compatibility.Ok, gate.status.value)
        assertEquals(1, sent.count { it.url.encodedPath.endsWith("/server-info") })
        assertEquals(2, syncCalls())
        assertTrue(sent.all { it.header(ApiVersions.REQUEST_HEADER) == ApiVersions.APP.toString() })
    }

    @Test
    fun oldServerIsNeverSentOurEdits() {
        // A server from before version headers: API 1 and no minimum.
        answer = { Triple(200, info(1, null), null) }
        assertRefused(Compatibility.ServerOutdated)
        assertEquals(0, syncCalls())
    }

    @Test
    fun newerServerAsksForAnAppUpdate() {
        server(ApiVersions.APP + 1, ApiVersions.APP + 1)
        assertRefused(Compatibility.AppOutdated)
        assertEquals(0, syncCalls())
    }

    @Test
    fun serverUpdatedWhileRunningIsNoticed() {
        server(ApiVersions.APP, ApiVersions.APP)
        sync()
        // The server was updated and now refuses this app.
        answer = { Triple(426, """{"error":"client_outdated","message":"update"}""", (ApiVersions.APP + 1) to (ApiVersions.APP + 1)) }
        assertRefused(Compatibility.AppOutdated)
        // Once the server is compatible again (e.g. rolled back), syncing resumes by itself.
        server(ApiVersions.APP, ApiVersions.APP)
        sync()
        assertEquals(Compatibility.Ok, gate.status.value)
    }

    @Test
    fun errorPagesWithoutVersionHeadersDontChangeTheVerdict() {
        server(ApiVersions.APP, ApiVersions.APP)
        sync()
        answer = { Triple(502, "<html>Bad gateway</html>", null) }
        try {
            sync()
            fail()
        } catch (e: ApiException) {
            assertEquals(502, e.status)
        }
        assertEquals(Compatibility.Ok, gate.status.value)
    }

    @Test
    fun evaluatesVersions() {
        assertEquals(Compatibility.Ok, ApiVersions.evaluate(ApiVersions.APP, ApiVersions.APP))
        assertEquals(Compatibility.Ok, ApiVersions.evaluate(ApiVersions.APP + 5, ApiVersions.APP))
        assertEquals(Compatibility.AppOutdated, ApiVersions.evaluate(ApiVersions.APP + 1, ApiVersions.APP + 1))
        assertEquals(Compatibility.ServerOutdated, ApiVersions.evaluate(ApiVersions.MIN_SERVER - 1, null))
    }
}
