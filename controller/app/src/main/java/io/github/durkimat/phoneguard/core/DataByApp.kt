package io.github.durkimat.phoneguard.core

/** The "By app" page of the mobile data screen: the biggest users this period (named by their label), the rest added up as "Other". Pure, tested without a phone. */
object DataByApp {
    /** [usage] is (app name, bytes); the same name can appear more than once (an app with several processes), so they are added together first. */
    fun rows(usage: List<Pair<String, Long>>, top: Int = 6): List<String> {
        val byName = LinkedHashMap<String, Long>()
        usage.forEach { (n, b) -> if (b > 0) byName[n] = (byName[n] ?: 0L) + b }
        val sorted = byName.entries.sortedByDescending { it.value }
        val shown = sorted.take(top)
        val other = sorted.drop(top).sumOf { it.value }
        return shown.map { it.key + "  " + DataView.amount(it.value) } + (if (other > 0) listOf("Other  " + DataView.amount(other)) else emptyList())
    }
}
