package dev.tasky.android.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasky.android.data.SessionStore
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.ExpenseRow
import dev.tasky.android.data.db.MemberRow
import dev.tasky.android.domain.Expenses
import dev.tasky.android.domain.Money
import dev.tasky.android.domain.Transfer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

/** Balances in one currency: every member's net amount, and payments that would settle them. */
data class CurrencyBalances(
    val currency: String,
    /** Positive: gets money back. Negative: owes. Every member is listed, also at zero. */
    val members: List<Pair<String, Long>>,
    val transfers: List<Transfer>,
)

data class MonthGroup(
    val month: YearMonth,
    val spent: Map<String, Long>,
    val myShare: Map<String, Long>,
    val expenses: List<ExpenseRow>,
)

data class ExpensesState(
    /** False until the first read from the local database. */
    val loaded: Boolean = false,
    val months: List<MonthGroup> = emptyList(),
    val balances: List<CurrencyBalances> = emptyList(),
    val members: List<MemberRow> = emptyList(),
    val myUserId: String? = null,
    /** Prefilled for new entries: the most recent expense's currency, else the device's. */
    val defaultCurrency: String = Money.localCurrency(),
    /** Categories used before, most used first. */
    val categories: List<String> = emptyList(),
) {
    private val names = members.associate { it.userId to it.displayName }
    fun name(userId: String): String? = names[userId]
    val memberIds: List<String> get() = members.map { it.userId }
    val isEmpty get() = loaded && months.isEmpty()
}

class ExpensesViewModel(private val repo: TaskyRepository, private val sessions: SessionStore) : ViewModel() {
    val state: StateFlow<ExpensesState> = combine(repo.queries.expenses(), repo.queries.members()) { expenses, members ->
        build(expenses, members, sessions.current?.userId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesState())

    fun save(existing: ExpenseRow?, input: TaskyRepository.ExpenseInput) = viewModelScope.launch {
        if (existing == null) repo.createExpense(input) else repo.updateExpense(existing, input)
    }

    fun recordSettlement(existing: ExpenseRow?, from: String, to: String, amountMinor: Long, currency: String, date: String) =
        viewModelScope.launch {
            if (existing == null) {
                repo.recordSettlement(from, to, amountMinor, currency, date)
            } else {
                repo.updateExpense(
                    existing,
                    TaskyRepository.ExpenseInput(existing.description, amountMinor, currency, date, from, listOf(to)),
                )
            }
        }

    fun delete(expense: ExpenseRow) = viewModelScope.launch { repo.deleteExpense(expense.id) }

    companion object {
        /** Expenses arrive newest first (by date, then creation). */
        fun build(expenses: List<ExpenseRow>, members: List<MemberRow>, me: String?): ExpensesState {
            val memberIds = members.map { it.userId }
            val balances = Expenses.balances(expenses).toSortedMap().map { (currency, book) ->
                // Everyone in the household, plus anyone the entries mention who isn't a member here yet.
                val people = (memberIds + book.keys.filter { it !in memberIds }).map { it to (book[it] ?: 0L) }
                CurrencyBalances(currency, people, Expenses.settleUp(book, currency))
            }
            val months = expenses.groupBy { YearMonth.parse(it.date.take(7)) }.map { (month, es) ->
                MonthGroup(month, Expenses.spent(es), me?.let { Expenses.shareOf(it, es) }.orEmpty(), es)
            }
            val categories = expenses.mapNotNull { it.category?.trim()?.takeIf(String::isNotEmpty) }
                .groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
                .map { it.key }
            return ExpensesState(
                loaded = true,
                months = months,
                balances = balances,
                members = members,
                myUserId = me,
                defaultCurrency = expenses.firstOrNull()?.currency ?: Money.localCurrency(),
                categories = categories,
            )
        }
    }
}
