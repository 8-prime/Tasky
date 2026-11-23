package dev.tasky.android.ui.stores

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tasky.android.R
import dev.tasky.android.data.db.CategoryRow
import dev.tasky.android.ui.components.ConfirmDialog
import dev.tasky.android.ui.components.TextInputDialog

private sealed interface StoresDialog {
    data object NewStore : StoresDialog
    data object NewCategory : StoresDialog
    data class Category(val category: CategoryRow) : StoresDialog
    data class RenameCategory(val category: CategoryRow) : StoresDialog
    data class DeleteCategory(val category: CategoryRow) : StoresDialog
}

/** Lists the stores (tap to edit a layout) and the categories (tap to rename or delete). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoresScreen(vm: StoresViewModel, onOpenStore: (String) -> Unit, onClose: () -> Unit) {
    val s by vm.state.collectAsState()
    var dialog by remember { mutableStateOf<StoresDialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stores_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
            item(key = "stores-h") { Header(R.string.stores_section, R.string.stores_help) }
            if (!s.loading && s.stores.isEmpty()) {
                item(key = "stores-none") {
                    Text(
                        stringResource(R.string.stores_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(s.stores, key = { "s-" + it.id }) { store ->
                ListItem(
                    modifier = Modifier.clickable { onOpenStore(store.id) },
                    leadingContent = { Icon(Icons.Filled.Storefront, null) },
                    headlineContent = { Text(store.name) },
                    supportingContent = {
                        Text(stringResource(if (store.order.isEmpty()) R.string.store_default_order else R.string.store_layout_custom))
                    },
                )
            }
            item(key = "stores-add") { AddRow(R.string.store_new) { dialog = StoresDialog.NewStore } }

            item(key = "categories-h") { Header(R.string.categories_section, R.string.categories_help) }
            items(s.categories, key = { "c-" + it.id }) { c ->
                ListItem(
                    modifier = Modifier.clickable { dialog = StoresDialog.Category(c) },
                    headlineContent = { Text(c.name) },
                )
            }
            item(key = "categories-add") { AddRow(R.string.category_new) { dialog = StoresDialog.NewCategory } }
        }
    }

    when (val d = dialog) {
        null -> Unit
        StoresDialog.NewStore -> TextInputDialog(
            title = stringResource(R.string.store_new), label = R.string.field_name, confirm = R.string.action_add,
            onDismiss = { dialog = null },
        ) { name ->
            vm.createStore(name, onOpenStore)
            dialog = null
        }
        StoresDialog.NewCategory -> TextInputDialog(
            title = stringResource(R.string.category_new), label = R.string.field_name, confirm = R.string.action_add,
            onDismiss = { dialog = null },
        ) { vm.createCategory(it); dialog = null }
        is StoresDialog.Category -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.category.name) },
            confirmButton = { TextButton(onClick = { dialog = StoresDialog.RenameCategory(d.category) }) { Text(stringResource(R.string.category_rename)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { dialog = StoresDialog.DeleteCategory(d.category) }) { Text(stringResource(R.string.action_delete)) }
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_close)) }
                }
            },
        )
        is StoresDialog.RenameCategory -> TextInputDialog(
            title = stringResource(R.string.category_rename), label = R.string.field_name, initial = d.category.name,
            onDismiss = { dialog = null },
        ) { vm.renameCategory(d.category.id, it); dialog = null }
        is StoresDialog.DeleteCategory -> ConfirmDialog(
            title = stringResource(R.string.category_delete_title, d.category.name),
            text = stringResource(R.string.category_delete_text),
            confirm = R.string.action_delete,
            onDismiss = { dialog = null },
        ) { vm.deleteCategory(d.category.id); dialog = null }
    }
}

/** A store's layout: every category in walking order, moved with up/down buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreEditScreen(vm: StoreEditViewModel, onClose: () -> Unit) {
    val s by vm.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Deleted here or on another phone.
    LaunchedEffect(s.loading, s.store) { if (!s.loading && s.store == null) onClose() }
    val store = s.store ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(store.name) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.store_rename)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.store_layout_reset)) },
                                enabled = store.order.isNotEmpty(),
                                onClick = { vm.resetOrder(); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.store_delete)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { deleting = true; menu = false },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
            item(key = "help") {
                Text(
                    stringResource(R.string.store_layout_help),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            itemsIndexed(s.categories, key = { _, c -> c.id }) { i, c ->
                ListItem(
                    modifier = Modifier.animateItem(),
                    leadingContent = {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, modifier = Modifier.width(24.dp))
                    },
                    headlineContent = { Text(c.name) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(enabled = i > 0, onClick = { vm.move(c.id, -1) }) {
                                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.store_move_up, c.name))
                            }
                            IconButton(enabled = i < s.categories.lastIndex, onClick = { vm.move(c.id, 1) }) {
                                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.store_move_down, c.name))
                            }
                        }
                    },
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.store_rename), label = R.string.field_name, initial = store.name,
            onDismiss = { renaming = false },
        ) { vm.rename(it); renaming = false }
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.store_delete_title, store.name),
            text = stringResource(R.string.store_delete_text),
            confirm = R.string.action_delete,
            onDismiss = { deleting = false },
        ) {
            deleting = false
            vm.delete()
        }
    }
}

@Composable
private fun Header(title: Int, help: Int) = Column {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    )
    Text(
        stringResource(help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AddRow(label: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Filled.Add, null) },
        headlineContent = { Text(stringResource(label)) },
    )
}
