package dev.tasky.android

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.tasky.android.api.ApiClient
import dev.tasky.android.data.AccountRepository
import dev.tasky.android.data.KeystoreSecretBox
import dev.tasky.android.data.SessionStore
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.android.sync.Hlc
import dev.tasky.android.sync.LiveConnection
import dev.tasky.android.sync.LocalStore
import dev.tasky.android.sync.SyncCoordinator
import dev.tasky.android.sync.SyncEngine
import dev.tasky.android.sync.SyncTarget
import dev.tasky.android.sync.SyncWorker
import dev.tasky.api.LiveEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency wiring; one per process. */
class AppContainer(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val sessions = SessionStore(context, KeystoreSecretBox())
    val db: TaskyDatabase = TaskyDatabase.open(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)
        .build()

    val hlc = Hlc(sessions.nodeId, sessions.loadHlcState(), persist = sessions::saveHlcState)

    fun sessionApi(): ApiClient? = sessions.current?.let { s ->
        ApiClient(http, s.serverUrl, s.token, onUnauthorized = ::onUnauthorized)
    }

    val store = LocalStore(db) { sessions.current?.userId }

    val syncEngine = SyncEngine(
        store = store,
        hlc = hlc,
        target = { sessionApi()?.let { api -> SyncTarget(api, sessions.current!!.householdId) } },
    )

    val coordinator = SyncCoordinator(scope, syncEngine) { SyncWorker.enqueue(context) }

    val repository = TaskyRepository(store, hlc) { coordinator.onLocalEdit() }

    val account: AccountRepository = AccountRepository(http, sessions, db, ::sessionApi) {
        coordinator.request()
        if (foreground) live.start()
    }

    val live: LiveConnection = LiveConnection(
        http = http,
        scope = scope,
        session = { sessions.current },
        onEvent = { event ->
            when (event.kind) {
                LiveEventKind.Hello, LiveEventKind.Changed -> scope.launch {
                    if (event.cursor > syncEngine.cursor()) coordinator.request()
                }
                LiveEventKind.HouseholdChanged -> scope.launch { runCatching { account.refreshHousehold() } }
            }
            if (event.kind == LiveEventKind.Hello) scope.launch { runCatching { account.refreshHousehold() } }
        },
        onUnauthorized = ::onUnauthorized,
    )

    @Volatile
    private var foreground = false

    /** Foreground: live socket + periodic sync. Background: WorkManager flushes leftovers. */
    fun observeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                if (sessions.current != null) {
                    live.start()
                    coordinator.startForeground()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                live.stop()
                coordinator.stopForeground()
                scope.launch { if (db.outbox().count() > 0) SyncWorker.enqueue(context) }
            }
        })
    }

    private fun onUnauthorized() {
        scope.launch { signOutLocally() }
    }

    /** Logs out on the server (best effort) and wipes local data. */
    suspend fun logout() {
        account.logoutRemote()
        signOutLocally()
    }

    private suspend fun signOutLocally() {
        if (sessions.current == null) return
        live.stop()
        coordinator.stopForeground()
        SyncWorker.cancel(context)
        sessions.clear()
        store.wipeAll()
    }
}
