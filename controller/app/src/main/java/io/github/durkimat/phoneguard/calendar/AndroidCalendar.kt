package io.github.durkimat.phoneguard.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId

/** The phone's own calendar store ([CalendarContract]): whatever a sync app (DAVx5 and the like) puts there, and a local calendar of ours. */
class AndroidCalendar(private val c: Context) : CalendarProvider {
    private val r get() = c.contentResolver
    private val zone: ZoneId get() = ZoneId.systemDefault()

    override fun calendars(): List<CalInfo> = try {
        val out = ArrayList<CalInfo>()
        r.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            CalendarContract.Calendars.VISIBLE + "=1", null, null)?.use {
            while (it.moveToNext()) out += CalInfo(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
        }
        out
    } catch (_: SecurityException) { emptyList() }

    /** Makes the phone's own calendar if no writable one exists, so an event can always be added. Returns the id to use. */
    fun ensureWritable(): Long {
        calendars().firstOrNull { it.writable }?.let { return it.id }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT); put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "phone"); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Phone")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT); put(CalendarContract.Calendars.VISIBLE, 1); put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, zone.id)
        }
        val made = r.insert(uri, v) ?: return 0
        return ContentUris.parseId(made)
    }

    override fun events(from: Long, to: Long): List<CalEvent> = try {
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon(); ContentUris.appendId(b, from); ContentUris.appendId(b, to)
        val level = HashMap<Long, Boolean>(); calendars().forEach { level[it.id] = it.writable }
        val out = ArrayList<CalEvent>()
        val alarm = reminders()
        r.query(b.build(), arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.RRULE, CalendarContract.Instances.START_DAY, CalendarContract.Instances.END_DAY,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DURATION),
            null, null, CalendarContract.Instances.BEGIN + " ASC")?.use {
            while (it.moveToNext()) {
                val allDay = it.getInt(5) == 1
                var s = it.getLong(3); var e = it.getLong(4)
                if (allDay) { // the day numbers are right whatever zone the provider shifted the times into
                    s = Cal.utcDay(julian(it.getInt(9))); e = Cal.utcDay(julian(it.getInt(10)).plusDays(1))
                }
                val rule = it.getString(8) ?: ""
                val ss = if (rule.isBlank() || it.isNull(11)) 0L else it.getLong(11)
                val se = if (ss == 0L) 0L else ss + parseDuration(it.getString(12), allDay)
                out += CalEvent(it.getLong(0), it.getLong(1), it.getString(2) ?: "", s, e, allDay, it.getString(6) ?: "", it.getString(7) ?: "",
                    rule, alarm[it.getLong(0)] ?: -1, level[it.getLong(1)] ?: false, ss, se)
            }
        }
        out
    } catch (_: SecurityException) { emptyList() }

    private fun julian(d: Int): LocalDate = LocalDate.ofEpochDay(d - 2440588L)

    /** The first reminder of every event that has one, in minutes before (the calendar's default counts as 10). */
    private fun reminders(): Map<Long, Int> = try {
        val m = HashMap<Long, Int>()
        r.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES), null, null, null)?.use {
            while (it.moveToNext()) { val id = it.getLong(0); if (id !in m) m[id] = it.getInt(1).let { v -> if (v < 0) 10 else v } }
        }
        m
    } catch (_: Exception) { emptyMap() }

    /** The reminder (minutes before) of one event, or -1. */
    fun reminderOf(eventId: Long): Int = try {
        var m = -1
        r.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES), CalendarContract.Reminders.EVENT_ID + "=?", arrayOf("$eventId"), null)?.use { if (it.moveToFirst()) m = it.getInt(0) }
        m
    } catch (_: SecurityException) { -1 }

    /** The rule and notes of one event (an occurrence list does not carry all of them when the provider trims it). */
    fun detail(eventId: Long): CalEvent? = try {
        var out: CalEvent? = null
        r.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), arrayOf(
            CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.RRULE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val s = it.getLong(2); val allDay = it.getInt(5) == 1
                var e = if (it.isNull(3)) 0L else it.getLong(3)
                if (e == 0L) e = s + parseDuration(it.getString(4), allDay)
                out = CalEvent(eventId, it.getLong(0), it.getString(1) ?: "", s, e, allDay, it.getString(6) ?: "", it.getString(7) ?: "", it.getString(8) ?: "", reminderOf(eventId))
            }
        }
        out
    } catch (_: SecurityException) { null }

    /** An RFC 2445 duration such as P3600S, PT1H, P1D or P2W, in milliseconds (an hour, or a day when all day, if it cannot be read). */
    private fun parseDuration(d: String?, allDay: Boolean): Long {
        val fallback = if (allDay) 86_400_000L else 3_600_000L
        if (d.isNullOrBlank()) return fallback
        val m = Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matchEntire(d.trim()) ?: Regex("^P(\\d+)S$").matchEntire(d.trim())?.let { return it.groupValues[1].toLong() * 1000L } ?: return fallback
        fun n(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val ms = n(1) * 604_800_000L + n(2) * 86_400_000L + n(3) * 3_600_000L + n(4) * 60_000L + n(5) * 1000L
        return if (ms > 0) ms else fallback
    }

    override fun save(e: CalEvent): Long {
        val cal = if (e.calId > 0) e.calId else ensureWritable()
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal)
            put(CalendarContract.Events.TITLE, e.title); put(CalendarContract.Events.EVENT_LOCATION, e.place); put(CalendarContract.Events.DESCRIPTION, e.notes)
            put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else zone.id)
            put(CalendarContract.Events.DTSTART, e.start)
            if (e.repeat.isBlank()) { put(CalendarContract.Events.DTEND, e.end); putNull(CalendarContract.Events.DURATION); putNull(CalendarContract.Events.RRULE) }
            else {
                putNull(CalendarContract.Events.DTEND); put(CalendarContract.Events.RRULE, e.repeat.removePrefix("RRULE:"))
                put(CalendarContract.Events.DURATION, if (e.allDay) "P${((e.end - e.start) / 86_400_000L).coerceAtLeast(1)}D" else "P${((e.end - e.start) / 1000L).coerceAtLeast(60)}S")
            }
            put(CalendarContract.Events.HAS_ALARM, if (e.reminder >= 0) 1 else 0)
        }
        val id = if (e.id == 0L) ContentUris.parseId(r.insert(CalendarContract.Events.CONTENT_URI, v)!!)
                 else { r.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), v, null, null); e.id }
        r.delete(CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.EVENT_ID + "=?", arrayOf("$id"))
        if (e.reminder >= 0) r.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, e.reminder); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        return id
    }

    override fun delete(id: Long) { r.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) }

    companion object { const val ACCOUNT = "Phone Guard" }
}
