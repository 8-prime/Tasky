package dev.tasky.android.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasky.android.R
import dev.tasky.android.api.ServerUrl
import dev.tasky.android.data.AccountRepository
import dev.tasky.api.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { Server, Choose, Create, Join, Login }

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Server,
    val serverInput: String = "",
    /** The server field is hidden behind "Change server" while a default/known server is set. */
    val editServer: Boolean = false,
    val serverUrl: String? = null,
    val serverInfo: ServerInfo? = null,
    val setupToken: String = "",
    val householdName: String = "",
    val inviteCode: String = "",
    val displayName: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    /** Error to show; [errorRes] for local problems, [error] for server/network exceptions. */
    val error: Throwable? = null,
    val errorRes: Int? = null,
    /** Fields with validation problems (string resource per field). */
    val fieldErrors: Map<Field, Int> = emptyMap(),
)

enum class Field { Server, SetupToken, HouseholdName, InviteCode, DisplayName, Username, Password }

/** Pure checks mirroring the server's rules (docs/API.md "Onboarding"). */
object Rules {
    private val USERNAME = Regex("[A-Za-z0-9._-]{3,32}")
    fun username(s: String) = USERNAME.matches(s.trim())
    fun password(s: String) = s.length >= 8
    fun displayName(s: String) = s.trim().length in 1..64
}

class OnboardingViewModel(
    private val account: AccountRepository,
    lastServer: String?,
    /** BuildConfig.DEFAULT_SERVER_URL */
    defaultServer: String,
) : ViewModel() {
    private val initialServer = lastServer?.takeIf { it.isNotBlank() } ?: defaultServer
    private val _state = MutableStateFlow(OnboardingState(serverInput = initialServer, editServer = initialServer.isBlank()))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun edit(transform: OnboardingState.() -> OnboardingState) =
        _state.update { it.transform().copy(error = null, errorRes = null, fieldErrors = emptyMap()) }

    fun goTo(step: OnboardingStep) = edit { copy(step = step, password = "") }

    /** Shows the server address field (for a self-hosted or local development server). */
    fun changeServer() = edit { copy(step = OnboardingStep.Server, editServer = true, password = "") }

    /** From an invite link: tasky://join?server=…&code=… */
    fun openInvite(server: String, code: String) {
        _state.update { OnboardingState(serverInput = server, inviteCode = code, editServer = false) }
        checkServer(then = OnboardingStep.Join)
    }

    fun checkServer(then: OnboardingStep = OnboardingStep.Choose) {
        val url = ServerUrl.parse(_state.value.serverInput)
        if (url == null) {
            _state.update { it.copy(editServer = true, fieldErrors = mapOf(Field.Server to R.string.error_invalid_url)) }
            return
        }
        run {
            val info = account.checkServer(url)
            when {
                info.name != "tasky" -> _state.update { it.copy(editServer = true, errorRes = R.string.error_not_tasky) }
                info.api_version != 1 -> _state.update { it.copy(editServer = true, error = UnsupportedApi(info.api_version)) }
                else -> _state.update { it.copy(serverUrl = url, serverInfo = info, step = then) }
            }
        }
    }

    class UnsupportedApi(val version: Int) : Exception()

    fun submit() {
        val s = _state.value
        val url = s.serverUrl ?: return goTo(OnboardingStep.Server)
        val errors = validate(s)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        run {
            when (s.step) {
                OnboardingStep.Create -> account.createHousehold(url, s.setupToken, s.householdName, s.username, s.displayName, s.password)
                OnboardingStep.Join -> account.join(url, s.inviteCode, s.username, s.displayName, s.password)
                OnboardingStep.Login -> account.login(url, s.username, s.password)
                else -> Unit
            }
            // On success the session flow switches the app to the main screens.
        }
    }

    private fun validate(s: OnboardingState): Map<Field, Int> = buildMap {
        if (s.step == OnboardingStep.Login) {
            if (s.username.isBlank()) put(Field.Username, R.string.error_required)
            if (s.password.isEmpty()) put(Field.Password, R.string.error_required)
            return@buildMap
        }
        if (s.step == OnboardingStep.Create) {
            if (s.setupToken.isBlank()) put(Field.SetupToken, R.string.error_required)
            if (s.householdName.isBlank()) put(Field.HouseholdName, R.string.error_required)
        }
        if (s.step == OnboardingStep.Join && s.inviteCode.isBlank()) put(Field.InviteCode, R.string.error_required)
        if (!Rules.displayName(s.displayName)) put(Field.DisplayName, R.string.error_display_name_rules)
        if (!Rules.username(s.username)) put(Field.Username, R.string.error_username_rules)
        if (!Rules.password(s.password)) put(Field.Password, R.string.error_password_rules)
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, errorRes = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(busy = false, error = e, editServer = it.editServer || it.step == OnboardingStep.Server) }
            }
        }
    }
}
