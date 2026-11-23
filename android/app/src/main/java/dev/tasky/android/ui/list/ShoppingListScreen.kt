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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.RadioButton
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
import dev.tasky.android.data.db.ListEntryRow
import dev.tasky.android.data.db.StoreRow
import dev.tasky.android.domain.CategoryIndex
import dev.tasky.android.domain.ListLine
import dev.tasky.android.domain.Units
import dev.tasky.android.sync.normalizeName
import dev.tasky.android.ui.components.AmountField
import dev.tasky.android.ui.components.ConfirmDialog
import dev.tasky.android.ui.components.ItemNameField
import dev.tasky.android.ui.components.Picker
import dev.tasky.android.ui.components.TextInputDialog
import dev.tasky.android.ui.components.TextLine
import dev.tasky.android.ui.components.UnitField

private sealed interface ListDialog {
    data object NewList : ListDialog
    data object Rename : ListDialog
    data object Delete : ListDialog
    data class Line(val line: ListLine) : ListDialog
    data class EditItem(val item: ItemRow) : ListDialog
    data class Category(val itemId: String, val itemName: String) : ListDialog
    data class Entry(val entry: ListEntryRow) : ListDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(vm: ShoppingListViewModel, onManageStores: () -> Unit = {}) {
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
                                DropdownMenuItem(
                                    text = { Text(l.name) },
                                    leadingIcon = { Icon(l.icon(), null) },
                                    onClick = { vm.select(l.id); listMenu = false },
                                )
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
                        if (s.list?.isNote != true) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.list_clear_checked)) },
                                enabled = s.hasChecked,
                                onClick = { vm.clearChecked(); overflow = false },
                            )
                        }
                        if (s.list?.isGrocery == true) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.store_manage)) },
                                onClick = { onManageStores(); overflow = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.list_rename)) },
                            enabled = s.list != null,
                            onClick = { dialog = ListDialog.Rename; overflow = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.list_delete)) },
                            // Keep at least one grocery list: the planner adds meals to it.
                            enabled = s.list != null && s.lists.size > 1 &&
                                !(s.list?.isGrocery == true && s.lists.count { it.isGrocery } == 1),
                            onClick = { dialog = ListDialog.Delete; overflow = false },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (s.list?.isGrocery == true) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.list_add_item)) },
                )
            }
        },
    ) { padding ->
        val current = s.list
        if (current?.isNote == true) {
            NoteContent(current, padding) { vm.saveNote(current.id, it) }
        } else if (current?.isChecklist == true) {
            ChecklistContent(
                entries = s.entries,
                members = s.memberNames,
                padding = padding,
                onAdd = vm::addEntry,
                onToggle = vm::toggleEntry,
                onLongPress = { dialog = ListDialog.Entry(it) },
            )
        } else if (!s.loading && s.groups.isEmpty() && s.checked.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (s.list == null) R.string.list_none_yet else R.string.list_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
                item(key = "store") {
                    StoreBar(s.store, s.stores, onSelect = vm::selectStore, onManage = onManageStores)
                }
                for (group in s.groups) {
                    item(key = "h-${group.category?.id}") {
                        SectionHeader(group.category?.name ?: stringResource(R.string.list_uncategorized))
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
            AddItemForm(items = s.items, categories = s.categories, onAdd = vm::add, onCreateCategory = vm::createCategory)
        }
    }

    when (val d = dialog) {
        null -> Unit
        ListDialog.NewList -> NewListDialog(onDismiss = { dialog = null }) { name, kind ->
            vm.createList(name, kind)
            dialog = null
        }
        is ListDialog.Entry -> EntryActionsDialog(
            entry = d.entry,
            onDismiss = { dialog = null },
            onSave = { vm.editEntry(d.entry, it); dialog = null },
            onDelete = { vm.deleteEntry(d.entry); dialog = null },
        )
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
            onCategory = { dialog = ListDialog.Category(d.line.itemId, d.line.name) },
            onRemove = { vm.removeLine(d.line); dialog = null },
        )
        is ListDialog.EditItem -> EditItemDialog(d.item, s.categories, onDismiss = { dialog = null }) { name, categoryId, unit ->
            vm.updateItem(d.item.id, name, categoryId, unit)
            dialog = null
        }
        is ListDialog.Category -> CategoryPickerDialog(
            itemName = d.itemName,
            categories = s.categories.all,
            selected = s.items.firstOrNull { it.id == d.itemId }?.categoryId?.takeIf { s.categories[it] != null },
            automatic = s.categories.automatic(d.itemName),
            onDismiss = { dialog = null },
            onPick = { vm.setCategory(d.itemId, it); dialog = null },
            onCreate = { name -> vm.createCategory(name) { id -> vm.setCategory(d.itemId, id) }; dialog = null },
        )
    }
}

/** Which store this phone shops in, which decides the order of the categories. */
@Composable
private fun StoreBar(store: StoreRow?, stores: List<StoreRow>, onSelect: (String?) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp)) {
        AssistChip(
            onClick = { open = true },
            label = { Text(stringResource(R.string.store_chip, store?.name ?: stringResource(R.string.store_default_order))) },
            leadingIcon = { Icon(Icons.Filled.Storefront, null, Modifier.size(AssistChipDefaults.IconSize)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.store_choose), Modifier.size(AssistChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.store_default_order)) },
                leadingIcon = { RadioButton(selected = store == null, onClick = null) },
                onClick = { onSelect(null); open = false },
            )
            stores.forEach { st ->
                DropdownMenuItem(
                    text = { Text(st.name) },
                    leadingIcon = { RadioButton(selected = store?.id == st.id, onClick = null) },
                    onClick = { onSelect(st.id); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.store_manage)) },
                onClick = { onManage(); open = false },
            )
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

/**
 * Name (with catalog suggestions), amount, unit, category and note. Stays open for the next item.
 * New items get their category automatically unless one is picked.
 */
@Composable
private fun AddItemForm(
    items: List<ItemRow>,
    categories: CategoryIndex,
    onAdd: (String, Double?, String?, String?, String?) -> Unit,
    onCreateCategory: (String, (String) -> Unit) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    /** Picked by hand; null means automatic. */
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
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
        if (existing != null) {
            val c = categories.of(existing)
            Text(categoryLine(c.category, c.automatic), style = MaterialTheme.typography.bodySmall)
        } else if (name.isNotBlank()) {
            val picked = categories[categoryId]
            TextButton(onClick = { picking = true }) {
                Text(if (picked != null) categoryLine(picked, false) else categoryLine(categories.automatic(name), true))
            }
        }
        TextLine(note, { note = it }, R.string.field_note)
        added?.let { Text(stringResource(R.string.list_added, it), color = MaterialTheme.colorScheme.primary) }
        Button(
            enabled = name.isNotBlank() && !amountError,
            onClick = {
                onAdd(name.trim(), Units.parseAmount(amount), unit.ifBlank { null }, note.ifBlank { null }, categoryId.takeIf { existing == null })
                added = name.trim()
                name = ""; amount = ""; unit = ""; categoryId = null; note = ""
                runCatching { focus.requestFocus() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_add)) }
    }
    if (picking) {
        CategoryPickerDialog(
            itemName = name.trim(),
            categories = categories.all,
            selected = categoryId?.takeIf { categories[it] != null },
            automatic = categories.automatic(name),
            onDismiss = { picking = false },
            onPick = { categoryId = it; picking = false },
            onCreate = { n -> onCreateCategory(n) { id -> categoryId = id }; picking = false },
        )
    }
}

@Composable
private fun LineActionsDialog(
    line: ListLine,
    onDismiss: () -> Unit,
    onEditItem: () -> Unit,
    onCategory: () -> Unit,
    onRemove: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.name) },
        text = {
            Column {
                AssistChip(
                    onClick = onCategory,
                    label = { Text(categoryLine(line.category, line.automaticCategory)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
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
private fun EditItemDialog(item: ItemRow, categories: CategoryIndex, onDismiss: () -> Unit, onSave: (String, String?, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(item.name) }
    var categoryId by rememberSaveable { mutableStateOf(item.categoryId?.takeIf { categories[it] != null }) }
    var unit by rememberSaveable { mutableStateOf(item.defaultUnit.orEmpty()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_edit_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextLine(name, { name = it }, R.string.field_name, isError = name.isBlank())
                Picker(
                    label = R.string.field_category,
                    options = listOf<Pair<String?, String>>(null to automaticLabel(categories.automatic(name))) +
                        categories.all.map { it.id to it.name },
                    selected = categoryId,
                    onSelect = { categoryId = it },
                )
                UnitField(unit, { unit = it })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, categoryId, unit.ifBlank { null }) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
