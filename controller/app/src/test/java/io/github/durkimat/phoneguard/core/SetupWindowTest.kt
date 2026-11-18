package io.github.durkimat.phoneguard.core

import org.junit.Assert.*
import org.junit.Test

class SetupWindowTest {
    private val min = 60_000L
    private fun cfg(setup: String = "sim", allow: SetupAllow? = SetupAllow(10_000 * min, 0, 0)) = WaConfig(setup = setup, setupWindow = allow)

    @Test fun needsSimMethodAndAllowance() {
        assertTrue(SetupWindow.canStart(cfg(), 100 * min))
        assertFalse(SetupWindow.canStart(cfg(setup = "smartphone"), 100 * min))
        assertFalse(SetupWindow.canStart(cfg(allow = null), 100 * min))
        assertFalse(SetupWindow.canStart(cfg(), 10_001 * min)); assertFalse(SetupWindow.canStart(null, 0))
    }

    @Test fun lastsThirtyMinutesPlusWhatWasAddedSinceStart() {
        val w = cfg(allow = SetupAllow(10_000 * min, 15, 2)); val r = SetupWindow.start(w, 100 * min, 5_000)!!
        assertEquals(130 * min, SetupWindow.until(w, r, 101 * min, 6_000))
        val more = cfg(allow = SetupAllow(10_000 * min, 30, 2))   // one "+15" press after it started
        assertEquals(145 * min, SetupWindow.until(more, r, 129 * min, 7_000))
        assertNull(SetupWindow.until(more, r, 145 * min, 7_000))
    }

    @Test fun endsOnEndNowCancelAndReboot() {
        val w = cfg(); val r = SetupWindow.start(w, 100 * min, 5_000)!!
        assertNull(SetupWindow.until(cfg(allow = SetupAllow(10_000 * min, 0, 1)), r, 101 * min, 6_000))   // End now
        assertNull(SetupWindow.until(cfg(allow = null), r, 101 * min, 6_000))                              // permission cancelled
        assertNull(SetupWindow.until(w, r, 101 * min, 100))                                                // rebooted (clock since boot went back)
        assertNull(SetupWindow.until(cfg(setup = "smartphone"), r, 101 * min, 6_000))
    }

    @Test fun windowIsOnlyWhatsAppAndTheStoreWithTouchAndCalls() {
        val cfg = Config.parse("""{"version":1,"base":"night","windowEnd":0,"modes":{"night":{"name":"Night","allowAll":false,"touch":false,"inbound":false,"radiosSleep":true}},"segments":[]}""")
        val e = Engine.effective(cfg, Boost(Engine.SETUP, 1_000), 10)
        assertEquals(Engine.SETUP, e.modeId); assertEquals(Engine.SETUP_APPS, e.allowed.pkgs); assertTrue(e.inbound); assertFalse(e.radiosSleep); assertEquals("full", e.waApp)
        assertTrue(Engine.effective(cfg, Boost(Engine.SETUP, 1_000), 2_000).let { !it.inbound && it.modeId == "night" })   // after the end the mode is back exactly
        assertEquals(1_000L, Engine.nextBoundary(cfg, Boost(Engine.SETUP, 1_000), 10))
    }

    @Test fun simMethodKeepsTheOfficialAppOutOfTheMenusUnlessFull() {
        fun eff(setup: String, waApp: String) = Engine.effective(Config.parse("""{"version":1,"base":"d","windowEnd":0,"modes":{"d":{"name":"D","allowAll":true,"waApp":"$waApp"}},"segments":[],"whatsapp":{"allowed":[],"setup":"$setup"}}"""), null, 10).waApp
        assertEquals("simple", eff("sim", "auto")); assertEquals("auto", eff("smartphone", "auto")); assertEquals("full", eff("sim", "full")); assertEquals("simple", eff("sim", "simple"))
    }
}
