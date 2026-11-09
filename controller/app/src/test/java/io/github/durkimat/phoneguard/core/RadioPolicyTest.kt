package io.github.durkimat.phoneguard.core

import org.junit.Assert.*
import org.junit.Test

class RadioPolicyTest {
    private val min = 60_000L
    private fun i(
        sleep: Boolean = true, inUse: Boolean = false, off: Long = 10 * min, grace: Long = 5 * min, hold: Boolean = false,
        window: Boolean = false, age: Long = 0, wifiOk: Boolean = false, loc: Boolean = false, data: Boolean = true,
        intent: RadioIntent = RadioIntent(),
    ) = RadioInputs(sleep, inUse, off, grace, hold, window, age, wifiOk, loc, data, intent)

    @Test fun unlockedAndInUseKeepsWhatTheChildChose() {
        assertEquals(RadioWanted(true, true, true), RadioPolicy.decide(i(inUse = true, off = 0)))
        assertEquals(RadioWanted(false, true, true), RadioPolicy.decide(i(inUse = true, off = 0, intent = RadioIntent(wifi = false)))) // switched off stays off
    }

    @Test fun lockedAndUnusedForTheGraceTimeSleepsEverything() {
        assertEquals(RadioWanted(false, false, false), RadioPolicy.decide(i()))
        assertEquals(RadioWanted(true, true, true), RadioPolicy.decide(i(off = 2 * min))) // not yet
        assertEquals(RadioWanted(true, true, true), RadioPolicy.decide(i(sleep = false))) // the mode does not sleep them
    }

    @Test fun obviousUseHoldsTheRadiosOn() {
        assertEquals(RadioWanted(true, true, true), RadioPolicy.decide(i(hold = true)))
    }

    @Test fun aWindowBringsWifiUpThenDataIfWifiDoesNotConnect() {
        val w = i(window = true, age = 1_000)
        assertEquals(RadioWanted(true, false, false), RadioPolicy.decide(w)) // Wi-Fi only, never Bluetooth
        assertEquals(RadioWanted(true, false, true), RadioPolicy.decide(w.copy(windowAgeMs = 25_000))) // no Wi-Fi after 20 s: data too
        assertEquals(RadioWanted(true, false, false), RadioPolicy.decide(w.copy(windowAgeMs = 25_000, wifiConnected = true))) // Wi-Fi came up: no data
        assertEquals(20_000L - 1_000L, RadioPolicy.nextCheckMs(w))
    }

    @Test fun aSwitchedOffRadioStillServesAWindowOnlyWhenThePositionIsReported() {
        val off = RadioIntent(wifi = false, bt = false, data = false)
        assertEquals(RadioWanted(false, false, false), RadioPolicy.decide(i(window = true, age = 30_000, intent = off))) // nothing reported: stays off
        assertEquals(RadioWanted(true, false, false), RadioPolicy.decide(i(window = true, age = 1_000, loc = true, intent = off)))
        assertEquals(RadioWanted(true, false, true), RadioPolicy.decide(i(window = true, age = 30_000, loc = true, intent = off)))
    }

    @Test fun withoutASimDataIsLeftAlone() {
        assertEquals(RadioWanted(false, false, true), RadioPolicy.decide(i(data = false))) // sleeping, but data is not ours to switch: keeps the child's choice
        assertEquals(RadioWanted(true, false, true), RadioPolicy.decide(i(data = false, window = true, age = 30_000)))
    }

    @Test fun windowsDoNothingWhileSomeoneIsUsingThePhone() {
        assertEquals(RadioWanted(true, true, true), RadioPolicy.decide(i(inUse = true, off = 0, window = true)))
    }

    @Test fun nextCheckIsTheEndOfTheGracePeriod() {
        assertEquals(3 * min, RadioPolicy.nextCheckMs(i(off = 2 * min)))
        assertNull(RadioPolicy.nextCheckMs(i()))
    }
}
