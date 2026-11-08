package dev.tasky.android.domain

import dev.tasky.android.data.db.EXPENSE_REGULAR
import dev.tasky.android.data.db.EXPENSE_SETTLEMENT
import dev.tasky.android.data.db.ExpenseRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ExpensesTest {
    private var n = 0
    private fun e(paidBy: String, amount: Long, split: List<String>, currency: String = "EUR", settlement: Boolean = false) =
        ExpenseRow(
            "e${n++}", "x", amount, currency, "2026-11-07", paidBy, split.joinToString(","), null, null,
            if (settlement) EXPENSE_SETTLEMENT else EXPENSE_REGULAR,
        )

    @Test
    fun sharesAddUpAndLeftoverCentsGoByAscendingId() {
        assertEquals(mapOf("a" to 334L, "b" to 333L, "c" to 333L), Expenses.shares(1000, listOf("c", "a", "b")))
        assertEquals(mapOf("a" to 500L, "b" to 500L), Expenses.shares(1000, listOf("b", "a")))
        assertEquals(mapOf("a" to 1L, "b" to 1L, "c" to 0L), Expenses.shares(2, listOf("a", "b", "c")))
        assertEquals(mapOf("a" to 7L), Expenses.shares(7, listOf("a", "a")))
        assertTrue(Expenses.shares(5, emptyList()).isEmpty())
    }

    @Test
    fun balancesNetPaymentsAgainstSharesPerCurrency() {
        val b = Expenses.balances(
            listOf(
                e("a", 9000, listOf("a", "b", "c")), // rent: a paid, everyone owes 30
                e("b", 1000, listOf("a", "b")), // b paid 10 for a and b
                e("c", 500, listOf("b"), currency = "USD"),
            ),
        )
        assertEquals(mapOf("a" to 5500L, "b" to -2500L, "c" to -3000L), b["EUR"])
        assertEquals(mapOf("c" to 500L, "b" to -500L), b["USD"])
        b.values.forEach { assertEquals("balances sum to zero", 0L, it.values.sum()) }
    }

    @Test
    fun settlementsMoveBalancesBack() {
        val expenses = listOf(e("a", 4000, listOf("a", "b")), e("b", 2000, listOf("a"), settlement = true))
        assertEquals(mapOf("a" to 0L, "b" to 0L), Expenses.balances(expenses)["EUR"])
        assertEquals(mapOf("EUR" to 4000L), Expenses.spent(expenses))
        assertEquals(mapOf("EUR" to 2000L), Expenses.shareOf("b", expenses))
    }

    @Test
    fun settleUpPaysLargestCreditorFirstAndClearsEverything() {
        val balances = mapOf("a" to 5500L, "b" to -2500L, "c" to -3000L, "d" to 0L)
        val t = Expenses.settleUp(balances, "EUR")
        assertEquals(listOf(Transfer("c", "a", 3000, "EUR"), Transfer("b", "a", 2500, "EUR")), t)

        val chain = mapOf("a" to 700L, "b" to 300L, "c" to -600L, "d" to -400L)
        val result = HashMap(chain)
        for (x in Expenses.settleUp(chain, "EUR")) {
            result.merge(x.from, x.amountMinor, Long::plus)
            result.merge(x.to, -x.amountMinor, Long::plus)
        }
        assertTrue(result.values.all { it == 0L })
        assertTrue(Expenses.settleUp(mapOf("a" to 0L), "EUR").isEmpty())
    }

    @Test
    fun parsesTypedAmounts() {
        assertEquals(1250L, Money.parse("12,50", "EUR"))
        assertEquals(1250L, Money.parse("12.5", "EUR"))
        assertEquals(700L, Money.parse(" 7 ", "EUR"))
        assertEquals(50L, Money.parse(",5", "EUR"))
        assertEquals(1200L, Money.parse("12.00", "EUR"))
        assertEquals(1500L, Money.parse("1500", "JPY"))
        assertNull(Money.parse("1500.5", "JPY"))
        assertNull(Money.parse("1.234,56", "EUR"))
        assertNull(Money.parse("12.345", "EUR"))
        assertNull(Money.parse("0", "EUR"))
        assertNull(Money.parse("-3", "EUR"))
        assertNull(Money.parse("abc", "EUR"))
        assertNull(Money.parse(".", "EUR"))
        assertNull(Money.parse("", "EUR"))
    }

    @Test
    fun formatsInTheDevicesLocale() {
        assertEquals("€12.50", Money.format(1250, "EUR", Locale.US))
        assertEquals("12,50 €", Money.format(1250, "EUR", Locale.GERMANY).replace(' ', ' '))
        assertEquals("¥1,500", Money.format(1500, "JPY", Locale.US).replace('￥', '¥'))
        assertEquals("12.50 XYZ", Money.format(1250, "XYZ", Locale.US))
        assertEquals("12,50", Money.toInput(1250, "EUR", Locale.GERMANY))
        assertEquals("12.50", Money.toInput(1250, "EUR", Locale.US))
        assertEquals("€12.00 + \$5.00", Money.formatAll(mapOf("USD" to 500L, "EUR" to 1200L), Locale.US))
        assertEquals("EUR", Money.localCurrency(Locale.GERMANY))
    }
}
