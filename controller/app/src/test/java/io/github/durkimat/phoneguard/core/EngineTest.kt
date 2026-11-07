package io.github.durkimat.phoneguard.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private val modes = mapOf(
        "day" to ModeDef("day", "Day", true, emptySet(), false),
        "school" to ModeDef("school", "School", false, setOf("a.calc", "a.clock"), true),
        "night" to ModeDef("night", "Night", false, setOf("a.clock", "a.music"), false),
    )

    /** Tue 3 Nov 2026: school 08:30-15:30, night 21:00 to 07:00 next day, day otherwise. */
    private fun cfg(withPattern: Boolean = true): Config {
        val segs = mutableListOf<Segment>()
        for (day in 3..9) {
            segs += Segment(at(2026, 11, day, 0), at(2026, 11, day, 7), "night")
            if (day in 3..6 || day == 9) segs += Segment(at(2026, 11, day, 8, 30), at(2026, 11, day, 15, 30), "school")
            segs += Segment(at(2026, 11, day, 21), at(2026, 11, day, 23, 59), "night")
        }
        val pat = if (withPattern) Pattern("Europe/London", (1..7).associateWith { dow ->
            buildList {
                add(PatSeg(0, 420, "night"))
                if (dow in 1..5) add(PatSeg(510, 930, "school"))
                add(PatSeg(1260, 1440, "night"))
            }
        }) else null
        return Config(1, modes, "day", segs, at(2026, 11, 10, 0), pat, emptySet(), emptySet(), null)
    }

    @Test fun noConfigIsEmergency() {
        assertEquals("emergency", Engine.scheduledMode(null, 0))
        assertFalse(Engine.effective(null, null, 0).allowed.all)
    }

    @Test fun resolvesSegments() {
        val c = cfg()
        assertEquals("night", Engine.scheduledMode(c, at(2026, 11, 3, 6, 59)))
        assertEquals("day", Engine.scheduledMode(c, at(2026, 11, 3, 7)))
        assertEquals("school", Engine.scheduledMode(c, at(2026, 11, 3, 9)))
        assertEquals("day", Engine.scheduledMode(c, at(2026, 11, 3, 15, 30)))
        assertEquals("night", Engine.scheduledMode(c, at(2026, 11, 3, 22)))
    }

    @Test fun repeatsSameWeekdayAfterWindow() {
        val c = cfg()
        // Tue 17 Nov is two weeks past the window; it must look like a school Tuesday.
        assertEquals("school", Engine.scheduledMode(c, at(2026, 11, 17, 9)))
        // Sat 14 Nov has no school even though the last pushed Saturday... (pattern has none).
        assertEquals("day", Engine.scheduledMode(c, at(2026, 11, 14, 9)))
        assertEquals("night", Engine.scheduledMode(c, at(2026, 11, 14, 22)))
    }

    @Test fun noPatternFallsBackToBase() {
        assertEquals("day", Engine.scheduledMode(cfg(false), at(2026, 11, 17, 9)))
    }

    @Test fun boostIsUnion() {
        val c = cfg()
        val now = at(2026, 11, 3, 9)
        val boost = Boost("day", now + 30 * 60_000)
        val e = Engine.effective(c, boost, now)
        assertTrue(e.allowed.all)
        assertFalse(e.gray) // school is grey, day is not: boost lifts it
        assertFalse(Engine.effective(c, boost, now + 31 * 60_000).allowed.all)
    }

    @Test fun nextChangeSkipsNonEvents() {
        val c = cfg()
        val now = at(2026, 11, 3, 10)
        val (t, e) = Engine.nextChange(c, null, now)!!
        assertEquals(at(2026, 11, 3, 15, 30), t)
        assertEquals("day", e.modeId)
    }

    @Test fun nextChangeIncludesBoostEnd() {
        val c = cfg()
        val now = at(2026, 11, 3, 9)
        val end = now + 20 * 60_000
        val (t, e) = Engine.nextChange(c, Boost("day", end), now)!!
        assertEquals(end, t)
        assertEquals("school", e.modeId)
    }

    @Test fun warnsOnlyWhenAccessIsRemoved() {
        val c = cfg()
        val manageable = setOf("a.calc", "a.clock", "a.music", "a.game")
        // Day to school at 08:30 removes the game: warn.
        assertNotNull(Engine.warningFor(c, null, at(2026, 11, 3, 8, 15), manageable))
        // School to day at 15:30 only adds apps: no warning.
        assertNull(Engine.warningFor(c, null, at(2026, 11, 3, 15, 0), manageable))
        // Boost ending back into school removes access: warn.
        assertNotNull(Engine.warningFor(c, Boost("day", at(2026, 11, 3, 9, 30)), at(2026, 11, 3, 9, 0), manageable))
    }

    @Test fun patternAcrossClockChange() {
        // Clocks go back on Sun 25 Oct 2026. A pattern edge for Mon 26 Oct 08:30 must be 08:30 local.
        val p = Pattern("Europe/London", mapOf(1 to listOf(PatSeg(510, 930, "school"))))
        val c = Config(1, modes, "day", emptyList(), 0, p, emptySet(), emptySet(), null)
        var t = at(2026, 10, 24, 12)
        repeat(5) { t = Engine.nextBoundary(c, null, t) } // lookahead is capped at 24h, so step forward
        assertTrue(t >= at(2026, 10, 26, 8, 30))
        val monday = at(2026, 10, 26, 8, 30)
        assertEquals(monday, generateSequence(at(2026, 10, 24, 12)) { Engine.nextBoundary(c, null, it) }.first { it >= monday })
    }

    @Test fun parsesJson() {
        val j = """{"version":3,"base":"day","windowEnd":10,
          "modes":{"day":{"name":"Day","allowAll":true},"school":{"allow":["x"],"gray":true}},
          "segments":[{"from":1,"to":5,"mode":"school"}],
          "pattern":{"tz":"Europe/London","days":{"1":[[510,930,"school"]]}},
          "restrictions":["no_factory_reset"]}"""
        val c = Config.parse(j)
        assertEquals(3L, c.version)
        assertTrue(c.modes["school"]!!.gray)
        assertEquals(setOf("no_factory_reset"), c.restrictions)
        assertEquals("school", c.pattern!!.days[1]!![0].mode)
    }
}
