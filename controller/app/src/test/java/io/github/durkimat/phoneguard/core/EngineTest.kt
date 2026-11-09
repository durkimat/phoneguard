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

    @Test fun lockdownDefaultsToOpenAndBoostTakesTheLessRestrictive() {
        val lockedSchool = ModeDef("school", "School", false, setOf("a.calc"), true, launcher = "nokia", touch = false, shade = false, kiosk = true)
        val m = modes + ("school" to lockedSchool)
        val c = Config(1, m, "day", listOf(Segment(0, 10_000_000_000_000, "school")), 10_000_000_000_000, null, emptySet(), emptySet(), null)
        assertEquals(Lockdown("nokia", false, false, true), Engine.effective(c, null, 1000).lockdown)
        assertEquals(Lockdown.OPEN, Engine.effective(null, null, 1000).lockdown) // Emergency never locks the phone
        val boosted = Engine.effective(c, Boost("day", 5000), 1000).lockdown // Day boost opens everything
        assertEquals(Lockdown("default", true, true, false), boosted)
        // a boost to another locked mode keeps the lock
        val night = lockedSchool.copy(id = "n2", launcher = "nokia", kiosk = true)
        val c2 = c.copy(modes = m + ("n2" to night))
        assertEquals(Lockdown("nokia", false, false, true), Engine.effective(c2, Boost("n2", 5000), 1000).lockdown)
    }

    @Test fun lockdownChangeCountsAsAChange() {
        val locked = ModeDef("school", "School", false, setOf("a.calc"), false, kiosk = true)
        val open = ModeDef("day", "Day", false, setOf("a.calc"), false)
        val c = Config(1, mapOf("school" to locked, "day" to open), "day", listOf(Segment(0, 5000, "school")), 5000, null, emptySet(), emptySet(), null)
        // same apps, same grey: only the lock differs, and that must still be a change
        assertEquals("day", Engine.nextChange(c, null, 1000)!!.second.modeId)
    }

    @Test fun aForcedModeBeatsTheTimetable() {
        val base = cfg()
        val tue10 = at(2026, 11, 3, 10) // normally School
        assertEquals("school", Engine.scheduledMode(base, tue10))
        val forced = base.copy(override = "night")
        assertEquals("night", Engine.scheduledMode(forced, tue10))
        assertEquals("night", Engine.scheduledMode(forced, at(2026, 11, 8, 12))) // and on a Sunday afternoon
        assertEquals("school", Engine.scheduledMode(base.copy(override = "nonsense"), tue10)) // an unknown mode is ignored
        assertEquals(null, Engine.nextChange(forced, null, tue10)) // nothing changes by itself while forced
        assertEquals("night", Config.parse("""{"modes":{"night":{"name":"N"}},"override":"night"}""").override)
        assertEquals(null, Config.parse("""{"modes":{"night":{}},"override":""}""").override)
    }

    @Test fun parsesJson() {
        val j = """{"version":3,"base":"day","windowEnd":10,
          "modes":{"day":{"name":"Day","allowAll":true},"school":{"allow":["x"],"gray":true}},
          "segments":[{"from":1,"to":5,"mode":"school"}],
          "pattern":{"tz":"Europe/London","days":{"1":[[510,930,"school"]]}},
          "restrictions":["no_factory_reset"]}"""
        val c = Config.parse(j)
        assertEquals(3L, c.version)
        assertEquals(true, c.modes["school"]!!.touch); assertEquals("default", c.modes["school"]!!.launcher)
        val k = Config.parse("""{"modes":{"s":{"launcher":"nokia","touch":false,"shade":false,"kiosk":true}}}""")
        assertEquals(ModeDef("s", "s", false, emptySet(), false, "nokia", false, false, true), k.modes["s"])
        assertTrue(c.modes["school"]!!.gray)
        assertEquals(setOf("no_factory_reset"), c.restrictions)
        assertEquals("school", c.pattern!!.days[1]!![0].mode)
    }

    @Test fun soundFollowsTheModeAndABoostNeverMakesItQuieter() {
        val quiet = modes + mapOf(
            "school" to ModeDef("school", "School", false, setOf("a.calc"), false, sound = "silent"),
            "night" to ModeDef("night", "Night", false, setOf("a.music"), false, sound = "vibrate"),
        )
        val c = cfg().copy(modes = quiet)
        assertEquals("silent", Engine.effective(c, null, at(2026, 11, 3, 10)).sound)
        assertEquals("vibrate", Engine.effective(c, null, at(2026, 11, 3, 22)).sound)
        assertEquals("normal", Engine.effective(c, null, at(2026, 11, 3, 18)).sound) // Day has no sound setting
        val boost = Boost("day", at(2026, 11, 3, 11))
        assertEquals("normal", Engine.effective(c, boost, at(2026, 11, 3, 10)).sound) // the louder of the two wins
        assertEquals("vibrate", Engine.louder("silent", "vibrate")); assertEquals("silent", Engine.louder("silent", "silent"))
    }

    @Test fun soundIsReadFromTheConfig() {
        val c = Config.parse("""{"version":1,"base":"day","windowEnd":0,"modes":{"day":{"name":"Day"},"school":{"name":"School","sound":"silent"}},"segments":[]}""")
        assertEquals("silent", c.modes["school"]!!.sound); assertEquals("normal", c.modes["day"]!!.sound)
    }

    @Test fun incomingCallsFollowTheModeAndABoostOnlyEverOpensThem() {
        val m2 = modes + ("school" to ModeDef("school", "School", false, setOf("a.calc"), false, inbound = false))
        val c = cfg().copy(modes = m2)
        assertFalse(Engine.effective(c, null, at(2026, 11, 3, 10)).inbound)
        assertTrue(Engine.effective(c, null, at(2026, 11, 3, 18)).inbound)
        assertTrue(Engine.effective(c, Boost("day", at(2026, 11, 3, 11)), at(2026, 11, 3, 10)).inbound) // a Day boost lets calls in
        assertFalse(Engine.effective(c, Boost("school", at(2026, 11, 3, 11)), at(2026, 11, 3, 10)).inbound)
    }

    @Test fun whatsAppChecksFollowTheModeAndABoostOnlyEverChecksMoreOften() {
        val m2 = modes + mapOf("school" to ModeDef("school", "School", false, setOf("a.calc"), false, wa = "off"), "day" to ModeDef("day", "Day", true, emptySet(), false, wa = "60"))
        val c = cfg().copy(modes = m2)
        assertEquals("off", Engine.effective(c, null, at(2026, 11, 3, 10)).wa)
        assertEquals("60", Engine.effective(c, null, at(2026, 11, 3, 18)).wa)
        assertEquals("60", Engine.effective(c, Boost("day", at(2026, 11, 3, 11)), at(2026, 11, 3, 10)).wa) // a Day boost lets checks happen
        assertEquals("30", Engine.moreOften("60", "30")); assertEquals("open", Engine.moreOften("off", "open"))
    }

    @Test fun whatsAppSettingsAreReadFromTheConfig() {
        val c = Config.parse("""{"version":1,"base":"day","windowEnd":0,"modes":{"day":{"name":"Day","wa":"30"},"school":{"name":"School"}},"segments":[],"whatsapp":{"all":false,"allowed":["+44 7700 900111","g:Family"],"perMinute":3,"perDay":50}}""")
        assertEquals("30", c.modes["day"]!!.wa); assertEquals("open", c.modes["school"]!!.wa)
        assertEquals(listOf("+44 7700 900111", "g:Family"), c.whatsapp!!.allowed); assertEquals(3, c.whatsapp!!.perMinute); assertEquals(false, c.whatsapp!!.all)
    }

    @Test fun theRealWhatsAppIsUsedWhenAskedForAndThere() {
        assertTrue(Engine.useRealWhatsApp("auto", true)); assertTrue(Engine.useRealWhatsApp("full", true))
        assertFalse(Engine.useRealWhatsApp("simple", true)) // our screens, even though the real app is installed
        assertFalse(Engine.useRealWhatsApp("auto", false)); assertFalse(Engine.useRealWhatsApp("full", false)) // not installed or not visible: ours
        assertFalse(Engine.useRealWhatsApp("auto", true, ourLinked = true)) // Auto keeps our screens once ours is linked
        assertTrue(Engine.useRealWhatsApp("full", true, ourLinked = true)) // Real app is the real app whatever
    }

    @Test fun aBoostOnlyEverOpensTheRealWhatsAppUp() {
        assertEquals("full", Engine.moreFull("simple", "full")); assertEquals("auto", Engine.moreFull("simple", "auto")); assertEquals("simple", Engine.moreFull("simple", "simple"))
        val m2 = modes + ("school" to ModeDef("school", "School", false, setOf("a.calc"), false, waApp = "simple"))
        val c = cfg().copy(modes = m2)
        assertEquals("simple", Engine.effective(c, null, at(2026, 11, 3, 10)).waApp)
        assertEquals("auto", Engine.effective(c, Boost("day", at(2026, 11, 3, 11)), at(2026, 11, 3, 10)).waApp)
        assertEquals("full", Config.parse("""{"version":1,"base":"day","windowEnd":0,"modes":{"day":{"name":"Day","waApp":"full"}},"segments":[]}""").modes["day"]!!.waApp)
    }

    @Test fun radioSettingDefaultsToAnyAndABoostOnlyOpensItUp() {
        assertEquals("any", Config.parse("""{"version":1,"base":"day","windowEnd":0,"modes":{"day":{"name":"Day"}},"segments":[]}""").modes["day"]!!.radio)
        assertEquals("off", Engine.moreRadio("off", "off")); assertEquals("presets", Engine.moreRadio("off", "presets")); assertEquals("any", Engine.moreRadio("any", "off"))
        val m2 = modes + ("school" to ModeDef("school", "School", false, setOf("a.calc"), false, radio = "off"))
        val c = cfg().copy(modes = m2)
        assertEquals("off", Engine.effective(c, null, at(2026, 11, 3, 10)).radio)
        assertEquals("any", Engine.effective(c, Boost("day", at(2026, 11, 3, 11)), at(2026, 11, 3, 10)).radio)
    }

    @Test fun radioStationsComeFromTheParentAndPlainHttpIsRefused() {
        val c = Config.parse("""{"version":1,"base":"day","windowEnd":0,"modes":{"day":{"name":"Day"}},"segments":[],"radioStations":[{"name":"A","url":"https://a/s"},{"name":"B","url":"http://b/s"},{"name":"","url":"https://c"}]}""")
        assertEquals(listOf(RadioStation("A", "https://a/s")), c.radioStations)
        assertNull(Config.parse("""{"version":1,"base":"day","windowEnd":0,"modes":{"day":{"name":"Day"}},"segments":[]}""").radioStations) // none: the phone uses its built-in list
    }

    @Test fun settingARadioPresetNeverLosesAStation() {
        val a = RadioStation("A", "https://a"); val b = RadioStation("B", "https://b"); val c = RadioStation("C", "https://c"); val x = RadioStation("X", "https://x")
        val base = listOf(a, b, c)
        assertEquals(listOf(c, b, a), io.github.durkimat.phoneguard.music.Radio.arrange(base, mapOf(1 to c))) // already in the list: they swap
        assertEquals(listOf(x, b, c, a), io.github.durkimat.phoneguard.music.Radio.arrange(base, mapOf(1 to x))) // from outside: the old one goes to the end
        assertEquals(listOf(a, b, c, x), io.github.durkimat.phoneguard.music.Radio.arrange(base, mapOf(5 to x))) // beyond the end: appended
        assertEquals(base, io.github.durkimat.phoneguard.music.Radio.arrange(base, emptyMap()))
    }
}
