package dev.tasky.android.api

import dev.tasky.api.AuthResponse
import dev.tasky.api.CreateHouseholdRequest
import dev.tasky.api.ErrorBody
import dev.tasky.api.ErrorCode
import dev.tasky.api.HouseholdDetails
import dev.tasky.api.InviteResponse
import dev.tasky.api.JoinHouseholdRequest
import dev.tasky.api.LoginRequest
import dev.tasky.api.MeResponse
import dev.tasky.api.ServerInfo
import dev.tasky.api.SyncRequest
import dev.tasky.api.SyncResponse
import dev.tasky.api.UpdateHouseholdRequest
import dev.tasky.api.UpdateMeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A non-2xx answer from the server. Network problems are plain [IOException]s. */
class ApiException(
    val status: Int,
    val code: ErrorCode?,
    message: String,
) : IOException(message) {
    val isUnauthorized: Boolean get() = status == 401 && code != ErrorCode.InvalidCredentials
}

/** What the sync engine needs from the server; faked in tests. */
fun interface SyncApi {
    suspend fun sync(householdId: String, request: SyncRequest): SyncResponse
}

/**
 * REST client for one server. [token] is sent as a bearer token when present.
 * [onUnauthorized] runs when an authenticated request gets a 401 (session revoked).
 */
class ApiClient(
    private val http: OkHttpClient,
    baseUrl: String,
    private val token: String? = null,
    private val onUnauthorized: () -> Unit = {},
) : SyncApi {
    val serverUrl: String = ServerUrl.normalize(baseUrl)
    private val api = "$serverUrl/api/v1"

    suspend fun serverInfo(): ServerInfo = get("/server-info")
    suspend fun createHousehold(req: CreateHouseholdRequest): AuthResponse = post("/auth/create-household", req)
    suspend fun join(req: JoinHouseholdRequest): AuthResponse = post("/auth/join", req)
    suspend fun login(req: LoginRequest): AuthResponse = post("/auth/login", req)
    suspend fun logout() { send<Unit>("POST", "/auth/logout", "{}", null) }
    suspend fun me(): MeResponse = get("/me")
    suspend fun updateMe(req: UpdateMeRequest): MeResponse = send("PATCH", "/me", req)
    suspend fun household(id: String): HouseholdDetails = get("/households/$id")
    suspend fun updateHousehold(id: String, req: UpdateHouseholdRequest): HouseholdDetails =
        send("PATCH", "/households/$id", req)
    suspend fun createInvite(householdId: String): InviteResponse =
        send("POST", "/households/$householdId/invites", "{}", serializer<InviteResponse>())

    override suspend fun sync(householdId: String, request: SyncRequest): SyncResponse =
        post("/households/$householdId/sync", request)

    private suspend inline fun <reified R> get(path: String): R = send("GET", path, null, serializer<R>())

    private suspend inline fun <reified B, reified R> post(path: String, body: B): R =
        send("POST", path, body)

    private suspend inline fun <reified B, reified R> send(method: String, path: String, body: B): R =
        send(method, path, TaskyJson.encodeToString(serializer<B>(), body), serializer<R>())

    /** Runs on the IO dispatcher: the response body is read (blocking) here. */
    private suspend fun <R> send(method: String, path: String, json: String?, out: KSerializer<R>?): R =
        withContext(Dispatchers.IO) { execute(method, path, json, out) }

    private suspend fun <R> execute(method: String, path: String, json: String?, out: KSerializer<R>?): R {
        val builder = Request.Builder().url(api + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(method, json?.toRequestBody(JSON))
        http.newCall(builder.build()).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching { TaskyJson.decodeFromString<ErrorBody>(text) }.getOrNull()
                val ex = ApiException(response.code, error?.error, error?.message ?: "HTTP ${response.code}")
                if (token != null && ex.isUnauthorized) onUnauthorized()
                throw ex
            }
            @Suppress("UNCHECKED_CAST")
            if (out == null) return Unit as R
            return try {
                TaskyJson.decodeFromString(out, text)
            } catch (e: IllegalArgumentException) {
                throw IOException("Unexpected response from server", e)
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

/** Runs the call asynchronously and cancels it with the coroutine. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
