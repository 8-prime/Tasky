package dev.tasky.android.ui.expenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tasky.android.R
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.ExpenseRow
import dev.tasky.android.domain.Expenses
import dev.tasky.android.domain.Money
import dev.tasky.android.domain.Transfer
import dev.tasky.android.ui.components.ConfirmDialog
import dev.tasky.android.ui.components.Picker
import dev.tasky.android.ui.components.SuggestionField
import dev.tasky.android.ui.components.TextLine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private sealed interface ExpenseDialog {
    data class Edit(val row: ExpenseRow?) : ExpenseDialog
    data class Payment(val row: ExpenseRow?, val suggested: Transfer? = null) : ExpenseDialog
    data object SettleUp : ExpenseDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(vm: ExpensesViewModel) {
    val s by vm.state.collectAsState()
    var dialog by remember { mutableStateOf<ExpenseDialog?>(null) }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.expenses_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = ExpenseDialog.Edit(null) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.expense_new)) },
            )
        },
    ) { padding ->
        if (s.isEmpty) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.expenses_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
                if (s.balances.isNotEmpty()) {
                    item(key = "balances") {
                        BalancesCard(s, onSettleUp = { dialog = ExpenseDialog.SettleUp })
                    }
                }
                s.months.forEach { group ->
                    item(key = "m-${group.month}") { MonthHeader(group, s.myUserId != null) }
                    items(group.expenses, key = { it.id }) { e ->
                        ExpenseItem(e, s, dayFormat) {
                            dialog = if (e.isSettlement) ExpenseDialog.Payment(e) else ExpenseDialog.Edit(e)
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is ExpenseDialog.Edit -> ExpenseEditor(
            s, d.row,
            onDismiss = { dialog = null },
            onSave = { input -> vm.save(d.row, input); dialog = null },
            onDelete = { vm.delete(it); dialog = null },
        )
        is ExpenseDialog.Payment -> PaymentEditor(
            s, d.row, d.suggested,
            onDismiss = { dialog = null },
            onSave = { from, to, amount, currency, date -> vm.recordSettlement(d.row, from, to, amount, currency, date); dialog = null },
            onDelete = { vm.delete(it); dialog = null },
        )
        ExpenseDialog.SettleUp -> SettleUpDialog(
            s,
            onDismiss = { dialog = null },
            onPick = { dialog = ExpenseDialog.Payment(null, it) },
        )
    }
}

@Composable
private fun BalancesCard(s: ExpensesState, onSettleUp: () -> Unit) {
    val unknown = stringResource(R.string.someone)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.balances.forEach { cb ->
                Text(
                    if (s.balances.size > 1) stringResource(R.string.balances_title_currency, cb.currency) else stringResource(R.string.balances_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                cb.members.forEach { (user, net) ->
                    val name = s.name(user) ?: unknown
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (user == s.myUserId) stringResource(R.string.household_you, name) else name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val amount = Money.format(kotlin.math.abs(net), cb.currency)
                        when {
                            net > 0 -> Text(stringResource(R.string.balance_gets_back, amount), color = MaterialTheme.colorScheme.primary)
                            net < 0 -> Text(stringResource(R.string.balance_owes, amount), color = MaterialTheme.colorScheme.error)
                            else -> Text(stringResource(R.string.balance_settled), color = LocalContentColor.current.copy(alpha = 0.72f))
                        }
                    }
                }
            }
            if (s.balances.any { it.transfers.isNotEmpty() }) {
                TextButton(onClick = onSettleUp, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.settle_up)) }
            } else {
                Text(
                    stringResource(R.string.balances_all_settled),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(group: MonthGroup, showMyShare: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val title = group.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { it.titlecase(locale) } + " " + group.month.year
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (group.spent.isNotEmpty()) {
                Text(stringResource(R.string.expenses_month_spent, Money.formatAll(group.spent)), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (showMyShare && group.myShare.isNotEmpty()) {
            Text(
                stringResource(R.string.expenses_month_your_share, Money.formatAll(group.myShare)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.End),
            )
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ExpenseItem(e: ExpenseRow, s: ExpensesState, dayFormat: DateTimeFormatter, onClick: () -> Unit) {
    val unknown = stringResource(R.string.someone)
    val payer = s.name(e.paidBy) ?: unknown
    val day = runCatching { LocalDate.parse(e.date).format(dayFormat) }.getOrDefault(e.date)
    val amount = Money.format(e.amountMinor, e.currency)
    if (e.isSettlement) {
        val to = e.participants.firstOrNull()?.let { s.name(it) } ?: unknown
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            leadingContent = { Icon(Icons.Filled.SwapHoriz, null) },
            overlineContent = { Text(day) },
            headlineContent = { Text(stringResource(R.string.settlement_title, payer, to)) },
            trailingContent = { Text(amount, style = MaterialTheme.typography.bodyLarge) },
        )
        return
    }
    val everyone = s.memberIds.isNotEmpty() && e.participants.toSet() == s.memberIds.toSet()
    val split = when {
        everyone -> stringResource(R.string.expense_paid_shared_everyone, payer)
        e.participants.size == 1 -> stringResource(R.string.expense_paid_for, payer, s.name(e.participants[0]) ?: unknown)
        else -> stringResource(R.string.expense_paid_shared, payer, e.participants.joinToString(", ") { s.name(it) ?: unknown })
    }
    val mine = s.myUserId?.let { Expenses.shares(e.amountMinor, e.participants)[it] }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = { Text(listOfNotNull(day, e.category).joinToString(" · ")) },
        headlineContent = { Text(e.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(split, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (mine != null && mine != e.amountMinor) {
                    Text(stringResource(R.string.expense_your_share, Money.format(mine, e.currency)), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

/** The typed amount in minor units, or null while amount or currency are invalid. */
private fun parseAmount(amount: String, currency: String): Long? {
    val code = currency.trim().uppercase()
    return if (Money.isValidCode(code)) Money.parse(amount, code) else null
}

/** Amount and currency side by side. */
@Composable
private fun MoneyFields(amount: String, onAmount: (String) -> Unit, currency: String, onCurrency: (String) -> Unit) {
    val code = currency.trim().uppercase()
    val parsed = parseAmount(amount, currency)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = amount,
            onValueChange = onAmount,
            label = { Text(stringResource(R.string.field_amount)) },
            singleLine = true,
            isError = amount.isNotBlank() && parsed == null && Money.isValidCode(code),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = currency,
            onValueChange = { onCurrency(it.filter(Char::isLetter).take(3).uppercase()) },
            label = { Text(stringResource(R.string.expense_currency)) },
            singleLine = true,
            isError = !Money.isValidCode(code),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.width(96.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(date: String, onDate: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val format = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault()) }
    val day = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.CalendarMonth, null, Modifier.padding(end = 8.dp))
        Text(day.format(format))
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(picker) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseEditor(
    s: ExpensesState,
    row: ExpenseRow?,
    onDismiss: () -> Unit,
    onSave: (TaskyRepository.ExpenseInput) -> Unit,
    onDelete: (ExpenseRow) -> Unit,
) {
    val fallbackPayer = s.myUserId ?: s.memberIds.firstOrNull()
    var description by rememberSaveable { mutableStateOf(row?.description.orEmpty()) }
    var currency by rememberSaveable { mutableStateOf(row?.currency ?: s.defaultCurrency) }
    var amount by rememberSaveable { mutableStateOf(row?.let { Money.toInput(it.amountMinor, it.currency) }.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(row?.date ?: LocalDate.now().toString()) }
    var paidBy by rememberSaveable { mutableStateOf(row?.paidBy ?: fallbackPayer) }
    // New expenses are shared by the whole household until someone is deselected.
    var split by rememberSaveable { mutableStateOf(row?.participants ?: s.memberIds.ifEmpty { listOfNotNull(fallbackPayer) }) }
    var category by rememberSaveable { mutableStateOf(row?.category.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(row?.note.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // People the entry mentions who aren't in the cached member list keep their place.
    val people = s.memberIds + split.filter { it !in s.memberIds } + listOfNotNull(paidBy).filter { it !in s.memberIds }
    val unknown = stringResource(R.string.someone)
    fun label(id: String) = s.name(id) ?: unknown

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (row == null) R.string.expense_new else R.string.expense_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextLine(description, { description = it.take(200) }, R.string.expense_description)
                MoneyFields(amount, { amount = it }, currency, { currency = it })
                DateButton(date) { date = it }
                if (people.size > 1 || paidBy == null) {
                    Picker(R.string.expense_paid_by, people.map<String, Pair<String?, String>> { it to label(it) }, paidBy, { paidBy = it })
                }
                if (people.size > 1) {
                    Text(stringResource(R.string.expense_split), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = split.toSet() == s.memberIds.toSet(),
                            onClick = { split = s.memberIds },
                            label = { Text(stringResource(R.string.expense_split_everyone)) },
                        )
                        people.forEach { id ->
                            FilterChip(
                                selected = id in split,
                                onClick = { split = if (id in split) split - id else split + id },
                                label = { Text(label(id)) },
                            )
                        }
                    }
                    SplitHint(parseAmount(amount, currency), currency, split)
                }
                SuggestionField(category, { category = it.take(64) }, R.string.field_category, s.categories)
                TextLine(note, { note = it.take(1000) }, R.string.field_note)
            }
        },
        confirmButton = {
            val code = currency.trim().uppercase()
            val parsed = parseAmount(amount, currency)
            val payer = paidBy
            TextButton(
                enabled = description.isNotBlank() && parsed != null && payer != null && split.isNotEmpty(),
                onClick = {
                    onSave(
                        TaskyRepository.ExpenseInput(
                            description = description, amountMinor = parsed!!, currency = code, date = date,
                            paidBy = payer!!, splitAmong = split, category = category, note = note,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (row != null) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
    if (confirmDelete && row != null) {
        ConfirmDialog(
            title = stringResource(R.string.expense_delete_title, row.description),
            text = stringResource(R.string.expense_delete_text),
            confirm = R.string.action_delete,
            onDismiss = { confirmDelete = false },
            onConfirm = { onDelete(row) },
        )
    }
}

@Composable
private fun SplitHint(amountMinor: Long?, currency: String, split: List<String>) {
    val text = when {
        split.isEmpty() -> stringResource(R.string.expense_split_error)
        amountMinor == null || split.size < 2 -> return
        else -> {
            val shares = Expenses.shares(amountMinor, split).values
            val lo = Money.format(shares.min(), currency)
            val hi = Money.format(shares.max(), currency)
            if (lo == hi) stringResource(R.string.expense_split_each, lo) else stringResource(R.string.expense_split_range, lo, hi)
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (split.isEmpty()) MaterialTheme.colorScheme.error else LocalContentColor.current.copy(alpha = 0.72f),
    )
}

@Composable
private fun PaymentEditor(
    s: ExpensesState,
    row: ExpenseRow?,
    suggested: Transfer?,
    onDismiss: () -> Unit,
    onSave: (from: String, to: String, amountMinor: Long, currency: String, date: String) -> Unit,
    onDelete: (ExpenseRow) -> Unit,
) {
    val me = s.myUserId ?: s.memberIds.firstOrNull()
    var from by rememberSaveable { mutableStateOf(row?.paidBy ?: suggested?.from ?: me) }
    var to by rememberSaveable { mutableStateOf(row?.participants?.firstOrNull() ?: suggested?.to ?: s.memberIds.firstOrNull { it != me }) }
    var currency by rememberSaveable { mutableStateOf(row?.currency ?: suggested?.currency ?: s.defaultCurrency) }
    var amount by rememberSaveable {
        mutableStateOf(
            (row?.let { it.amountMinor to it.currency } ?: suggested?.let { it.amountMinor to it.currency })
                ?.let { (minor, cur) -> Money.toInput(minor, cur) }.orEmpty(),
        )
    }
    var date by rememberSaveable { mutableStateOf(row?.date ?: LocalDate.now().toString()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.someone)
    val people = (s.memberIds + listOfNotNull(from, to)).distinct().map<String, Pair<String?, String>> { it to (s.name(it) ?: unknown) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (row == null) R.string.settlement_new else R.string.settlement_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settlement_help), style = MaterialTheme.typography.bodySmall)
                Picker(R.string.settlement_from, people, from, { from = it })
                Picker(R.string.settlement_to, people.filter { it.first != from }, to, { to = it })
                MoneyFields(amount, { amount = it }, currency, { currency = it })
                DateButton(date) { date = it }
            }
        },
        confirmButton = {
            val code = currency.trim().uppercase()
            val parsed = parseAmount(amount, currency)
            val payer = from
            val recipient = to
            TextButton(
                enabled = parsed != null && payer != null && recipient != null && payer != recipient,
                onClick = { onSave(payer!!, recipient!!, parsed!!, code, date) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (row != null) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
    if (confirmDelete && row != null) {
        ConfirmDialog(
            title = stringResource(R.string.settlement_delete_title),
            text = stringResource(R.string.expense_delete_text),
            confirm = R.string.action_delete,
            onDismiss = { confirmDelete = false },
            onConfirm = { onDelete(row) },
        )
    }
}

@Composable
private fun SettleUpDialog(s: ExpensesState, onDismiss: () -> Unit, onPick: (Transfer?) -> Unit) {
    val unknown = stringResource(R.string.someone)
    val transfers = s.balances.flatMap { it.transfers }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settle_up)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settle_up_help), style = MaterialTheme.typography.bodySmall)
                transfers.forEach { t ->
                    ListItem(
                        modifier = Modifier.clickable { onPick(t) },
                        headlineContent = {
                            Text(stringResource(R.string.settle_up_transfer, s.name(t.from) ?: unknown, s.name(t.to) ?: unknown))
                        },
                        trailingContent = { Text(Money.format(t.amountMinor, t.currency), style = MaterialTheme.typography.bodyLarge) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.settle_up_other)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
