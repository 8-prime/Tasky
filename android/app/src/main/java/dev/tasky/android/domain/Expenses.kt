package dev.tasky.android.domain

import dev.tasky.android.data.db.ExpenseRow
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Amounts are whole minor units (cents) of an ISO 4217 currency, never floating point. */
object Money {
    const val FALLBACK_CURRENCY = "EUR"

    fun isValidCode(code: String) = code.length == 3 && code.all { it in 'A'..'Z' }

    fun fractionDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 } ?: 2

    /** The currency of the device's region, used until the household has expenses. */
    fun localCurrency(locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()?.takeIf(::isValidCode) ?: FALLBACK_CURRENCY

    /**
     * Parses what someone typed ("12,50", "12.5", "7") into minor units. Either `.` or `,` is the
     * decimal separator; grouping separators aren't accepted. Returns null for anything that isn't
     * a positive amount with at most the currency's number of decimals.
     */
    fun parse(input: String, currency: String): Long? {
        val s = input.filterNot { it == ' ' || it == ' ' || it == ' ' }
        if (s.isEmpty() || s.count { it == '.' || it == ',' } > 1) return null
        val normalized = s.replace(',', '.')
        if (!normalized.all { it.isDigit() || it == '.' } || normalized.none { it.isDigit() }) return null
        val value = normalized.toBigDecimalOrNull() ?: return null
        val digits = fractionDigits(currency)
        if (value.stripTrailingZeros().scale() > digits) return null
        return runCatching { value.movePointRight(digits).longValueExact() }.getOrNull()?.takeIf { it > 0 }
    }

    /** Minor units as editable text, e.g. `12,50` in a German locale. */
    fun toInput(minor: Long, currency: String, locale: Locale = Locale.getDefault()): String {
        val text = BigDecimal.valueOf(minor, fractionDigits(currency)).toPlainString()
        return text.replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }

    fun format(minor: Long, currency: String, locale: Locale = Locale.getDefault()): String {
        val digits = fractionDigits(currency)
        val value = BigDecimal.valueOf(minor, digits)
        val known = runCatching { Currency.getInstance(currency) }.getOrNull()
            ?: return NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }.format(value) + " " + currency
        return NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = known
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(value)
    }

    /** Several currencies side by side: `€12.00 + $5.00`. */
    fun formatAll(byCurrency: Map<String, Long>, locale: Locale = Locale.getDefault()): String =
        byCurrency.entries.sortedBy { it.key }.joinToString(" + ") { format(it.value, it.key, locale) }
}

/** A suggested repayment: [from] pays [to] to settle up. */
data class Transfer(val from: String, val to: String, val amountMinor: Long, val currency: String)

/**
 * Expense book arithmetic (docs/API.md "Expenses"). Every device computes balances from the same
 * entries with the same integer rules, so they agree to the cent.
 */
object Expenses {
    /**
     * Splits [amountMinor] equally between [participants]. The cents that don't divide evenly go one
     * each to the first participants by ascending id, so the shares always add up to the total.
     */
    fun shares(amountMinor: Long, participants: List<String>): Map<String, Long> {
        val people = participants.distinct().sorted()
        if (people.isEmpty()) return emptyMap()
        val base = amountMinor / people.size
        val remainder = amountMinor % people.size
        return people.withIndex().associate { (i, id) -> id to base + if (i < remainder) 1 else 0 }
    }

    /**
     * Net balance per currency and member: what they paid minus their shares. Positive means the
     * household owes them money; negative means they owe. Settlements count the same way, which
     * is what moves a balance back towards zero.
     */
    fun balances(expenses: List<ExpenseRow>): Map<String, Map<String, Long>> {
        val out = HashMap<String, HashMap<String, Long>>()
        for (e in expenses) {
            val book = out.getOrPut(e.currency) { HashMap() }
            book.merge(e.paidBy, e.amountMinor, Long::plus)
            for ((user, share) in shares(e.amountMinor, e.participants)) book.merge(user, -share, Long::plus)
        }
        return out
    }

    /**
     * A short list of payments that brings every balance in [balances] to zero: the largest debtor
     * repeatedly pays the largest creditor. Ties are broken by id so every device suggests the same.
     */
    fun settleUp(balances: Map<String, Long>, currency: String): List<Transfer> {
        val creditors = balances.filter { it.value > 0 }.map { it.key to it.value }.toMutableList()
        val debtors = balances.filter { it.value < 0 }.map { it.key to -it.value }.toMutableList()
        val order = compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }
        val transfers = ArrayList<Transfer>()
        while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
            creditors.sortWith(order)
            debtors.sortWith(order)
            val (to, owed) = creditors[0]
            val (from, owes) = debtors[0]
            val amount = minOf(owed, owes)
            transfers += Transfer(from, to, amount, currency)
            if (owed == amount) creditors.removeAt(0) else creditors[0] = to to owed - amount
            if (owes == amount) debtors.removeAt(0) else debtors[0] = from to owes - amount
        }
        return transfers
    }

    /** What was spent, per currency. Settlements only move money around, so they don't count. */
    fun spent(expenses: List<ExpenseRow>): Map<String, Long> =
        expenses.filter { !it.isSettlement }.groupBy { it.currency }.mapValues { (_, es) -> es.sumOf { it.amountMinor } }

    /** [user]'s part of what was spent, per currency (zero entries left out). */
    fun shareOf(user: String, expenses: List<ExpenseRow>): Map<String, Long> =
        expenses.filter { !it.isSettlement }
            .groupBy { it.currency }
            .mapValues { (_, es) -> es.sumOf { shares(it.amountMinor, it.participants)[user] ?: 0L } }
            .filterValues { it != 0L }
}
