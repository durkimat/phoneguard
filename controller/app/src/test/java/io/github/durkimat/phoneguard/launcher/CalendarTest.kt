package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.calendar.Cal
import io.github.durkimat.phoneguard.calendar.CalEvent
import io.github.durkimat.phoneguard.calendar.CalInfo
import io.github.durkimat.phoneguard.calendar.FakeCalendar
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTest {
    private val z = ZoneId.of("Europe/London")
    // Friday 13 November 2026, 10:20 local
    private val now = LocalDate.of(2026, 11, 13).atTime(10, 20).atZone(z).toInstant().toEpochMilli()
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(z).toInstant().toEpochMilli()
    private val d13 = LocalDate.of(2026, 11, 13)

    private fun model(events: List<CalEvent>): LauncherModel = LauncherModel(listOf(MenuItem(CALENDAR_PKG, "Calendar"), MenuItem(SETTINGS_PKG, "Settings"))).also {
        it.zone = z; it.nowMs = { now }
        it.calEvents = events; it.calWritable = listOf(CalInfo(1, "Phone", "Phone Guard", true))
        it.onKey(Key.OK); it.onKey(Key.OK) // menu, then Calendar
    }

    private fun events() = listOf(
        CalEvent(1, 1, "Dentist", at(d13, 15), at(d13, 16), place = "High St"),
        CalEvent(2, 1, "Half term", Cal.utcDay(LocalDate.of(2026, 11, 16)), Cal.utcDay(LocalDate.of(2026, 11, 21)), allDay = true),
        CalEvent(3, 1, "Football", at(d13.plusDays(1), 9), at(d13.plusDays(1), 11)),
        CalEvent(4, 2, "Term dates", at(d13.plusDays(2), 8), at(d13.plusDays(2), 9), writable = false),
    )

    @Test fun agendaIsInOrderAndLabelsDays() {
        val rows = Cal.agenda(events(), d13, 90, z).map { Cal.agendaRow(it, d13, z) }
        assertEquals("Today 15:00 Dentist", rows[0])
        assertTrue(rows[1].startsWith("Tmrw 09:00 Football"))
        assertTrue(rows.contains("Mon 16  Half term"))
        assertEquals(1 + 1 + 5 + 1, rows.size) // dentist, football, five days of half term, term dates
    }

    @Test fun allDayEndsOnTheLastDayNotTheNext() {
        val e = events()[1]
        assertEquals(LocalDate.of(2026, 11, 16), Cal.startDate(e, z))
        assertEquals(LocalDate.of(2026, 11, 20), Cal.endDate(e, z))
    }

    @Test fun monthGridStartsOnAMonday() {
        val cells = Cal.monthCells(2026, 11)
        assertEquals(42, cells.size)
        assertEquals(java.time.DayOfWeek.MONDAY, cells[0].dayOfWeek)
        assertTrue(LocalDate.of(2026, 11, 1) in cells.take(7))
    }

    @Test fun opensOnTheAgendaAndShowsAnEvent() {
        val m = model(events())
        assertEquals(Screen.CALENDAR, m.screen)
        assertTrue(m.rows.first().contains("Dentist"))
        m.onKey(Key.OK)
        assertEquals(Screen.EVENT, m.screen)
        assertTrue(m.pageBody.contains("Dentist") && m.pageBody.contains("High St"))
        m.onKey(Key.SOFT_RIGHT)
        assertEquals(Screen.CALENDAR, m.screen)
    }

    @Test fun monthViewMovesByDaysAndOpensADay() {
        val m = model(events())
        m.onKey(Key.SOFT_LEFT); assertEquals(Screen.CALMENU, m.screen)
        m.onKey(Key.DIGIT, 2); assertEquals(Screen.CALMONTH, m.screen)
        m.onKey(Key.DOWN); assertEquals(d13.plusDays(7), m.calDay)
        m.onKey(Key.POUND); assertEquals(d13.plusDays(7).plusMonths(1), m.calDay)
        m.onKey(Key.OK)
        assertEquals(Screen.CALENDAR, m.screen)
        assertEquals(d13.plusDays(7).plusMonths(1), m.calFrom)
    }

    @Test fun addingAnEventSavesWhatWasTyped() {
        val m = model(events())
        m.onKey(Key.SOFT_LEFT); m.onKey(Key.OK) // Options > Add event
        assertEquals(Screen.FORM, m.screen)
        assertEquals("New event", m.title)
        assertTrue(m.rows[0].startsWith("Title"))
        // Save with no title is refused
        var a = m.onKey(Key.SOFT_LEFT)
        assertEquals(Screen.FORM, m.screen); assertNotNull(m.notice)
        // title through the text entry
        m.onKey(Key.OK); assertEquals(Screen.WRITE, m.screen)
        m.onKey(Key.DIGIT, 2, 0L); m.onKey(Key.SOFT_LEFT) // Done
        assertEquals(Screen.FORM, m.screen)
        assertTrue(m.rows[0].startsWith("Title: "))
        // a later start, then Save
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); m.onKey(Key.RIGHT) // Starts +15 min
        a = m.onKey(Key.SOFT_LEFT)
        assertEquals(Screen.CALENDAR, m.screen)
        val save = a as Action.CalSave
        assertEquals(at(d13, 11, 15), save.event.start)
        assertEquals(at(d13, 12, 15), save.event.end) // it moved as a whole
        assertEquals(1L, save.event.calId)
    }

    @Test fun stepsKeepTheLengthAndAllDayConverts() {
        val e = CalEvent(0, 1, "x", at(d13, 9), at(d13, 10))
        val moved = Cal.shiftDays(e, 7, z)
        assertEquals(at(d13.plusDays(7), 9), moved.start)
        val allDay = Cal.toAllDay(moved, z)
        assertTrue(allDay.allDay)
        assertEquals(LocalDate.of(2026, 11, 20), Cal.startDate(allDay, z))
        assertEquals(LocalDate.of(2026, 11, 20), Cal.endDate(allDay, z))
        val back = Cal.toTimed(allDay, z)
        assertEquals(at(LocalDate.of(2026, 11, 20), 9), back.start)
        assertEquals("Weekly", Cal.repeatName(Cal.stepRepeat("", 2)))
        assertEquals("Custom", Cal.repeatName("FREQ=WEEKLY;BYDAY=MO,WE"))
        assertEquals(15, Cal.stepReminder(10, 1))
    }

    @Test fun readOnlyEventsCannotBeEdited() {
        val m = model(events())
        // the fourth agenda entry is the read-only one; walk to it
        val idx = m.agenda.indexOfFirst { it.event.title == "Term dates" }
        repeat(idx) { m.onKey(Key.DOWN) }
        m.onKey(Key.OK); m.onKey(Key.OK)
        assertEquals(Screen.EVMENU, m.screen)
        assertEquals(listOf("Read only calendar"), m.rows)
        val a = m.onKey(Key.OK)
        assertEquals(Action.None, a); assertEquals(Screen.EVMENU, m.screen)
    }

    @Test fun deletingAsksFirst() {
        val m = model(events())
        m.onKey(Key.OK); m.onKey(Key.OK) // event, Options
        m.onKey(Key.DOWN); m.onKey(Key.OK) // Delete
        assertEquals(listOf("Yes, delete it", "No"), m.rows)
        val no = m.onKey(Key.OK) // the highlight starts on No
        assertEquals(Action.Redraw, no)
        assertEquals(listOf("Edit", "Delete"), m.rows)
        m.onKey(Key.OK); m.onKey(Key.UP) // Delete again, then Yes
        val yes = m.onKey(Key.OK)
        assertEquals(1L, (yes as Action.CalDelete).id)
        assertEquals(Screen.CALENDAR, m.screen)
    }

    @Test fun fakeProviderSavesAndFinds() {
        val p = FakeCalendar()
        val id = p.save(CalEvent(0, 1, "A", 1000, 2000))
        assertEquals(1, p.events(0, 5000).size)
        p.save(CalEvent(id, 1, "B", 1000, 2000))
        assertEquals("B", p.events(0, 5000).single().title)
        p.delete(id); assertTrue(p.events(0, 5000).isEmpty())
    }
}
