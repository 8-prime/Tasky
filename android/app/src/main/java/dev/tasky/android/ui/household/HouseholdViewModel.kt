package dev.tasky.android.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasky.android.AppContainer
import dev.tasky.android.data.Session
import dev.tasky.android.data.db.MemberRow
import dev.tasky.android.sync.LocalStore
import dev.tasky.api.InviteResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncInfo(
    val pendingOps: Int = 0,
    val lastSyncMs: Long? = null,
    val live: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
)

data class HouseholdState(
    val session: Session? = null,
    val members: List<MemberRow> = emptyList(),
    val sync: SyncInfo = SyncInfo(),
)

class HouseholdViewModel(private val c: AppContainer) : ViewModel() {
    private val syncInfo = combine(
        c.db.outbox().observeCount(),
        c.db.entities().observeState(LocalStore.KEY_LAST_SYNC),
        c.live.connected,
        c.syncEngine.syncing,
        c.coordinator.lastError,
    ) { pending, last, live, syncing, error -> SyncInfo(pending, last?.toLongOrNull(), live, syncing, error) }

    val state: StateFlow<HouseholdState> = combine(c.sessions.session, c.db.queries().members(), syncInfo) { s, m, sync ->
        HouseholdState(s, m, sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HouseholdState(c.sessions.current))

    private val _invite = MutableStateFlow<InviteResponse?>(null)
    val invite: StateFlow<InviteResponse?> = _invite.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch { runCatching { c.account.refreshHousehold() } }
    }

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = e
            } finally {
                _busy.value = false
            }
        }
    }

    fun createInvite() = run { _invite.value = c.account.createInvite() }
    fun dismissInvite() { _invite.value = null }
    fun dismissError() { _error.value = null }
    fun changeDisplayName(name: String) = run { c.account.updateDisplayName(name) }
    fun renameHousehold(name: String) = run { c.account.renameHousehold(name) }
    fun syncNow() = run {
        c.coordinator.syncNow().getOrThrow()
        c.account.refreshHousehold()
    }
    fun logout() = run { c.logout() }

    /** `tasky://join?server=<url>&code=<code>` */
    fun inviteLink(code: String): String {
        val server = c.sessions.current?.serverUrl.orEmpty()
        return "tasky://join?server=" + java.net.URLEncoder.encode(server, "UTF-8") + "&code=" + java.net.URLEncoder.encode(code, "UTF-8")
    }
}
