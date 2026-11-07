package dev.tasky.android.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tasky.android.R
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.domain.ListLine
import dev.tasky.android.domain.Units
import dev.tasky.android.sync.normalizeName
import dev.tasky.android.ui.components.AmountField
import dev.tasky.android.ui.components.ConfirmDialog
import dev.tasky.android.ui.components.ItemNameField
import dev.tasky.android.ui.components.TextInputDialog
import dev.tasky.android.ui.components.TextLine
import dev.tasky.android.ui.components.UnitField

private sealed interface ListDialog {
    data object NewList : ListDialog
    data object Rename : ListDialog
    data object Delete : ListDialog
    data class Line(val line: ListLine) : ListDialog
    data class EditItem(val item: ItemRow) : ListDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(vm: ShoppingListViewModel) {
    val s by vm.state.collectAsState()
    var dialog by remember { mutableStateOf<ListDialog?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        TextButton(onClick = { listMenu = true }) {
                            Text(s.list?.name ?: stringResource(R.string.list_title), style = MaterialTheme.typography.titleLarge)
                            Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.list_switch))
                        }
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            s.lists.forEach { l ->
                                DropdownMenuItem(text = { Text(l.name) }, onClick = { vm.select(l.id); listMenu = false })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.list_new)) },
                                leadingIcon = { Icon(Icons.Filled.Add, null) },
                                onClick = { dialog = ListDialog.NewList; listMenu = false },
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.list_clear_checked)) },
                            enabled = s.checked.isNotEmpty(),
                            onClick = { vm.clearChecked(); overflow = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.list_rename)) },
                            enabled = s.list != null,
                            onClick = { dialog = ListDialog.Rename; overflow = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.list_delete)) },
                            enabled = s.list != null && s.lists.size > 1,
                            onClick = { dialog = ListDialog.Delete; overflow = false },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (s.list != null) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.list_add_item)) },
                )
            }
        },
    ) { padding ->
        if (!s.loading && s.groups.isEmpty() && s.checked.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (s.list == null) R.string.list_none_yet else R.string.list_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
                for (group in s.groups) {
                    item(key = "h-${group.category}") {
                        SectionHeader(group.category ?: stringResource(R.string.list_uncategorized))
                    }
                    items(group.lines, key = { it.itemId }) { line ->
                        LineRow(line, s.memberNames, onToggle = { vm.toggle(line) }, onLongPress = { dialog = ListDialog.Line(line) })
                    }
                }
                if (s.checked.isNotEmpty()) {
                    item(key = "checked-header") {
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader(pluralStringResource(R.plurals.list_checked_count, s.checked.size, s.checked.size), Modifier.weight(1f))
                            TextButton(onClick = vm::clearChecked) { Text(stringResource(R.string.list_clear_checked)) }
                        }
                    }
                    items(s.checked, key = { "c-" + it.itemId }) { line ->
                        LineRow(line, s.memberNames, onToggle = { vm.toggle(line) }, onLongPress = { dialog = ListDialog.Line(line) })
                    }
                }
            }
        }
    }

    if (adding) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { adding = false }, sheetState = sheet) {
            AddItemForm(items = s.items, onAdd = vm::add)
        }
    }

    when (val d = dialog) {
        null -> Unit
        ListDialog.NewList -> TextInputDialog(
            title = stringResource(R.string.list_new), label = R.string.field_name, confirm = R.string.action_add,
            onDismiss = { dialog = null },
        ) { vm.createList(it); dialog = null }
        ListDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.list_rename), label = R.string.field_name, initial = s.list?.name.orEmpty(),
            onDismiss = { dialog = null },
        ) { vm.renameList(it); dialog = null }
        ListDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.list_delete_title, s.list?.name.orEmpty()),
            text = stringResource(R.string.list_delete_text),
            confirm = R.string.action_delete,
            onDismiss = { dialog = null },
        ) { vm.deleteList(); dialog = null }
        is ListDialog.Line -> LineActionsDialog(
            line = d.line,
            onDismiss = { dialog = null },
            onEditItem = { dialog = s.items.firstOrNull { it.id == d.line.itemId }?.let { ListDialog.EditItem(it) } },
            onRemove = { vm.removeLine(d.line); dialog = null },
        )
        is ListDialog.EditItem -> EditItemDialog(d.item, onDismiss = { dialog = null }) { name, category, unit ->
            vm.updateItem(d.item.id, name, category, unit)
            dialog = null
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineRow(line: ListLine, members: Map<String, String>, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val amounts = Units.format(line.open)
    val support = buildList {
        if (line.checked) {
            val names = line.checkedBy.map { members[it] ?: stringResource(R.string.someone) }
            if (names.isNotEmpty()) add(stringResource(R.string.list_checked_by, names.joinToString(", ")))
        } else {
            addAll(line.notes)
        }
    }.joinToString(" · ")
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onToggle, onLongClick = onLongPress),
        leadingContent = { Checkbox(checked = line.checked, onCheckedChange = { onToggle() }) },
        headlineContent = {
            Text(
                line.name,
                textDecoration = if (line.checked) TextDecoration.LineThrough else null,
                color = if (line.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (support.isNotEmpty()) ({ Text(support, maxLines = 2, overflow = TextOverflow.Ellipsis) }) else null,
        trailingContent = if (amounts.isNotEmpty()) ({ Text(amounts, style = MaterialTheme.typography.bodyMedium) }) else null,
    )
}

/** Name (with catalog suggestions), amount, unit, category and note. Stays open for the next item. */
@Composable
private fun AddItemForm(items: List<ItemRow>, onAdd: (String, Double?, String?, String?, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var added by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val existing = items.firstOrNull { it.normName == normalizeName(name) }
    val amountError = amount.isNotBlank() && Units.parseAmount(amount) == null

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.list_add_item), style = MaterialTheme.typography.titleLarge)
        ItemNameField(
            value = name,
            onValueChange = { name = it; added = null },
            items = items,
            onPick = { item ->
                name = item.name
                if (unit.isBlank()) unit = item.defaultUnit.orEmpty()
            },
            focusRequester = focus,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(amount, { amount = it }, Modifier.weight(1f), isError = amountError)
            UnitField(unit, { unit = it }, Modifier.weight(1f))
        }
        if (existing == null) {
            TextLine(category, { category = it }, R.string.field_category, supporting = stringResource(R.string.list_category_help))
        } else if (!existing.category.isNullOrBlank()) {
            Text(stringResource(R.string.list_in_category, existing.category), style = MaterialTheme.typography.bodySmall)
        }
        TextLine(note, { note = it }, R.string.field_note)
        added?.let { Text(stringResource(R.string.list_added, it), color = MaterialTheme.colorScheme.primary) }
        Button(
            enabled = name.isNotBlank() && !amountError,
            onClick = {
                onAdd(name.trim(), Units.parseAmount(amount), unit.ifBlank { null }, note.ifBlank { null }, category.ifBlank { null })
                added = name.trim()
                name = ""; amount = ""; unit = ""; category = ""; note = ""
                runCatching { focus.requestFocus() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_add)) }
    }
}

@Composable
private fun LineActionsDialog(line: ListLine, onDismiss: () -> Unit, onEditItem: () -> Unit, onRemove: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.name) },
        text = {
            Column {
                line.contributions.forEach { c ->
                    val amount = Units.formatAmount(c.amount, c.unit)
                    val text = listOfNotNull(amount.ifBlank { null }, c.note).joinToString(" · ")
                    if (text.isNotEmpty()) {
                        Text(
                            text,
                            textDecoration = if (c.purchased) TextDecoration.LineThrough else null,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.list_remove_line)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onEditItem) { Text(stringResource(R.string.list_edit_item)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
private fun EditItemDialog(item: ItemRow, onDismiss: () -> Unit, onSave: (String, String?, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(item.name) }
    var category by rememberSaveable { mutableStateOf(item.category.orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(item.defaultUnit.orEmpty()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_edit_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextLine(name, { name = it }, R.string.field_name, isError = name.isBlank())
                TextLine(category, { category = it }, R.string.field_category)
                UnitField(unit, { unit = it })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, category.ifBlank { null }, unit.ifBlank { null }) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
