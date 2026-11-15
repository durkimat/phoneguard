package io.github.durkimat.phoneguard.calendar

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsTest {
    private val z = ZoneId.of("Europe/London")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = LocalDate.of(y, m, d).atTime(h, min).atZone(z).toInstant().toEpochMilli()
    private fun cal(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"
    private fun run(text: String, from: Long, to: Long) = Ics.expand(Ics.parse(text, z), from, to, -1)

    @Test fun plainTimedAndAllDayEvents() {
        val t = cal("UID:a\r\nSUMMARY:Dentist\r\nDTSTART;TZID=Europe/London:20261113T150000\r\nDTEND;TZID=Europe/London:20261113T160000\r\nLOCATION:High St\\, Town",
            "UID:b\r\nSUMMARY:Half term\r\nDTSTART;VALUE=DATE:20261116\r\nDTEND;VALUE=DATE:20261121")
        val e = run(t, ms(2026, 11, 1), ms(2026, 12, 1))
        assertEquals(2, e.size)
        assertEquals("Dentist", e[0].title); assertEquals(ms(2026, 11, 13, 15), e[0].start); assertEquals("High St, Town", e[0].place); assertFalse(e[0].writable)
        assertTrue(e[1].allDay); assertEquals(Cal.utcDay(LocalDate.of(2026, 11, 16)), e[1].start); assertEquals(Cal.utcDay(LocalDate.of(2026, 11, 21)), e[1].end)
    }

    @Test fun utcTimesAndFoldedLines() {
        val e = run(cal("UID:a\r\nSUMMARY:Very long\r\n  title\r\nDTSTART:20261113T150000Z\r\nDURATION:PT90M"), ms(2026, 11, 13), ms(2026, 11, 14))
        assertEquals("Very long title", e[0].title); assertEquals(ms(2026, 11, 13, 15), e[0].start); assertEquals(90 * 60_000L, e[0].end - e[0].start)
    }

    @Test fun weeklyRepeatWithCountAndExdate() {
        val t = cal("UID:w\r\nSUMMARY:Swimming\r\nDTSTART;TZID=Europe/London:20261102T170000\r\nDTEND;TZID=Europe/London:20261102T180000\r\nRRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=5\r\nEXDATE;TZID=Europe/London:20261109T170000")
        val days = run(t, ms(2026, 11, 1), ms(2027, 1, 1)).map { java.time.Instant.ofEpochMilli(it.start).atZone(z).toLocalDate().toString() }
        assertEquals(listOf("2026-11-02", "2026-11-04", "2026-11-11", "2026-11-16"), days) // 5 counted, the 9th removed
    }

    @Test fun repeatsKeepLocalTimeAcrossTheClockChange() {
        val t = cal("UID:d\r\nSUMMARY:School\r\nDTSTART;TZID=Europe/London:20261023T083000\r\nDTEND;TZID=Europe/London:20261023T153000\r\nRRULE:FREQ=DAILY;UNTIL=20261028T000000Z")
        val e = run(t, ms(2026, 10, 20), ms(2026, 11, 5))
        assertEquals(listOf(ms(2026, 10, 23, 8, 30), ms(2026, 10, 24, 8, 30), ms(2026, 10, 25, 8, 30), ms(2026, 10, 26, 8, 30), ms(2026, 10, 27, 8, 30)), e.map { it.start })
    }

    @Test fun monthlyNthWeekdayAndYearly() {
        val m = run(cal("UID:m\r\nSUMMARY:Club\r\nDTSTART;TZID=Europe/London:20261105T190000\r\nDTEND;TZID=Europe/London:20261105T200000\r\nRRULE:FREQ=MONTHLY;BYDAY=1TH"), ms(2026, 11, 1), ms(2027, 3, 1))
        assertEquals(listOf(ms(2026, 11, 5, 19), ms(2026, 12, 3, 19), ms(2027, 1, 7, 19), ms(2027, 2, 4, 19)), m.map { it.start })
        val y = run(cal("UID:y\r\nSUMMARY:Birthday\r\nDTSTART;VALUE=DATE:20200229\r\nRRULE:FREQ=YEARLY"), ms(2026, 1, 1), ms(2029, 1, 1))
        assertEquals(1, y.size); assertEquals(Cal.utcDay(LocalDate.of(2028, 2, 29)), y[0].start) // only leap years
    }

    @Test fun movedOccurrenceReplacesTheOriginal() {
        val t = cal("UID:r\r\nSUMMARY:Choir\r\nDTSTART;TZID=Europe/London:20261109T170000\r\nDTEND;TZID=Europe/London:20261109T180000\r\nRRULE:FREQ=WEEKLY;COUNT=3",
            "UID:r\r\nSUMMARY:Choir (moved)\r\nRECURRENCE-ID;TZID=Europe/London:20261116T170000\r\nDTSTART;TZID=Europe/London:20261117T180000\r\nDTEND;TZID=Europe/London:20261117T190000")
        val e = run(t, ms(2026, 11, 1), ms(2026, 12, 1))
        assertEquals(listOf("Choir", "Choir (moved)", "Choir"), e.map { it.title }); assertEquals(ms(2026, 11, 17, 18), e[1].start)
    }

    @Test fun cancelledAndBrokenEventsAreSkippedAndPagesRefused() {
        val e = Ics.parse(cal("UID:c\r\nSTATUS:CANCELLED\r\nSUMMARY:No\r\nDTSTART:20261113T150000Z", "UID:x\r\nSUMMARY:Bad\r\nDTSTART:garbage", "UID:ok\r\nSUMMARY:Yes\r\nDTSTART:20261113T150000Z"), z)
        assertEquals(listOf("Yes"), e.map { it.title })
        assertTrue(Ics.looksLikeCalendar("﻿BEGIN:VCALENDAR\r\n")); assertFalse(Ics.looksLikeCalendar("<html>"))
    }
}
