package dev.tasky.android.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Recommended unit codes, offered in the unit picker (docs/API.md). */
val UNIT_CODES = listOf("g", "kg", "ml", "l", "tsp", "tbsp", "pcs")

/** A group of units that can be added up. */
sealed interface Dimension {
    data object Mass : Dimension
    data object Volume : Dimension
    data object Count : Dimension
    /** Any other unit (`can`, `pinch`…), keyed by its exact text. */
    data class Other(val unit: String) : Dimension
}

/** A summed amount in a dimension. [base] is in g, ml or pcs; for [Dimension.Other] in the unit itself. */
data class Quantity(val dimension: Dimension, val base: Double, val units: Set<String>)

object Units {
    private val factors = mapOf(
        "g" to (Dimension.Mass to 1.0),
        "kg" to (Dimension.Mass to 1000.0),
        "ml" to (Dimension.Volume to 1.0),
        "l" to (Dimension.Volume to 1000.0),
        "tsp" to (Dimension.Volume to 5.0),
        "tbsp" to (Dimension.Volume to 15.0),
        "pcs" to (Dimension.Count to 1.0),
    )

    /** Codes are matched ignoring case and surrounding spaces; a missing unit counts as `pcs`. */
    private fun lookup(unit: String?): Pair<Dimension, Double> {
        val u = unit?.trim().orEmpty()
        if (u.isEmpty()) return Dimension.Count to 1.0
        return factors[u.lowercase()] ?: (Dimension.Other(u) to 1.0)
    }

    fun dimensionOf(unit: String?): Dimension = lookup(unit).first

    /**
     * Sums amounts per dimension. Entries without an amount are ignored here (they still count
     * toward the line). Order: mass, volume, count, then other units by first appearance.
     */
    fun sum(amounts: List<Pair<Double?, String?>>): List<Quantity> {
        val totals = LinkedHashMap<Dimension, Pair<Double, MutableSet<String>>>()
        for ((amount, unit) in amounts) {
            if (amount == null) continue
            val (dim, factor) = lookup(unit)
            val code = unit?.trim()?.takeIf { it.isNotEmpty() }?.let { if (dim is Dimension.Other) it else it.lowercase() } ?: "pcs"
            val (sum, units) = totals[dim] ?: (0.0 to mutableSetOf())
            units += code
            totals[dim] = (sum + amount * factor) to units
        }
        val order = listOf(Dimension.Mass, Dimension.Volume, Dimension.Count)
        return totals.entries
            .sortedBy { (dim, _) -> order.indexOf(dim).let { if (it < 0) order.size else it } }
            .map { (dim, v) -> Quantity(dim, v.first, v.second) }
    }

    /** `1.2 kg`, `750 g`, `3 tbsp`, `2 pcs`, `1 can`. */
    fun format(q: Quantity): String = when (q.dimension) {
        Dimension.Mass -> if (q.base >= 1000) "${number(q.base / 1000)} kg" else "${number(q.base)} g"
        Dimension.Volume -> when {
            // Spoons stay spoons when nothing else was added.
            q.units == setOf("tsp") -> "${number(q.base / 5)} tsp"
            q.units == setOf("tbsp") -> "${number(q.base / 15)} tbsp"
            q.base >= 1000 -> "${number(q.base / 1000)} l"
            else -> "${number(q.base)} ml"
        }
        Dimension.Count -> "${number(q.base)} pcs"
        is Dimension.Other -> "${number(q.base)} ${q.dimension.unit}"
    }

    fun format(quantities: List<Quantity>): String = quantities.joinToString(" + ") { format(it) }

    /** At most two decimals, no trailing zeros: `1.25`, `0.5`, `3`. */
    fun number(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Parses user input like `1,5` or `2`. Null if blank or invalid. */
    fun parseAmount(text: String): Double? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= 1e9 }
    }

    /** A single amount + unit for display, e.g. `200 g`. */
    fun formatAmount(amount: Double?, unit: String?): String = when {
        amount == null -> unit.orEmpty()
        unit.isNullOrBlank() -> number(amount)
        else -> "${number(amount)} ${unit.trim()}"
    }
}
