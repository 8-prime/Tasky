package dev.tasky.android.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.tasky.android.sync.Hlc
import dev.tasky.android.sync.HlcState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The signed-in user. v1 has exactly one household per user. */
data class Session(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val householdId: String,
    val householdName: String,
)

/** Encrypts small secrets at rest. */
interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/** AES-GCM with a non-exportable key in the Android Keystore. */
class KeystoreSecretBox(private val alias: String = "tasky_session") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        return "$iv:$data"
    }

    override fun open(sealed: String): String? = runCatching {
        val (iv, data) = sealed.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }.getOrNull()
}

/**
 * Persists the session (token encrypted with [SecretBox]) plus install-wide sync settings:
 * the HLC node id, the last HLC issued and the server clock offset.
 */
class SessionStore(context: Context, private val box: SecretBox) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tasky_session", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()
    val current: Session? get() = _session.value

    private fun load(): Session? {
        val token = prefs.getString(K_TOKEN, null)?.let(box::open) ?: return null
        return Session(
            serverUrl = prefs.getString(K_SERVER, null) ?: return null,
            token = token,
            userId = prefs.getString(K_USER_ID, null) ?: return null,
            username = prefs.getString(K_USERNAME, null) ?: "",
            displayName = prefs.getString(K_DISPLAY_NAME, null) ?: "",
            householdId = prefs.getString(K_HOUSEHOLD_ID, null) ?: return null,
            householdName = prefs.getString(K_HOUSEHOLD_NAME, null) ?: "",
        )
    }

    @Synchronized
    fun save(s: Session) {
        prefs.edit(commit = true) {
            putString(K_SERVER, s.serverUrl)
            putString(K_LAST_SERVER, s.serverUrl)
            putString(K_TOKEN, box.seal(s.token))
            putString(K_USER_ID, s.userId)
            putString(K_USERNAME, s.username)
            putString(K_DISPLAY_NAME, s.displayName)
            putString(K_HOUSEHOLD_ID, s.householdId)
            putString(K_HOUSEHOLD_NAME, s.householdName)
        }
        _session.value = s
    }

    @Synchronized
    fun update(transform: (Session) -> Session) {
        val cur = _session.value ?: return
        save(transform(cur))
    }

    @Synchronized
    fun clear() {
        prefs.edit(commit = true) {
            listOf(K_SERVER, K_TOKEN, K_USER_ID, K_USERNAME, K_DISPLAY_NAME, K_HOUSEHOLD_ID, K_HOUSEHOLD_NAME)
                .forEach(::remove)
        }
        _session.value = null
    }

    /** Last server URL used, to prefill sign-in after a logout. */
    val lastServerUrl: String? get() = prefs.getString(K_LAST_SERVER, null)

    fun rememberServer(url: String) = prefs.edit { putString(K_LAST_SERVER, url) }

    /** Random per install, `[a-z0-9]{16}`, used as the HLC node. */
    val nodeId: String
        @Synchronized get() = prefs.getString(K_NODE, null) ?: Hlc.randomNodeId().also {
            prefs.edit(commit = true) { putString(K_NODE, it) }
        }

    fun loadHlcState(): HlcState = HlcState(
        ms = prefs.getLong(K_HLC_MS, 0),
        counter = prefs.getInt(K_HLC_COUNTER, 0),
        offsetMs = prefs.getLong(K_OFFSET, 0),
    )

    fun saveHlcState(state: HlcState) = prefs.edit {
        putLong(K_HLC_MS, state.ms)
        putInt(K_HLC_COUNTER, state.counter)
        putLong(K_OFFSET, state.offsetMs)
    }

    private companion object {
        const val K_SERVER = "server_url"
        const val K_LAST_SERVER = "last_server_url"
        const val K_TOKEN = "token"
        const val K_USER_ID = "user_id"
        const val K_USERNAME = "username"
        const val K_DISPLAY_NAME = "display_name"
        const val K_HOUSEHOLD_ID = "household_id"
        const val K_HOUSEHOLD_NAME = "household_name"
        const val K_NODE = "hlc_node"
        const val K_HLC_MS = "hlc_ms"
        const val K_HLC_COUNTER = "hlc_counter"
        const val K_OFFSET = "server_offset_ms"
    }
}
