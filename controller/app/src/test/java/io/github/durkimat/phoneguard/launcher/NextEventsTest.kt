package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.calendar.AgendaItem
import io.github.durkimat.phoneguard.calendar.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The Now strip's calendar page: timed events first, all-day events by the owner's rule. */
class NextEventsTest {
    private val z: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 19)
    private val now = today.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun timed(d: LocalDate, h: Int, title: String) = AgendaItem(d, CalEvent(title = title, start = at(d, h), end = at(d, h + 1)))
    private fun allDay(d: LocalDate, title: String) = AgendaItem(d, CalEvent(title = title, start = at(d, 0), end = at(d.plusDays(1), 0), allDay = true))
    private fun lines(vararg i: AgendaItem) = LauncherData.pickNext(i.toList(), today, now, z).map { it.prefix + it.title }

    @Test fun twoTimedEventsFillTheLinesAndAllDayOnesAreNotShown() {
        assertEquals(listOf("15:00 Dentist", "20/11 09:00 Football"), lines(allDay(today, "Sam's birthday"), timed(today, 15, "Dentist"), timed(today.plusDays(1), 9, "Football")))
    }

    @Test fun oneTimedPlusAnAllDayEventTodayShowsTimedThenAllDay() {
        assertEquals(listOf("15:00 Dentist", "All day Sam's birthday"), lines(allDay(today, "Sam's birthday"), timed(today, 15, "Dentist")))
    }

    @Test fun oneTimedPlusAnAllDayEventLaterShowsOnlyTheTimedOne() {
        assertEquals(listOf("15:00 Dentist"), lines(timed(today, 15, "Dentist"), allDay(today.plusDays(3), "Holiday")))
    }

    @Test fun noTimedEventsLetAllDayOnesFillTheLinesTodaysFirst() {
        assertEquals(listOf("All day Sam's birthday", "22/11 Bank holiday"), lines(allDay(today.plusDays(3), "Bank holiday"), allDay(today, "Sam's birthday")))
        assertEquals(listOf("21/11 Trip"), lines(allDay(today.plusDays(2), "Trip")))
    }

    @Test fun aMultiDayAllDayEventIsShownOnce() {
        val trip = CalEvent(title = "Trip", start = at(today, 0), end = at(today.plusDays(3), 0), allDay = true)
        assertEquals(listOf("All day Trip"), lines(AgendaItem(today, trip), AgendaItem(today.plusDays(1), trip), AgendaItem(today.plusDays(2), trip)))
    }

    @Test fun anEventThatHasEndedIsLeftOut() {
        assertEquals(listOf("15:00 Dentist"), lines(timed(today, 7, "Breakfast"), timed(today, 15, "Dentist")))
    }
}
