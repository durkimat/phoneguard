package io.github.durkimat.phoneguard.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The family's mobile data plan as the owner tells the phone: the monthly allowance and when the month starts. The carrier's own count is the one
 * that matters, so a correction (add or take away megabytes) is part of it. Pure logic; the measuring is in net/DataUsage.
 * GB here are the carrier's (1 GB = 1000 MB = 1,000,000,000 bytes).
 */
data class DataPlan(
    /** Allowance in bytes; 0 means no plan set (the widget is not shown). */
    val allowance: Long = 0,
    /** Day of the month the plan renews, 1 to 28 (used when [start] is empty). */
    val renewDay: Int = 1,
    /** An exact start date (yyyy-mm-dd) of one period; periods then run month by month from it. Empty to use [renewDay]. */
    val start: String = "",
    /** Warn when this share or less is left (percent). */
    val warnPct: Int = 10,
    /** Megabytes added to (or, negative, taken from) the phone's own count, to match the carrier. */
    val correctionMb: Long = 0,
    /** The subscription to track; -1 means the one used for mobile data. */
    val subId: Int = -1,
) {
    val set: Boolean get() = allowance > 0

    /** The period that contains [now]: its start and end (exclusive), in epoch milliseconds. */
    fun period(now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val today = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone).toLocalDate()
        val anchor = parseDate(start)
        val day = anchor?.dayOfMonth ?: renewDay.coerceIn(1, 28)
        // the latest start on or before today: this month's [day] (clamped to the month's length), else last month's
        fun startIn(m: LocalDate) = m.withDayOfMonth(day.coerceAtMost(m.lengthOfMonth()))
        var s = startIn(today.withDayOfMonth(1))
        if (s.isAfter(today)) s = startIn(today.withDayOfMonth(1).minusMonths(1))
        if (anchor != null && s.isBefore(anchor)) s = anchor // before the first exact start the period begins at that date
        val next = startIn(s.withDayOfMonth(1).plusMonths(1))
        val end = startIn(s.withDayOfMonth(1).plusMonths(1))
        return s.atStartOfDay(zone).toInstant().toEpochMilli() to end.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    companion object {
        fun parseDate(s: String): LocalDate? = try { if (s.isBlank()) null else LocalDate.parse(s.trim()) } catch (_: Exception) { null }
    }
}

/** What the home screen shows. */
data class DataView(val used: Long, val allowance: Long, val warnPct: Int) {
    val left: Long get() = (allowance - used).coerceAtLeast(0)
    /** Share used, 0 to 1 (a full bar when over the allowance). */
    val fraction: Float get() = if (allowance <= 0) 0f else (used.toDouble() / allowance).toFloat().coerceIn(0f, 1f)
    val leftPct: Int get() = if (allowance <= 0) 0 else (left * 100 / allowance).toInt()
    val low: Boolean get() = allowance > 0 && left * 100 <= allowance * warnPct

    val leftText: String get() = amount(left) + " left"
    val bannerText: String get() = "Data low: " + amount(left) + " left of " + amount(allowance)

    companion object {
        /** "4.8 GB" above one gigabyte, "820 MB" below it; nothing left reads "0 GB". */
        fun amount(bytes: Long): String = when {
            bytes <= 0 -> "0 GB"
            bytes >= 1_000_000_000L -> String.format(Locale.UK, "%.1f", bytes / 1e9).removeSuffix(".0") + " GB"
            else -> ((bytes + 500_000) / 1_000_000).toString() + " MB"
        }
    }
}
