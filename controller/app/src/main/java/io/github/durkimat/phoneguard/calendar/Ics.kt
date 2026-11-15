package io.github.durkimat.phoneguard.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Reads an iCalendar (.ics) feed: events, all-day events, time zones (TZID), repeats (RRULE with daily, weekly, monthly and yearly rules, EXDATE, and
 * single changed occurrences). Anything it does not understand is skipped, never fatal. Pure Kotlin so it can be tested without a phone.
 */
object Ics {
    /** One VEVENT. [start] and [end] are epoch ms (an all-day event: UTC midnight of its first day to UTC midnight after its last). */
    class Ev(
        val uid: String, val title: String, val start: Long, val end: Long, val allDay: Boolean, val place: String, val notes: String,
        val zone: ZoneId, val rrule: String, val exdates: Set<Long>, val recurrenceId: Long?,
    )

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private const val MAX_EVENTS = 20_000
    private const val MAX_STEPS = 20_000

    /** Joins folded lines (a line starting with a space or tab continues the one before). */
    fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split("\n")) {
            val line = raw.removeSuffix("\r")
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + line.substring(1)
            else out += line
        }
        return out
    }

    private fun prop(line: String): Prop? {
        var inQuote = false; var colon = -1
        for (i in line.indices) { val ch = line[i]; if (ch == '"') inQuote = !inQuote else if (ch == ':' && !inQuote) { colon = i; break } }
        if (colon < 1) return null
        val head = line.substring(0, colon).split(';')
        val params = HashMap<String, String>()
        for (p in head.drop(1)) { val eq = p.indexOf('='); if (eq > 0) params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).trim('"') }
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val b = StringBuilder(); var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) { val n = s[i + 1]; b.append(if (n == 'n' || n == 'N') '\n' else n); i += 2 } else { b.append(ch); i++ }
        }
        return b.toString()
    }

    private class When(val ms: Long, val allDay: Boolean, val zone: ZoneId)

    private fun zoneOf(tzid: String?, device: ZoneId): ZoneId =
        if (tzid.isNullOrBlank()) device else try { ZoneId.of(tzid.trim()) } catch (_: Exception) { device } // Windows names and the like: the phone's zone

    private fun time(p: Prop, device: ZoneId): When? = try {
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || (v.length == 8 && !v.contains('T'))) {
            When(Cal.utcDay(LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())), true, ZoneOffset.UTC)
        } else {
            val utc = v.endsWith("Z")
            val d = LocalDateTime.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt(), v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), if (v.length >= 15) v.substring(13, 15).toInt() else 0)
            val z = if (utc) ZoneOffset.UTC else zoneOf(p.params["TZID"], device)
            When(d.atZone(z).toInstant().toEpochMilli(), false, z)
        }
    } catch (_: Exception) { null }

    private fun duration(v: String): Long? {
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matchEntire(v.trim()) ?: return null
        fun n(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val ms = (((n(2) * 7 + n(3)) * 24 + n(4)) * 60 + n(5)) * 60_000L + n(6) * 1000L
        return if (m.groupValues[1] == "-") -ms else ms
    }

    /** The events in an .ics text. [device] is the zone used for times that carry none. */
    fun parse(text: String, device: ZoneId): List<Ev> {
        val out = ArrayList<Ev>()
        var cur: MutableList<Prop>? = null; var depth = 0
        for (line in unfold(text)) {
            val p = prop(line) ?: continue
            if (p.name == "BEGIN") {
                if (p.value.equals("VEVENT", true) && cur == null) { cur = ArrayList(); depth = 0 } else if (cur != null) depth++
            } else if (p.name == "END") {
                if (cur != null && p.value.equals("VEVENT", true) && depth == 0) { event(cur, device)?.let { out += it }; cur = null; if (out.size >= MAX_EVENTS) break }
                else if (cur != null) depth-- // an alarm inside the event
            } else if (cur != null && depth == 0) cur.add(p)
        }
        return out
    }

    private fun event(props: List<Prop>, device: ZoneId): Ev? {
        fun get(n: String) = props.firstOrNull { it.name == n }
        if (get("STATUS")?.value.equals("CANCELLED", true)) return null
        val s = time(get("DTSTART") ?: return null, device) ?: return null
        var end: Long? = get("DTEND")?.let { time(it, device)?.ms }
        if (end == null) end = get("DURATION")?.let { d -> duration(d.value)?.let { s.ms + it } }
        val e = when {
            end != null && end > s.ms -> end
            s.allDay -> s.ms + 86_400_000L
            else -> s.ms
        }
        val ex = HashSet<Long>()
        for (x in props.filter { it.name == "EXDATE" }) for (v in x.value.split(',')) time(Prop("EXDATE", x.params, v), device)?.let { ex += it.ms }
        val rid = get("RECURRENCE-ID")?.let { time(it, device)?.ms }
        return Ev(get("UID")?.value.orEmpty(), unescape(get("SUMMARY")?.value.orEmpty()), s.ms, e, s.allDay, unescape(get("LOCATION")?.value.orEmpty()), unescape(get("DESCRIPTION")?.value.orEmpty()),
            s.zone, get("RRULE")?.value.orEmpty(), ex, rid)
    }

    /** True when the text looks like a calendar at all (so an ordinary web page is refused). */
    fun looksLikeCalendar(text: String) = text.trimStart('﻿', ' ', '\r', '\n').startsWith("BEGIN:VCALENDAR", ignoreCase = true)

    // ---- expanding repeats ----------------------------------------------------------------------

    /** Every occurrence of the events that overlaps [from, to), as phone events on calendar [calId] (read only). */
    fun expand(evs: List<Ev>, from: Long, to: Long, calId: Long): List<CalEvent> {
        val moved = HashSet<Pair<String, Long>>()
        for (e in evs) e.recurrenceId?.let { moved += e.uid to it }
        val out = ArrayList<CalEvent>()
        fun add(e: Ev, s: Long, en: Long) { if (s < to && maxOf(en, s + 1) > from) out += CalEvent(id = idOf(calId, e.uid, s), calId = calId, title = e.title, start = s, end = en, allDay = e.allDay, place = e.place, notes = e.notes, writable = false) }
        for (e in evs) {
            if (e.rrule.isBlank() || e.recurrenceId != null) { add(e, e.start, e.end); continue }
            val len = e.end - e.start
            for (s in occurrences(e, to)) if ((e.uid to s) !in moved && s !in e.exdates) add(e, s, s + len)
        }
        return out.sortedBy { it.start }
    }

    private fun idOf(calId: Long, uid: String, start: Long): Long = -(1L + (("$calId|$uid|$start").hashCode().toLong() and 0x3fffffffL))

    private val DAYS = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

    /** The start times (epoch ms, in order) of a repeating event up to [limit]. */
    private fun occurrences(e: Ev, limit: Long): List<Long> {
        val r = HashMap<String, String>()
        for (part in e.rrule.removePrefix("RRULE:").split(';')) { val eq = part.indexOf('='); if (eq > 0) r[part.substring(0, eq).uppercase()] = part.substring(eq + 1) }
        val freq = r["FREQ"]?.uppercase() ?: return listOf(e.start)
        val interval = (r["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val count = r["COUNT"]?.toIntOrNull()
        val zone = e.zone
        val first = java.time.Instant.ofEpochMilli(e.start).atZone(if (e.allDay) ZoneOffset.UTC else zone)
        val ds = first.toLocalDate(); val tod = first.toLocalTime()
        val until: Long? = r["UNTIL"]?.let { u ->
            time(Prop("UNTIL", if (u.length == 8) mapOf("VALUE" to "DATE") else emptyMap(), u), zone)?.let { w -> if (w.allDay) w.ms + 86_400_000L - 1 else w.ms }
        }
        val byDay = r["BYDAY"]?.split(',')?.mapNotNull { d ->
            val m = Regex("^([+-]?\\d+)?(MO|TU|WE|TH|FR|SA|SU)$").matchEntire(d.trim().uppercase()) ?: return@mapNotNull null
            (m.groupValues[1].toIntOrNull() ?: 0) to DAYS.getValue(m.groupValues[2])
        }.orEmpty()
        val byMonthDay = r["BYMONTHDAY"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val byMonth = r["BYMONTH"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

        fun at(d: LocalDate): Long = if (e.allDay) Cal.utcDay(d) else d.atTime(tod).atZone(zone).toInstant().toEpochMilli()
        fun monthDays(ym: YearMonth): List<LocalDate> {
            val days = ArrayList<LocalDate>()
            if (byMonthDay.isNotEmpty()) for (n in byMonthDay) { val dom = if (n > 0) n else ym.lengthOfMonth() + 1 + n; if (dom in 1..ym.lengthOfMonth()) days += ym.atDay(dom) }
            else if (byDay.isNotEmpty()) for ((ord, dow) in byDay) {
                val all = (1..ym.lengthOfMonth()).map { ym.atDay(it) }.filter { it.dayOfWeek == dow }
                if (ord == 0) days += all else all.getOrNull(if (ord > 0) ord - 1 else all.size + ord)?.let { days += it }
            } else if (ds.dayOfMonth <= ym.lengthOfMonth()) days += ym.atDay(ds.dayOfMonth)
            return days.sorted()
        }

        val out = ArrayList<Long>()
        var emitted = 0
        var k = 0
        while (k < MAX_STEPS) {
            val cands: List<LocalDate> = when (freq) {
                "DAILY" -> listOf(ds.plusDays(k.toLong() * interval))
                "WEEKLY" -> {
                    val ws = ds.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(k.toLong() * interval)
                    (if (byDay.isEmpty()) listOf(ds.dayOfWeek) else byDay.map { it.second }).distinct().sortedBy { it.value }.map { ws.plusDays((it.value - 1).toLong()) }
                }
                "MONTHLY" -> monthDays(YearMonth.from(ds).plusMonths(k.toLong() * interval))
                "YEARLY" -> {
                    val year = ds.year + k * interval
                    (if (byMonth.isEmpty()) listOf(ds.monthValue) else byMonth.sorted()).flatMap { m ->
                        if (byDay.isEmpty() && byMonthDay.isEmpty()) { if (ds.dayOfMonth <= YearMonth.of(year, m).lengthOfMonth()) listOf(LocalDate.of(year, m, ds.dayOfMonth)) else emptyList() }
                        else monthDays(YearMonth.of(year, m))
                    }
                }
                else -> return listOf(e.start)
            }
            for (d in cands) {
                if (d.isBefore(ds)) continue
                val s = at(d)
                if (until != null && s > until) return out
                emitted++
                if (count != null && emitted > count) return out
                if (s >= limit) return out
                out += s
            }
            if (cands.isNotEmpty() && cands.all { it.isAfter(ds.plusYears(200)) }) return out
            if (cands.isEmpty() && k > 4000) return out
            k++
        }
        return out
    }
}
