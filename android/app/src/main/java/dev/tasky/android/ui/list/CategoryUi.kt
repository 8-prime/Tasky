package dev.tasky.android.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.tasky.android.R
import dev.tasky.android.data.db.CategoryRow
import dev.tasky.android.ui.components.TextInputDialog

/** "Automatic (Dairy & eggs)", or "Automatic (none found)". */
@Composable
fun automaticLabel(automatic: CategoryRow?): String =
    automatic?.let { stringResource(R.string.category_automatic, it.name) } ?: stringResource(R.string.category_automatic_none)

/** "Category: Dairy & eggs (automatic)" for the current choice of an item. */
@Composable
fun categoryLine(category: CategoryRow?, automatic: Boolean): String {
    val name = category?.name ?: stringResource(R.string.list_uncategorized)
    return stringResource(if (automatic) R.string.category_of_item_auto else R.string.category_of_item, name)
}

/**
 * Picks the category of [itemName]: automatic (null) or one of [categories]. "New category"
 * creates one and picks it through [onCreate].
 */
@Composable
fun CategoryPickerDialog(
    itemName: String,
    categories: List<CategoryRow>,
    selected: String?,
    automatic: CategoryRow?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.category_new),
            label = R.string.field_name,
            initial = "",
            onDismiss = { creating = false },
        ) { onCreate(it) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_pick_title, itemName)) },
        text = {
            Column {
                Text(stringResource(R.string.category_pick_help), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item(key = "auto") { ChoiceRow(automaticLabel(automatic), selected == null) { onPick(null) } }
                    items(categories, key = { it.id }) { c -> ChoiceRow(c.name, selected == c.id) { onPick(c.id) } }
                    item(key = "new") {
                        ListItem(
                            modifier = Modifier.clickable { creating = true },
                            leadingContent = { Icon(Icons.Filled.Add, null) },
                            headlineContent = { Text(stringResource(R.string.category_new)) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label) },
    )
}
