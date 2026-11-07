package dev.tasky.android.ui.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tasky.android.R
import dev.tasky.android.data.db.ItemRow
import dev.tasky.android.data.db.MealIngredientRow
import dev.tasky.android.domain.Units
import dev.tasky.android.ui.components.AmountField
import dev.tasky.android.ui.components.ConfirmDialog
import dev.tasky.android.ui.components.ItemNameField
import dev.tasky.android.ui.components.TextLine
import dev.tasky.android.ui.components.UnitField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealsScreen(vm: MealsViewModel, onOpen: (String?) -> Unit) {
    val meals by vm.meals.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.meals_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpen(null) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.meals_new)) },
            )
        },
    ) { padding ->
        val list = meals
        if (list != null && list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.meals_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
                items(list.orEmpty(), key = { it.meal.id }) { m ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(m.meal.id) },
                        headlineContent = { Text(m.meal.name) },
                        supportingContent = {
                            Text(
                                pluralStringResource(R.plurals.meals_summary_servings, m.meal.defaultServings, m.meal.defaultServings) +
                                    " · " + pluralStringResource(R.plurals.meals_summary_ingredients, m.ingredientCount, m.ingredientCount),
                            )
                        },
                    )
                }
            }
        }
    }
}

private sealed interface IngredientDialog {
    data object New : IngredientDialog
    data class Edit(val row: MealIngredientRow, val itemName: String) : IngredientDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealEditScreen(vm: MealEditViewModel, onClose: () -> Unit) {
    val s by vm.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var ingredientDialog by remember { mutableStateOf<IngredientDialog?>(null) }
    LaunchedEffect(s.gone) { if (s.gone) onClose() }

    // Form state, filled once the meal has loaded.
    var loadedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var servings by rememberSaveable { mutableStateOf("2") }
    var notes by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val meal = s.meal
    LaunchedEffect(meal?.id) {
        if (meal != null && loadedFor != meal.id) {
            loadedFor = meal.id
            name = meal.name
            servings = meal.defaultServings.toString()
            notes = meal.notes.orEmpty()
            url = meal.url.orEmpty()
        }
    }
    val servingsValue = servings.trim().toIntOrNull()?.takeIf { it in 1..1000 }
    val dirty = meal == null || name.trim() != meal.name || servingsValue != meal.defaultServings ||
        notes.trim() != meal.notes.orEmpty() || url.trim() != meal.url.orEmpty()
    val valid = name.isNotBlank() && servingsValue != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (s.mealId == null) R.string.meals_new else R.string.meals_edit)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    if (meal != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.meals_delete)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextLine(name, { name = it }, R.string.field_name, isError = name.isBlank() && loadedFor != null)
            TextLine(
                servings, { servings = it }, R.string.meals_default_servings, isError = servingsValue == null,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                supporting = stringResource(R.string.meals_default_servings_help),
            )
            TextLine(url, { url = it }, R.string.meals_url, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            TextLine(notes, { notes = it }, R.string.meals_notes, singleLine = false)
            Button(
                enabled = valid && dirty,
                onClick = { vm.save(name.trim(), servingsValue!!, notes.trim().ifEmpty { null }, url.trim().ifEmpty { null }) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (meal == null) R.string.meals_create else R.string.action_save)) }

            if (meal != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.meals_ingredients), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { ingredientDialog = IngredientDialog.New }) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.meals_add_ingredient))
                    }
                }
                if (s.ingredients.isEmpty()) {
                    Text(stringResource(R.string.meals_no_ingredients), style = MaterialTheme.typography.bodyMedium)
                }
                s.ingredients.forEach { ing ->
                    ListItem(
                        modifier = Modifier.clickable { ingredientDialog = IngredientDialog.Edit(ing.row, ing.itemName) },
                        headlineContent = { Text(ing.itemName) },
                        supportingContent = ing.row.note?.let { { Text(it) } },
                        trailingContent = { Text(Units.formatAmount(ing.row.amount, ing.row.unit)) },
                    )
                }
            }
        }
    }

    if (confirmDelete && meal != null) {
        ConfirmDialog(
            title = stringResource(R.string.meals_delete_title, meal.name),
            text = stringResource(R.string.meals_delete_text),
            confirm = R.string.action_delete,
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            vm.delete()
        }
    }
    when (val d = ingredientDialog) {
        null -> Unit
        IngredientDialog.New -> IngredientEditor(null, "", s.items, onDismiss = { ingredientDialog = null }, onDelete = null) { n, a, u, note ->
            vm.addIngredient(n, a, u, note)
            ingredientDialog = null
        }
        is IngredientDialog.Edit -> IngredientEditor(
            d.row, d.itemName, s.items,
            onDismiss = { ingredientDialog = null },
            onDelete = { vm.deleteIngredient(d.row); ingredientDialog = null },
        ) { n, a, u, note ->
            vm.updateIngredient(d.row, n, a, u, note)
            ingredientDialog = null
        }
    }
}

@Composable
private fun IngredientEditor(
    row: MealIngredientRow?,
    itemName: String,
    items: List<ItemRow>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, Double?, String?, String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(itemName) }
    var amount by rememberSaveable { mutableStateOf(row?.amount?.let(Units::number).orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(row?.unit.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(row?.note.orEmpty()) }
    val amountError = amount.isNotBlank() && Units.parseAmount(amount) == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (row == null) R.string.meals_add_ingredient else R.string.meals_edit_ingredient)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemNameField(name, { name = it }, items, onPick = { item ->
                    name = item.name
                    if (unit.isBlank()) unit = item.defaultUnit.orEmpty()
                })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField(amount, { amount = it }, Modifier.weight(1f), isError = amountError)
                    UnitField(unit, { unit = it }, Modifier.weight(1f))
                }
                TextLine(note, { note = it }, R.string.field_note)
                Text(stringResource(R.string.meals_amount_help), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !amountError,
                onClick = { onSave(name.trim(), Units.parseAmount(amount), unit.trim().ifEmpty { null }, note.trim().ifEmpty { null }) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
