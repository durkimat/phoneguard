package io.github.durkimat.phoneguard.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** A calendar on the phone (a synced account's, or the local one). */
data class CalInfo(val id: Long, val name: String, val account: String, val writable: Boolean)

/**
 * One event, or one occurrence of a repeating one. [start] and [end] are epoch milliseconds; an all-day event runs from UTC midnight
 * of its first day to UTC midnight after its last (how Android stores it). [repeat] is the RRULE text ("" for none), [reminder] minutes
 * before the start (-1 for none).
 */
data class CalEvent(
    val id: Long = 0, val calId: Long = 0, val title: String = "", val start: Long, val end: Long,
    val allDay: Boolean = false, val place: String = "", val notes: String = "",
    val repeat: String = "", val reminder: Int = -1, val writable: Boolean = true,
    /** For one occurrence of a repeating event: when the whole series starts and ends (what an edit changes). */
    val seriesStart: Long = 0, val seriesEnd: Long = 0,
)

/** Where events come from. The launcher never knows which service is behind it (the Android calendar store, a feed, a test double). */
interface CalendarProvider {
    fun calendars(): List<CalInfo>
    /** The occurrences that overlap [from, to) (epoch ms), repeating events already expanded. */
    fun events(from: Long, to: Long): List<CalEvent>
    /** Adds the event (id 0) or changes it; returns its id. */
    fun save(e: CalEvent): Long
    fun delete(id: Long)
}

/** An in-memory calendar for tests and for trying the screens without any account. Repeats are not expanded. */
class FakeCalendar(events: List<CalEvent> = emptyList(), private val zones: List<CalInfo> = listOf(CalInfo(1, "Phone", "Phone Guard", true))) : CalendarProvider {
    private val all = events.toMutableList()
    private var next = (events.maxOfOrNull { it.id } ?: 0) + 1
    override fun calendars() = zones
    override fun events(from: Long, to: Long) = all.filter { it.end > from && it.start < to }.sortedBy { it.start }
    override fun save(e: CalEvent): Long {
        if (e.id == 0L) { val n = e.copy(id = next++); all += n; return n.id }
        val i = all.indexOfFirst { it.id == e.id }
        if (i >= 0) all[i] = e else all += e
        return e.id
    }
    override fun delete(id: Long) { all.removeAll { it.id == id } }
}

/** One line of the agenda: an event on one of the days it covers. */
data class AgendaItem(val day: LocalDate, val event: CalEvent)

object Cal {
    val repeatRules = listOf("", "FREQ=DAILY", "FREQ=WEEKLY", "FREQ=MONTHLY", "FREQ=YEARLY")
    val repeatNames = listOf("Never", "Daily", "Weekly", "Monthly", "Yearly")
    fun repeatName(rule: String): String { val i = repeatRules.indexOf(rule.removePrefix("RRULE:")); return if (i >= 0) repeatNames[i] else "Custom" }
    /** The rule after stepping [dir] through none, daily, weekly, monthly, yearly; a custom rule steps to the start of the list. */
    fun stepRepeat(rule: String, dir: Int): String {
        val i = repeatRules.indexOf(rule.removePrefix("RRULE:"))
        return repeatRules[if (i < 0) 0 else (i + dir).coerceIn(0, repeatRules.size - 1)]
    }
    val reminderMinutes = listOf(-1, 0, 5, 10, 15, 30, 60, 120, 1440)
    fun reminderName(min: Int) = when { min < 0 -> "None"; min == 0 -> "At start"; min < 60 -> "$min min before"; min < 1440 -> "${min / 60} h before"; else -> "1 day before" }
    fun stepReminder(min: Int, dir: Int): Int {
        val i = reminderMinutes.indexOf(min).let { if (it < 0) reminderMinutes.indexOfLast { m -> m <= min }.coerceAtLeast(0) else it }
        return reminderMinutes[(i + dir).coerceIn(0, reminderMinutes.size - 1)]
    }

    fun startDate(e: CalEvent, z: ZoneId): LocalDate = Instant.ofEpochMilli(e.start).atZone(if (e.allDay) ZoneOffset.UTC else z).toLocalDate()
    /** The last day the event touches (an all-day event ends at midnight, so that day is not counted). */
    fun endDate(e: CalEvent, z: ZoneId): LocalDate {
        val s = startDate(e, z)
        val d = if (e.allDay) Instant.ofEpochMilli(e.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                else Instant.ofEpochMilli(maxOf(e.end - 1, e.start)).atZone(z).toLocalDate()
        return if (d.isBefore(s)) s else d
    }
    fun dayStart(d: LocalDate, z: ZoneId): Long = d.atStartOfDay(z).toInstant().toEpochMilli()
    fun utcDay(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun two(n: Int) = if (n < 10) "0$n" else "$n"
    fun clock(ms: Long, z: ZoneId): String = Instant.ofEpochMilli(ms).atZone(z).let { two(it.hour) + ":" + two(it.minute) }
    fun time(e: CalEvent, z: ZoneId): String = if (e.allDay) "All day" else clock(e.start, z)
    fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)
    fun monthName(m: Int) = java.time.Month.of(m).getDisplayName(TextStyle.FULL, Locale.UK)
    /** "Today", "Tomorrow" or "Mon 16 Nov". */
    fun dayLabel(d: LocalDate, today: LocalDate, short: Boolean = false): String = when {
        d == today -> "Today"
        d == today.plusDays(1) -> if (short) "Tmrw" else "Tomorrow"
        else -> dayName(d) + " " + d.dayOfMonth + (if (short) "" else " " + java.time.Month.of(d.monthValue).getDisplayName(TextStyle.SHORT, Locale.UK))
    }
    /** "Fri 13 Nov 09:30 to 10:30", "Fri 13 Nov, all day", "Fri 13 Nov to Sun 15 Nov, all day". */
    fun when_(e: CalEvent, z: ZoneId): String {
        val a = startDate(e, z); val b = endDate(e, z)
        fun d(x: LocalDate) = dayName(x) + " " + x.dayOfMonth + " " + java.time.Month.of(x.monthValue).getDisplayName(TextStyle.SHORT, Locale.UK)
        return if (e.allDay) (if (a == b) d(a) else d(a) + " to " + d(b)) + ", all day"
        else if (a == b) d(a) + " " + clock(e.start, z) + " to " + clock(e.end, z)
        else d(a) + " " + clock(e.start, z) + " to " + d(b) + " " + clock(e.end, z)
    }

    /** The events on or after [from], one entry per day each covers, by day (all-day first) then time, for [days] days. */
    fun agenda(events: List<CalEvent>, from: LocalDate, days: Int, z: ZoneId): List<AgendaItem> {
        val last = from.plusDays(days.toLong() - 1)
        val out = ArrayList<AgendaItem>()
        for (e in events) {
            var d = maxOf(startDate(e, z), from); val end = minOf(endDate(e, z), last)
            while (!d.isAfter(end)) { out += AgendaItem(d, e); d = d.plusDays(1) }
        }
        return out.sortedWith(compareBy<AgendaItem>({ it.day }, { !it.event.allDay }, { it.event.start }, { it.event.title }))
    }
    fun agendaRow(i: AgendaItem, today: LocalDate, z: ZoneId): String =
        dayLabel(i.day, today, true) + (if (i.event.allDay) "  " else " " + clock(i.event.start, z) + " ") + i.event.title.ifBlank { "(no title)" }

    /** The 42 days of a month grid, a week to a row from Monday. */
    fun monthCells(year: Int, month: Int): List<LocalDate> {
        val first = LocalDate.of(year, month, 1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        return (0 until 42).map { start.plusDays(it.toLong()) }
    }
    fun eventsOn(events: List<CalEvent>, d: LocalDate, z: ZoneId) = events.filter { !startDate(it, z).isAfter(d) && !endDate(it, z).isBefore(d) }
        .sortedWith(compareBy<CalEvent>({ !it.allDay }, { it.start }))

    // ---- editing steps (each returns a changed copy; an event keeps its length when it moves)
    fun shiftDays(e: CalEvent, n: Long, z: ZoneId): CalEvent =
        if (e.allDay) e.copy(start = e.start + n * 86_400_000L, end = e.end + n * 86_400_000L)
        else e.copy(start = Instant.ofEpochMilli(e.start).atZone(z).plusDays(n).toInstant().toEpochMilli(), end = Instant.ofEpochMilli(e.end).atZone(z).plusDays(n).toInstant().toEpochMilli())
    fun shiftTime(e: CalEvent, minutes: Long): CalEvent = e.copy(start = e.start + minutes * 60_000L, end = e.end + minutes * 60_000L)
    fun shiftEnd(e: CalEvent, minutes: Long): CalEvent = e.copy(end = maxOf(e.start, e.end + minutes * 60_000L))
    fun shiftLastDay(e: CalEvent, n: Long): CalEvent = e.copy(end = maxOf(e.start + 86_400_000L, e.end + n * 86_400_000L))
    fun toAllDay(e: CalEvent, z: ZoneId): CalEvent = e.copy(allDay = true, start = utcDay(startDate(e, z)), end = utcDay(endDate(e, z).plusDays(1)))
    fun toTimed(e: CalEvent, z: ZoneId): CalEvent {
        val d = startDate(e, z)
        return e.copy(allDay = false, start = d.atTime(9, 0).atZone(z).toInstant().toEpochMilli(), end = d.atTime(10, 0).atZone(z).toInstant().toEpochMilli())
    }
    /** A new event on [day]: an hour long, at the next whole hour if the day is today, otherwise at nine. */
    fun newEvent(day: LocalDate, nowMs: Long, z: ZoneId, calId: Long): CalEvent {
        val now = Instant.ofEpochMilli(nowMs).atZone(z)
        val s = if (day == now.toLocalDate()) now.withMinute(0).withSecond(0).withNano(0).plusHours(1) else day.atTime(9, 0).atZone(z)
        return CalEvent(calId = calId, start = s.toInstant().toEpochMilli(), end = s.plusHours(1).toInstant().toEpochMilli())
    }
}
