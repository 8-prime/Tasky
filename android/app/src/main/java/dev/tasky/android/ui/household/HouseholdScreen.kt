package dev.tasky.android.ui.household

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tasky.android.R
import dev.tasky.android.ui.components.ConfirmDialog
import dev.tasky.android.ui.components.TextInputDialog
import dev.tasky.android.ui.errorMessage
import dev.tasky.api.InviteResponse

private enum class HouseholdDialog { DisplayName, HouseholdName, Logout }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(vm: HouseholdViewModel) {
    val s by vm.state.collectAsState()
    val invite by vm.invite.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    var dialog by remember { mutableStateOf<HouseholdDialog?>(null) }
    val context = LocalContext.current
    val session = s.session ?: return

    Scaffold(topBar = { TopAppBar(title = { Text(session.householdName) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            ListItem(
                headlineContent = { Text(session.householdName) },
                supportingContent = { Text(stringResource(R.string.household_name)) },
                trailingContent = {
                    IconButton(onClick = { dialog = HouseholdDialog.HouseholdName }) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.household_rename))
                    }
                },
            )
            HorizontalDivider()
            Text(
                pluralStringResource(R.plurals.household_members, s.members.size, s.members.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            s.members.forEach { m ->
                val me = m.userId == session.userId
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Person, null) },
                    headlineContent = { Text(if (me) stringResource(R.string.household_you, m.displayName) else m.displayName) },
                    supportingContent = {
                        Text(if (m.role == "owner") stringResource(R.string.household_owner, m.username) else "@" + m.username)
                    },
                    trailingContent = if (me) ({
                        IconButton(onClick = { dialog = HouseholdDialog.DisplayName }) {
                            Icon(Icons.Filled.Edit, stringResource(R.string.household_change_display_name))
                        }
                    }) else null,
                )
            }
            Button(
                onClick = vm::createInvite,
                enabled = !busy,
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PersonAdd, null)
                Text(stringResource(R.string.household_invite), Modifier.padding(start = 8.dp))
            }
            error?.let {
                Text(LocalResources.current.errorMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                stringResource(R.string.household_sync),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            val sync = s.sync
            val last = sync.lastSyncMs?.let {
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            } ?: stringResource(R.string.household_never)
            ListItem(
                headlineContent = { Text(session.serverUrl) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.household_signed_in_as, session.username))
                        Text(stringResource(R.string.household_last_sync, last))
                        Text(stringResource(if (sync.live) R.string.household_live_on else R.string.household_live_off))
                        if (sync.pendingOps > 0) {
                            Text(pluralStringResource(R.plurals.household_pending, sync.pendingOps, sync.pendingOps))
                        }
                        sync.error?.let { Text(stringResource(R.string.household_sync_error, it), color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::syncNow, enabled = !busy && !sync.syncing) { Text(stringResource(R.string.household_sync_now)) }
                OutlinedButton(onClick = { dialog = HouseholdDialog.Logout }, enabled = !busy) { Text(stringResource(R.string.household_logout)) }
            }
        }
    }

    invite?.let { InviteDialog(it, vm.inviteLink(it.code), session.serverUrl, onDismiss = vm::dismissInvite) }

    when (dialog) {
        null -> Unit
        HouseholdDialog.DisplayName -> TextInputDialog(
            title = stringResource(R.string.household_change_display_name), label = R.string.onboarding_display_name,
            initial = session.displayName, onDismiss = { dialog = null },
        ) { vm.changeDisplayName(it); dialog = null }
        HouseholdDialog.HouseholdName -> TextInputDialog(
            title = stringResource(R.string.household_rename), label = R.string.household_name,
            initial = session.householdName, onDismiss = { dialog = null },
        ) { vm.renameHousehold(it); dialog = null }
        HouseholdDialog.Logout -> ConfirmDialog(
            title = stringResource(R.string.household_logout_title),
            text = if (s.sync.pendingOps > 0) {
                pluralStringResource(R.plurals.household_logout_pending, s.sync.pendingOps, s.sync.pendingOps)
            } else {
                stringResource(R.string.household_logout_text)
            },
            confirm = R.string.household_logout,
            onDismiss = { dialog = null },
        ) { vm.logout(); dialog = null }
    }
}

@Composable
private fun InviteDialog(invite: InviteResponse, link: String, server: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val expires = DateUtils.formatDateTime(
        context, invite.expires_at_ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY,
    )
    val shareText = stringResource(R.string.household_invite_share_text, server, invite.code, link)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.household_invite_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QrCode(link, stringResource(R.string.household_invite_qr))
                SelectionContainer {
                    Text(invite.code, style = MaterialTheme.typography.headlineMedium)
                }
                Text(stringResource(R.string.household_invite_help, server), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.household_invite_expires, expires), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        dismissButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                context.startActivity(Intent.createChooser(send, null))
            }) { Text(stringResource(R.string.household_invite_share)) }
        },
    )
}
