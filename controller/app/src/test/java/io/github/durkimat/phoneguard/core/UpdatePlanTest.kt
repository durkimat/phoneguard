package io.github.durkimat.phoneguard.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UpdatePlanTest {
    private val london = ZoneId.of("Europe/London")
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, london).toInstant().toEpochMilli()

    @Test fun nextRunIsTheNextQuietTime() {
        assertEquals(ms(2026, 11, 19, 3), UpdatePlan.nextRun(180, ms(2026, 11, 18, 22), london))
        assertEquals(ms(2026, 11, 18, 3), UpdatePlan.nextRun(180, ms(2026, 11, 18, 1), london))
        assertEquals(ms(2026, 11, 19, 3), UpdatePlan.nextRun(180, ms(2026, 11, 18, 3), london))   // exactly now: tomorrow
        assertEquals(ms(2026, 10, 26, 3), UpdatePlan.nextRun(180, ms(2026, 10, 25, 12), london))   // across the clock change
    }

    @Test fun neverTheStoreOrPhoneGuardOrExcludedOrBusyOrOtherInstalls() {
        val s = AutoUpdate(exclude = setOf("x.skip"))
        assertNull(UpdatePlan.skip("com.spotify.music", UpdatePlan.STORE, "pg", s, false))
        assertNotNull(UpdatePlan.skip(UpdatePlan.STORE, UpdatePlan.STORE, "pg", s, false))
        assertNotNull(UpdatePlan.skip("pg", UpdatePlan.STORE, "pg", s, false))
        assertNotNull(UpdatePlan.skip("x.skip", UpdatePlan.STORE, "pg", s, false))
        assertNotNull(UpdatePlan.skip("a.b", "com.android.vending", "pg", s, false)); assertNotNull(UpdatePlan.skip("a.b", null, "pg", s, false))
        assertEquals("in use right now", UpdatePlan.skip("a.b", UpdatePlan.STORE, "pg", s, true))
    }

    @Test fun mobileDataOnlyWhenAllowed() {
        assertEquals("go", UpdatePlan.network(true, true, AutoUpdate())); assertEquals("no Wi-Fi", UpdatePlan.network(false, true, AutoUpdate()))
        assertEquals("go", UpdatePlan.network(false, true, AutoUpdate(data = true))); assertEquals("no Wi-Fi", UpdatePlan.network(false, false, AutoUpdate(data = true)))
    }

    @Test fun configDefaultsAndParsing() {
        fun cfg(extra: String) = Config.parse("""{"version":1,"base":"d","windowEnd":0,"modes":{"d":{"name":"D"}},"segments":[]$extra}""")
        assertEquals(AutoUpdate(), UpdatePlan.settings(cfg("")))
        val u = UpdatePlan.settings(cfg(""","autoUpdate":{"on":false,"data":true,"atMin":90,"exclude":["a.b"]}"""))
        assertEquals(AutoUpdate(false, true, 90, setOf("a.b")), u)
    }

    @Test fun updateModeDrivesTheStorePage() {
        val d = StoreDrive(update = true)
        assertEquals(StoreDrive.Step.Tap("Update"), d.step("com.aurora.store", listOf("Spotify", "Update", "Uninstall")))
        assertEquals(StoreDrive.Step.UpToDate, StoreDrive(true).step("com.aurora.store", listOf("Spotify", "Open", "Uninstall")))
        assertTrue(StoreDrive(true).step("com.aurora.store", listOf("Spotify", "Install")) is StoreDrive.Step.Fail)
        assertEquals(StoreDrive.Step.Tap("Update"), StoreDrive(true).step("com.google.android.packageinstaller", listOf("Update", "Cancel")))
    }
}
