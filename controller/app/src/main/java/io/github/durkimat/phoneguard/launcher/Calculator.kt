package io.github.durkimat.phoneguard.launcher

import java.math.BigDecimal
import java.math.MathContext

/**
 * A four-function calculator driven the way a Nokia's was: type a number, press a key for the operator,
 * type the next number, press equals. Pure Kotlin so the arithmetic is unit tested.
 */
class Calculator {
    private var acc: BigDecimal? = null
    private var op: Char? = null
    private var entry = ""
    private var fresh = false // the entry is a result: the next digit starts a new number
    private var error = false

    private val ops = listOf('+', '-', 'x', '/')

    /** The big line: what is being typed, or the last result. */
    val display: String get() = when {
        error -> "Error"
        entry.isEmpty() -> acc?.let { show(it) } ?: "0"
        else -> entry
    }

    /** The small line above: the number so far and the operator waiting, like "12.5 x". */
    val pending: String get() = if (error) "" else acc?.let { show(it) + (op?.let { o -> " $o" } ?: "") } ?: ""

    val isEmpty: Boolean get() = !error && entry.isEmpty() && acc == null

    fun digit(d: Int) {
        if (error) allClear()
        if (fresh) { entry = ""; fresh = false }
        if (entry.replace(".", "").replace("-", "").length >= 12) return
        entry = if (entry == "0") d.toString() else entry + d
    }

    fun point() {
        if (error) allClear()
        if (fresh) { entry = ""; fresh = false }
        if (entry.contains('.')) return
        entry = if (entry.isEmpty()) "0." else "$entry."
    }

    /** Sets the operator; pressing it again with nothing typed changes the one chosen. */
    fun operator(o: Char) {
        if (error) return
        if (entry.isEmpty() || entry == "0.") {
            if (acc != null) op = o
            return
        }
        val typed = BigDecimal(entry.removeSuffix("."))
        acc = if (acc != null && op != null) apply(acc!!, op!!, typed) ?: run { error = true; return } else typed
        op = o; entry = ""; fresh = false
    }

    /** The * key: step through + - x / */
    fun cycleOperator() {
        val next = op?.let { ops[(ops.indexOf(it) + 1) % ops.size] } ?: ops[0]
        operator(next)
    }

    fun equals() {
        if (error) return
        val a = acc; val o = op
        if (a == null || o == null || entry.isEmpty()) return
        val result = apply(a, o, BigDecimal(entry.removeSuffix("."))) ?: run { error = true; return }
        entry = show(result); acc = null; op = null; fresh = true
    }

    /** Clear: removes the last digit; with nothing typed it clears the sum. Returns false when there was nothing to clear. */
    fun clear(): Boolean {
        if (error) { allClear(); return true }
        return when {
            entry.isNotEmpty() && !fresh -> { entry = entry.dropLast(1); true }
            entry.isNotEmpty() || acc != null -> { allClear(); true }
            else -> false
        }
    }

    fun allClear() { acc = null; op = null; entry = ""; fresh = false; error = false }

    private fun apply(a: BigDecimal, o: Char, b: BigDecimal): BigDecimal? = when (o) {
        '+' -> a + b
        '-' -> a - b
        'x' -> a * b
        else -> if (b.signum() == 0) null else a.divide(b, MathContext(12))
    }

    private fun show(v: BigDecimal): String {
        var s = v.round(MathContext(12)).stripTrailingZeros().toPlainString()
        if (s == "-0") s = "0"
        return s
    }
}
