package io.github.durkimat.phoneguard.alarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** One alarm. [days] is a bit mask, bit 0 Monday to bit 6 Sunday; 0 means once (it switches itself off after ringing). */
data class Alarm(val id: Int, val hour: Int, val minute: Int, val days: Int = 0, val on: Boolean = true, val tone: String = Tones.DEFAULT,
    /** This alarm's volume step 1..7 (0 = not set: the Clock settings' old default), and whether it climbs over the first minute. */
    val vol: Int = 0, val gradual: Boolean = false)

/** The Clock app's saved settings, shared by the screens, the scheduler and the ring screen. */
data class ClockPrefs(val volume: Int = 5, val crescendo: Boolean = false)

/** The pure rules: when an alarm next rings, how the days read, and the saved text. */
object AlarmMath {
    const val MAX_ALARMS = 5
    const val SNOOZE_MIN = 9
    const val MAX_VOLUME = 7
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000
    const val EVERY = 0b1111111

    fun bit(d: DayOfWeek) = 1 shl (d.value - 1)

    /** The next time (epoch ms) the alarm rings after [now], or null when it is off. */
    fun next(a: Alarm, now: Long, zone: ZoneId): Long? {
        if (!a.on) return null
        val t0 = Instant.ofEpochMilli(now).atZone(zone)
        for (d in 0..7) {
            val date: LocalDate = t0.toLocalDate().plusDays(d.toLong())
            if (a.days != 0 && a.days and bit(date.dayOfWeek) == 0) continue
            val at = ZonedDateTime.of(date, java.time.LocalTime.of(a.hour, a.minute), zone) // built from the wall clock, so a clock change cannot shift it
            if (at.toInstant().toEpochMilli() > now) return at.toInstant().toEpochMilli()
        }
        return null
    }

    fun daysLabel(days: Int): String = when (days) {
        0 -> "Once"
        EVERY -> "Every day"
        WEEKDAYS -> "Mon to Fri"
        WEEKEND -> "Sat, Sun"
        else -> dayNames.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(" ")
    }

    fun clock(h: Int, m: Int) = "%02d:%02d".format(h, m)

    /** One line of the alarm list: "07:30  Mon to Fri", or "07:30  Off". */
    fun row(a: Alarm) = clock(a.hour, a.minute) + "  " + (if (a.on) daysLabel(a.days) else "Off")

    fun nextFrom(list: List<Alarm>, now: Long, zone: ZoneId): Long? = list.mapNotNull { next(it, now, zone) }.minOrNull()

    /** "in 7 h 30 min" for the line under the list, or empty. */
    fun until(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).coerceAtLeast(0)
        val d = min / 1440; val h = min % 1440 / 60; val m = min % 60
        return "in " + listOfNotNull(if (d > 0) "$d d" else null, if (h > 0) "$h h" else null, if (m > 0 || (d == 0L && h == 0L)) "$m min" else null).joinToString(" ")
    }

    fun encode(list: List<Alarm>) = list.joinToString(";") { "${it.id},${it.hour},${it.minute},${it.days},${if (it.on) 1 else 0},${it.tone},${it.vol},${if (it.gradual) 1 else 0}" }

    fun decode(s: String): List<Alarm> = s.split(';').mapNotNull { r ->
        val p = r.split(',')
        if (p.size < 6) return@mapNotNull null
        val id = p[0].toIntOrNull() ?: return@mapNotNull null
        val h = p[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return@mapNotNull null
        val m = p[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return@mapNotNull null
        Alarm(id, h, m, (p[3].toIntOrNull() ?: 0) and EVERY, p[4] == "1", p[5].let { t -> if (Tones.all.any { it.id == t }) t else Tones.DEFAULT },
            (p.getOrNull(6)?.toIntOrNull() ?: 0).coerceIn(0, MAX_VOLUME), p.getOrNull(7) == "1")
    }.take(MAX_ALARMS)

    /** A new alarm's id: the smallest 1..MAX not in use. */
    fun freeId(list: List<Alarm>): Int = (1..MAX_ALARMS).firstOrNull { id -> list.none { it.id == id } } ?: 0

    fun mmss(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)
}

/** A countdown timer: [length] is what was set (seconds); running means it ends at [endAt]; paused keeps [left]. */
data class TimerState(val length: Int = 5 * 60, val endAt: Long = 0, val left: Int = -1) {
    val running get() = endAt > 0
    val paused get() = endAt == 0L && left >= 0
    fun leftSec(now: Long): Int = if (running) ((endAt - now + 999) / 1000).toInt().coerceAtLeast(0) else if (paused) left else length
    fun start(now: Long) = TimerState(length, now + leftSec(now) * 1000L, -1)
    fun pause(now: Long) = if (running) TimerState(length, 0, leftSec(now)) else this
    fun reset() = TimerState(length)
    fun encode() = "$length,$endAt,$left"
    companion object { fun decode(s: String): TimerState { val p = s.split(','); return TimerState(p.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 99 * 3600) ?: 300, p.getOrNull(1)?.toLongOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: -1) } }
}

/** The stopwatch: running means it started at [startedAt] with [before] ms already counted. */
data class Stopwatch(val before: Long = 0, val startedAt: Long = 0, val laps: List<Long> = emptyList()) {
    val running get() = startedAt > 0
    fun elapsed(now: Long) = before + if (running) (now - startedAt).coerceAtLeast(0) else 0
    fun toggle(now: Long) = if (running) copy(before = elapsed(now), startedAt = 0) else copy(startedAt = now)
    fun lap(now: Long) = if (running) copy(laps = (laps + elapsed(now)).takeLast(20)) else this
    fun text(now: Long): String { val e = elapsed(now); return AlarmMath.mmss(e / 1000) + "." + (e % 1000 / 100) }
}
