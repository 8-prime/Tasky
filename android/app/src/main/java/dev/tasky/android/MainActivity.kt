package dev.tasky.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import dev.tasky.android.ui.TaskyRoot
import dev.tasky.android.ui.theme.TaskyTheme

/** An invite link: tasky://join?server=<url>&code=<code> */
data class InviteLink(val server: String, val code: String)

class MainActivity : ComponentActivity() {
    private val invite = MutableStateFlow<InviteLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        val container = (application as TaskyApp).container
        setContent {
            TaskyTheme {
                TaskyRoot(container, invite)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        parseInvite(intent?.data)?.let { invite.value = it }
    }

    companion object {
        fun parseInvite(uri: Uri?): InviteLink? {
            if (uri == null || uri.scheme != "tasky" || uri.host != "join") return null
            val server = uri.getQueryParameter("server") ?: return null
            val code = uri.getQueryParameter("code") ?: return null
            return InviteLink(server, code)
        }
    }
}
