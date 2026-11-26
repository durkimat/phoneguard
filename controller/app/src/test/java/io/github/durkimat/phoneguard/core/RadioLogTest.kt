package io.github.durkimat.phoneguard.core

import org.junit.Assert.*
import org.junit.Test

class RadioLogTest {
    private fun inputs(inUse: Boolean = false, off: Long = 120_000, grace: Long = 60_000, hold: Boolean = false, window: Boolean = false) =
        RadioInputs(true, inUse, off, grace, hold, window, 0, false, false, true, RadioIntent())

    private fun why(i: RadioInputs, modeRests: Boolean = true, live: Boolean = false, reportOnly: Boolean = false, held: String? = null, job: String? = null) =
        RadioLog.why(i, modeRests, live, reportOnly, held, job)

    @Test fun theReasonIsTheFirstThingThatAppliesInOrder() {
        assertEquals("in use", why(inputs(inUse = true), reportOnly = true))
        assertEquals("not resting: power saver set to only report", why(inputs(), modeRests = false, reportOnly = true))
        assertEquals("not resting: this mode keeps radios on", why(inputs(), modeRests = false, live = true))
        assertEquals("not resting: live location", why(inputs(window = true), live = true))
        assertEquals("woken for companion check-in", why(inputs(window = true, hold = true), job = "companion check-in"))
        assertEquals("kept on: charging", why(inputs(hold = true), held = "charging"))
        assertEquals("waiting to rest", why(inputs(off = 59_999)))
        assertEquals("resting", why(inputs(off = 60_000)))
    }

    @Test fun aLineIsKeptOnlyWhenSomethingChanged() {
        val a = RadioEntry(1000, true, false, false, -1, "resting")
        assertTrue(a.differsFrom(null)); assertFalse(a.copy(t = 99_000).differsFrom(a))
        assertTrue(a.copy(wifi = true).differsFrom(a)); assertTrue(a.copy(why = "waiting to rest").differsFrom(a))
        assertEquals(a, RadioEntry.fromJson(a.toJson())); assertNull(RadioEntry.fromJson(org.json.JSONArray().put("x")))
    }
}
