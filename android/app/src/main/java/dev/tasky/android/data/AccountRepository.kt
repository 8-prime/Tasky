package dev.tasky.android.data

import android.os.Build
import dev.tasky.android.api.ApiClient
import dev.tasky.android.data.db.MemberRow
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.api.AuthResponse
import dev.tasky.api.CreateHouseholdRequest
import dev.tasky.api.InviteResponse
import dev.tasky.api.JoinHouseholdRequest
import dev.tasky.api.LoginRequest
import dev.tasky.api.ServerInfo
import dev.tasky.api.UpdateHouseholdRequest
import dev.tasky.api.UpdateMeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/** Sign-in, household details and account settings (plain REST, not synced). */
class AccountRepository(
    private val http: OkHttpClient,
    private val sessions: SessionStore,
    private val db: TaskyDatabase,
    /** Builds the client for the current session (with 401 handling). */
    private val sessionApi: () -> ApiClient?,
    /** Called after a new session is stored. */
    private val onSignedIn: suspend () -> Unit,
) {
    private fun anonymous(serverUrl: String) = ApiClient(http, serverUrl)

    suspend fun checkServer(serverUrl: String): ServerInfo = anonymous(serverUrl).serverInfo()

    suspend fun createHousehold(
        serverUrl: String, setupToken: String, householdName: String,
        username: String, displayName: String, password: String,
    ) = signIn(serverUrl) {
        it.createHousehold(
            CreateHouseholdRequest(
                setup_token = setupToken.trim(), household_name = householdName.trim(), username = username.trim(),
                display_name = displayName.trim(), password = password, device_name = deviceName(),
            ),
        )
    }

    suspend fun join(serverUrl: String, inviteCode: String, username: String, displayName: String, password: String) =
        signIn(serverUrl) {
            it.join(
                JoinHouseholdRequest(
                    invite_code = inviteCode.trim(), username = username.trim(), display_name = displayName.trim(),
                    password = password, device_name = deviceName(),
                ),
            )
        }

    suspend fun login(serverUrl: String, username: String, password: String) = signIn(serverUrl) {
        it.login(LoginRequest(username = username.trim(), password = password, device_name = deviceName()))
    }

    private suspend fun signIn(serverUrl: String, call: suspend (ApiClient) -> AuthResponse) {
        val client = anonymous(serverUrl)
        val auth = call(client)
        val household = auth.households.firstOrNull() ?: throw IOException("This account has no household")
        // A different account may have used this device before: start from a clean store.
        withContext(Dispatchers.IO) { db.clearAllTables() }
        sessions.save(
            Session(
                serverUrl = client.serverUrl,
                token = auth.session_token,
                userId = auth.user.id,
                username = auth.user.username,
                displayName = auth.user.display_name,
                householdId = household.id,
                householdName = household.name,
            ),
        )
        runCatching { refreshHousehold() }
        onSignedIn()
    }

    /** Refetches the household name and members into the local cache. */
    suspend fun refreshHousehold() {
        val api = sessionApi() ?: return
        val s = sessions.current ?: return
        val details = api.household(s.householdId)
        val members = details.members.map {
            MemberRow(it.user_id, it.username, it.display_name, it.role.string, it.joined_at_ms)
        }
        db.queries().upsertMembers(members)
        db.queries().deleteMembersExcept(members.map { it.userId })
        sessions.update { cur ->
            val me = members.firstOrNull { it.userId == cur.userId }
            cur.copy(householdName = details.household.name, displayName = me?.displayName ?: cur.displayName)
        }
    }

    suspend fun createInvite(): InviteResponse {
        val api = sessionApi() ?: throw IOException("Not signed in")
        return api.createInvite(sessions.current!!.householdId)
    }

    suspend fun updateDisplayName(name: String) {
        val api = sessionApi() ?: throw IOException("Not signed in")
        val me = api.updateMe(UpdateMeRequest(name.trim()))
        sessions.update { it.copy(displayName = me.user.display_name) }
        runCatching { refreshHousehold() }
    }

    suspend fun renameHousehold(name: String) {
        val api = sessionApi() ?: throw IOException("Not signed in")
        val details = api.updateHousehold(sessions.current!!.householdId, UpdateHouseholdRequest(name.trim()))
        sessions.update { it.copy(householdName = details.household.name) }
    }

    /** Ends the session on the server (best effort). Local cleanup is up to the caller. */
    suspend fun logoutRemote() {
        runCatching { sessionApi()?.logout() }
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            .ifBlank { "Android" }.take(64)
}
