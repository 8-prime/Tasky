package dev.tasky.android.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tasky.android.R
import dev.tasky.android.data.db.ListEntryRow
import dev.tasky.android.data.db.ListRow
import dev.tasky.api.ListKind
import kotlinx.coroutines.delay

fun ListRow.icon(): ImageVector = when {
    isChecklist -> Icons.Filled.Checklist
    isNote -> Icons.AutoMirrored.Filled.Notes
    else -> Icons.Filled.ShoppingBasket
}

private data class KindOption(val kind: ListKind, val label: Int, val help: Int, val icon: ImageVector)

private val kindOptions = listOf(
    KindOption(ListKind.Grocery, R.string.list_kind_grocery, R.string.list_kind_grocery_help, Icons.Filled.ShoppingBasket),
    KindOption(ListKind.Checklist, R.string.list_kind_checklist, R.string.list_kind_checklist_help, Icons.Filled.Checklist),
    KindOption(ListKind.Note, R.string.list_kind_note, R.string.list_kind_note_help, Icons.AutoMirrored.Filled.Notes),
)

/** Name plus the list type (grocery list, checklist or note). */
@Composable
fun NewListDialog(onDismiss: () -> Unit, onCreate: (String, ListKind) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ListKind.Checklist) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(stringResource(R.string.list_kind_title), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    kindOptions.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = kind == option.kind, role = Role.RadioButton) { kind = option.kind }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = kind == option.kind, onClick = null)
                            Icon(option.icon, null, Modifier.padding(horizontal = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(option.label), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(option.help),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalContentColor.current.copy(alpha = 0.72f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), kind) }) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A checklist: an input row to add entries, then unchecked entries, then checked ones. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChecklistContent(
    entries: List<ListEntryRow>,
    members: Map<String, String>,
    padding: PaddingValues,
    onAdd: (String) -> Unit,
    onToggle: (ListEntryRow) -> Unit,
    onLongPress: (ListEntryRow) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp),
    ) {
        item(key = "add") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.checklist_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                // Keep the keyboard open so several entries can be added in a row.
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = submit, enabled = text.isNotBlank()) {
                        Icon(Icons.Filled.Add, stringResource(R.string.checklist_add))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.checklist_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
        items(entries, key = { it.id }) { entry ->
            val by = entry.checkedBy?.let { members[it] ?: stringResource(R.string.someone) }
            ListItem(
                modifier = Modifier.combinedClickable(onClick = { onToggle(entry) }, onLongClick = { onLongPress(entry) }),
                leadingContent = { Checkbox(checked = entry.checked, onCheckedChange = { onToggle(entry) }) },
                headlineContent = {
                    Text(
                        entry.text,
                        textDecoration = if (entry.checked) TextDecoration.LineThrough else null,
                        color = if (entry.checked) LocalContentColor.current.copy(alpha = 0.6f) else LocalContentColor.current,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = if (entry.checked && by != null) {
                    { Text(stringResource(R.string.list_checked_by, by)) }
                } else {
                    null
                },
            )
        }
    }
}

/** Edit or delete a checklist entry. */
@Composable
fun EntryActionsDialog(entry: ListEntryRow, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var text by rememberSaveable(entry.id) { mutableStateOf(entry.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checklist_edit_entry)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.field_entry)) },
                isError = text.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.checklist_delete_entry)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Delay between the last keystroke and saving a note. */
private const val NOTE_SAVE_DEBOUNCE_MS = 800L

/**
 * A note's text, saved while typing (debounced) and when leaving the screen.
 *
 * Remote edits replace the text only while there are no unsaved local edits, so typing is never
 * overwritten mid-sentence. If two people edit at once, the later save wins (server LWW).
 */
@Composable
fun NoteContent(list: ListRow, padding: PaddingValues, onSave: (String) -> Unit) {
    var text by rememberSaveable(list.id) { mutableStateOf(list.body.orEmpty()) }
    var saved by rememberSaveable(list.id) { mutableStateOf(list.body.orEmpty()) }

    LaunchedEffect(list.id, list.body) {
        val remote = list.body.orEmpty()
        if (text == saved) text = remote
        saved = remote
    }
    LaunchedEffect(list.id, text) {
        if (text != saved) {
            delay(NOTE_SAVE_DEBOUNCE_MS)
            onSave(text)
            saved = text
        }
    }
    val latestText by rememberUpdatedState(text)
    val latestSaved by rememberUpdatedState(saved)
    val latestOnSave by rememberUpdatedState(onSave)
    DisposableEffect(list.id) {
        onDispose { if (latestText != latestSaved) latestOnSave(latestText) }
    }

    TextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.note_placeholder)) },
        label = null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding())
            .imePadding()
            .padding(horizontal = 4.dp),
    )
}
