package io.github.durkimat.phoneguard.alarm

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTest {
    private val london = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, london).toInstant().toEpochMilli()

    @Test fun onceRingsTodayOrTomorrow() {
        val now = at(2026, 11, 18, 6, 0) // a Wednesday
        assertEquals(at(2026, 11, 18, 7, 0), AlarmMath.next(Alarm(1, 7, 0), now, london))
        assertEquals(at(2026, 11, 19, 5, 0), AlarmMath.next(Alarm(1, 5, 0), now, london))
        assertEquals(at(2026, 11, 19, 6, 0), AlarmMath.next(Alarm(1, 6, 0), now, london)) // the minute that is now has gone
        assertNull(AlarmMath.next(Alarm(1, 7, 0, on = false), now, london))
    }

    @Test fun repeatDaysSkipToTheNextChosenDay() {
        val wed = at(2026, 11, 18, 8, 0)
        val monOnly = Alarm(1, 7, 30, days = 1)
        assertEquals(at(2026, 11, 23, 7, 30), AlarmMath.next(monOnly, wed, london))
        assertEquals(at(2026, 11, 19, 7, 30), AlarmMath.next(Alarm(1, 7, 30, AlarmMath.WEEKDAYS), wed, london))
        assertEquals(at(2026, 11, 21, 7, 30), AlarmMath.next(Alarm(1, 7, 30, AlarmMath.WEEKEND), wed, london))
    }

    @Test fun clockChangeKeepsTheWallClockTime() {
        val sat = at(2027, 3, 27, 22, 0) // clocks go forward that night
        assertEquals(at(2027, 3, 28, 7, 0), AlarmMath.next(Alarm(1, 7, 0), sat, london))
        assertEquals(ZonedDateTime.of(2027, 3, 28, 7, 0, 0, 0, london).toInstant().toEpochMilli() - at(2027, 3, 27, 22, 0), 8 * 3600_000L)
    }

    @Test fun labelsAndSavedText() {
        assertEquals("Mon to Fri", AlarmMath.daysLabel(AlarmMath.WEEKDAYS))
        assertEquals("Mon Wed", AlarmMath.daysLabel(0b0000101))
        assertEquals("07:05  Off", AlarmMath.row(Alarm(1, 7, 5, on = false)))
        val l = listOf(Alarm(1, 7, 0, AlarmMath.WEEKDAYS, true, "waltz"), Alarm(3, 22, 15, 0, false, "beep"))
        assertEquals(l, AlarmMath.decode(AlarmMath.encode(l)))
        assertEquals(listOf(Alarm(2, 9, 0, 0, true, Tones.DEFAULT)), AlarmMath.decode("junk;2,9,0,0,1,nope;4,99,0,0,1,x;5,1"))
        val v = listOf(Alarm(1, 7, 0, 31, true, "ode", 6, true), Alarm(2, 8, 0, 0, true, "chime", 0, false))
        assertEquals(v, AlarmMath.decode(AlarmMath.encode(v))) // volume and Gradual are kept per alarm; an old line without them reads as 0 (the shared default)
        assertEquals(0, AlarmMath.decode("3,7,0,0,1,chime")[0].vol)
        assertEquals(0.51f, AlarmGain.level(5), 0.01f); assertEquals(1f, AlarmGain.ramp(90_000, true), 0.001f); assertTrue(AlarmGain.ramp(0, true) < 0.2f); assertEquals(1f, AlarmGain.ramp(0, false), 0f)
        assertEquals(2, AlarmMath.freeId(listOf(Alarm(1, 1, 1))))
        assertEquals(0, AlarmMath.freeId((1..5).map { Alarm(it, 1, 1) }))
        assertEquals("in 7 h 30 min", AlarmMath.until(7 * 3600_000L + 30 * 60_000L - 5000))
    }

    @Test fun timerCountsDownAndPauses() {
        val t = TimerState(length = 90)
        assertEquals(90, t.leftSec(0))
        val run = t.start(1000)
        assertTrue(run.running); assertEquals(60, run.leftSec(31_000)); assertEquals(0, run.leftSec(999_000))
        val p = run.pause(31_000)
        assertTrue(p.paused); assertEquals(60, p.leftSec(500_000))
        assertEquals(60, p.start(500_000).leftSec(500_000))
        assertEquals(TimerState(90), TimerState.decode(TimerState(90).encode()))
        assertEquals(run, TimerState.decode(run.encode()))
    }

    @Test fun stopwatchRunsStopsAndLaps() {
        var s = Stopwatch().toggle(10_000)
        assertEquals(5_000, s.elapsed(15_000))
        s = s.lap(15_000).toggle(16_000)
        assertEquals(6_000, s.elapsed(99_000)); assertEquals(listOf(5_000L), s.laps)
        assertEquals("00:06.0", s.text(99_000))
        assertEquals(9_000, s.toggle(20_000).elapsed(23_000))
    }

    @Test fun everyToneParsesAndRenders() {
        for (t in Tones.all) {
            val n = Tones.parse(t.rtttl)
            assertTrue(t.id, n.size >= 3)
            val pcm = Tones.render(t)
            assertEquals(t.id, Tones.lengthMs(t) * 16.0, pcm.size.toDouble(), 16.0 * n.size) // within a rounding of every note
            assertTrue(t.id, pcm.any { it.toInt() != 0 })
            assertTrue(t.id, Tones.lengthMs(t) in 300..12_000)
        }
        assertEquals(12, Tones.all.size)
        assertEquals("Classic waltz", Tones.byId("waltz").name)
        assertEquals(Tones.DEFAULT, Tones.byId("nope").id)
    }

    @Test fun rtttlNotesAreRight() {
        val n = Tones.parse("x:d=4,o=5,b=120:8a4,p,2e.6,c#,bad")
        assertEquals(4, n.size)
        assertEquals(440.0, n[0].hz, 0.01); assertEquals(250, n[0].ms)
        assertEquals(0.0, n[1].hz, 0.0); assertEquals(500, n[1].ms)
        assertEquals(1318.5, n[2].hz, 0.1); assertEquals(1500, n[2].ms)
        assertEquals(554.4, n[3].hz, 0.1)
        assertNotNull(Tones.byId("elise"))
    }
}
