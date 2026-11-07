package dev.tasky.android.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tasky.android.R
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.PlanEntryRow
import dev.tasky.android.data.db.mealSlotOf
import dev.tasky.android.domain.MealPlanning
import dev.tasky.android.domain.Units
import dev.tasky.android.ui.components.ConfirmDialog
import dev.tasky.android.ui.components.TextLine
import dev.tasky.api.MealSlot
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface PlanDialog {
    data class Edit(val date: LocalDate, val slot: MealSlot, val entry: PlanEntryRow?) : PlanDialog
    data class Actions(val entry: EntryView) : PlanDialog
    data class Delete(val entry: EntryView) : PlanDialog
    /** Add to list for one entry, or the whole week when [entry] is null. Rows are loaded before showing. */
    data class AddToList(val entry: PlanEntryRow?, val rows: List<ProposalRow>) : PlanDialog
}

@Composable
fun slotLabel(slot: MealSlot): String = stringResource(
    when (slot) {
        MealSlot.Breakfast -> R.string.slot_breakfast
        MealSlot.Lunch -> R.string.slot_lunch
        MealSlot.Dinner -> R.string.slot_dinner
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(vm: PlanViewModel) {
    val s by vm.state.collectAsState()
    var dialog by remember { mutableStateOf<PlanDialog?>(null) }
    val today = LocalDate.now()
    val weekFormat = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun openAddToList(entry: PlanEntryRow?) {
        scope.launch { dialog = PlanDialog.AddToList(entry, vm.proposals(entry)) }
    }
    LaunchedEffect(s.weekStart) {
        val index = if (MealPlanning.weekStart(today) == s.weekStart) today.dayOfWeek.value - 1 else 0
        listState.scrollToItem(index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = vm::previousWeek) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.plan_previous_week))
                        }
                        Text(
                            stringResource(R.string.plan_week_of, s.weekStart.format(weekFormat)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = vm::nextWeek) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.plan_next_week))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::thisWeek) { Icon(Icons.Filled.Today, stringResource(R.string.plan_this_week)) }
                    IconButton(onClick = { openAddToList(null) }) {
                        Icon(Icons.Filled.AddShoppingCart, stringResource(R.string.plan_add_week_to_list))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(s.days, key = { it.date.toString() }) { day ->
                DayCard(
                    day = day,
                    isToday = day.date == today,
                    onAdd = { slot -> dialog = PlanDialog.Edit(day.date, slot, null) },
                    onEntry = { dialog = PlanDialog.Actions(it) },
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is PlanDialog.Edit -> EntryEditor(s, d.date, d.slot, d.entry, onDismiss = { dialog = null }) { existing, input ->
            vm.save(existing, input)
            dialog = null
        }
        is PlanDialog.Actions -> EntryActions(
            d.entry,
            onDismiss = { dialog = null },
            onEdit = { dialog = PlanDialog.Edit(LocalDate.parse(d.entry.row.date), mealSlotOf(d.entry.row.slot), d.entry.row) },
            onAddToList = { openAddToList(d.entry.row) },
            onDelete = { dialog = PlanDialog.Delete(d.entry) },
        )
        is PlanDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.plan_delete_title, d.entry.title),
            text = stringResource(R.string.plan_delete_text),
            confirm = R.string.action_delete,
            onDismiss = { dialog = null },
        ) {
            vm.delete(d.entry.row)
            dialog = null
        }
        is PlanDialog.AddToList -> AddToListDialog(vm, s, d.entry, d.rows, onDismiss = { dialog = null })
    }
}

@Composable
private fun DayCard(day: DayPlan, isToday: Boolean, onAdd: (MealSlot) -> Unit, onEntry: (EntryView) -> Unit) {
    val format = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
    Card(
        colors = if (isToday) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                day.date.format(format),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isToday) FontWeight.Bold else null,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            MealSlot.entries.forEach { slot ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        slotLabel(slot),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(84.dp).padding(top = 12.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        day.slots[slot].orEmpty().forEach { e -> EntryLine(e) { onEntry(e) } }
                    }
                    IconButton(onClick = { onAdd(slot) }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.plan_add_to_slot, slotLabel(slot)))
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryLine(e: EntryView, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp)) {
        Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val details = buildList {
            add(e.ownerName ?: stringResource(R.string.plan_household))
            add(pluralStringResource(R.plurals.meals_summary_servings, e.servings, e.servings))
            if (e.onListCount > 0) add(stringResource(R.string.plan_on_list))
            e.row.note?.let { add(it) }
        }
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryActions(e: EntryView, onDismiss: () -> Unit, onEdit: () -> Unit, onAddToList: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title) },
        text = {
            Column {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
                TextButton(onClick = onAddToList, enabled = e.hasIngredients) { Text(stringResource(R.string.plan_add_to_list)) }
                if (!e.hasIngredients) {
                    Text(stringResource(R.string.plan_no_ingredients), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Dropdown over fixed choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: Int, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@Composable
private fun EntryEditor(
    s: PlanState,
    date: LocalDate,
    slot: MealSlot,
    entry: PlanEntryRow?,
    onDismiss: () -> Unit,
    onSave: (PlanEntryRow?, TaskyRepository.PlanEntryInput) -> Unit,
) {
    var day by rememberSaveable { mutableStateOf(date.toString()) }
    var slotValue by rememberSaveable { mutableStateOf(slot) }
    var mealId by rememberSaveable { mutableStateOf(entry?.mealId ?: if (entry == null) s.meals.firstOrNull()?.id else null) }
    var freeText by rememberSaveable { mutableStateOf(entry?.freeText.orEmpty()) }
    var owner by rememberSaveable { mutableStateOf(entry?.ownerUserId) }
    var servings by rememberSaveable { mutableStateOf(entry?.servings?.toString().orEmpty()) }
    var note by rememberSaveable { mutableStateOf(entry?.note.orEmpty()) }

    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()) }
    val weekStart = MealPlanning.weekStart(date)
    val servingsValue = servings.trim().toIntOrNull()
    val servingsError = servings.isNotBlank() && (servingsValue == null || servingsValue !in 1..1000)
    val defaultServings = if (owner == null) s.members.size.coerceAtLeast(1) else 1
    val valid = (mealId != null || freeText.isNotBlank()) && !servingsError
    val otherLabel = stringResource(R.string.plan_other_meal)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (entry == null) R.string.plan_new_entry else R.string.plan_edit_entry)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(
                    R.string.plan_meal,
                    s.meals.map<_, Pair<String?, String>> { it.id to it.name } + (null to otherLabel),
                    mealId,
                    { mealId = it },
                )
                if (mealId == null || freeText.isNotBlank()) {
                    TextLine(freeText, { freeText = it.take(200) }, R.string.plan_free_text)
                }
                Picker(
                    R.string.plan_for,
                    listOf<Pair<String?, String>>(null to stringResource(R.string.plan_household)) + s.members.map { it.userId to it.displayName },
                    owner,
                    { owner = it },
                )
                Picker(
                    R.string.plan_day,
                    MealPlanning.weekDays(weekStart).map { it.toString() to it.format(dayFormat) },
                    day,
                    { day = it },
                )
                Picker(R.string.plan_slot, MealSlot.entries.map { it to slotLabel(it) }, slotValue, { slotValue = it })
                OutlinedTextField(
                    value = servings,
                    onValueChange = { servings = it },
                    label = { Text(stringResource(R.string.field_servings)) },
                    placeholder = { Text(defaultServings.toString()) },
                    supportingText = { Text(stringResource(R.string.plan_servings_help, defaultServings)) },
                    isError = servingsError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextLine(note, { note = it }, R.string.field_note)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    entry,
                    TaskyRepository.PlanEntryInput(
                        date = day, slot = slotValue, ownerUserId = owner, mealId = mealId,
                        freeText = freeText.trim().ifEmpty { null }, servings = servingsValue, note = note.trim().ifEmpty { null },
                    ),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AddToListDialog(vm: PlanViewModel, s: PlanState, entry: PlanEntryRow?, rows: List<ProposalRow>, onDismiss: () -> Unit) {
    // Ingredients already put on a list from these entries start unticked.
    val selected = remember(rows) { mutableStateListOf<String>().apply { addAll(rows.filter { !it.alreadyAdded }.map { it.proposal.id }) } }
    var listId by rememberSaveable { mutableStateOf(s.lists.firstOrNull()?.id) }
    val scope = rememberCoroutineScope()
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (entry == null) R.string.plan_add_week_to_list else R.string.plan_add_to_list)) },
        text = {
            val r = rows
            when {
                r.isEmpty() -> Text(stringResource(R.string.plan_nothing_to_add))
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.plan_untick_help), style = MaterialTheme.typography.bodySmall)
                    if (s.lists.size > 1) {
                        Picker(R.string.plan_list, s.lists.map<_, Pair<String?, String>> { it.id to it.name }, listId, { listId = it })
                    }
                    var lastHeader: String? = null
                    r.forEach { row ->
                        val p = row.proposal
                        val header = if (entry == null) "${LocalDate.parse(p.date).format(dayFormat)} · ${p.mealName}" else null
                        if (header != null && header != lastHeader) {
                            lastHeader = header
                            Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (p.id in selected) selected.remove(p.id) else selected.add(p.id)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = p.id in selected, onCheckedChange = { if (it) selected.add(p.id) else selected.remove(p.id) })
                            Column(Modifier.weight(1f)) {
                                Text(p.itemName)
                                val sub = listOfNotNull(
                                    Units.formatAmount(p.amount, p.unit).ifBlank { null },
                                    if (row.alreadyAdded) stringResource(R.string.plan_already_added) else null,
                                ).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() && listId != null,
                onClick = {
                    val chosen = rows.map { it.proposal }.filter { it.id in selected }
                    scope.launch {
                        vm.addToList(listId!!, chosen)
                        onDismiss()
                    }
                },
            ) { Text(pluralStringResource(R.plurals.plan_add_n, selected.size, selected.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
