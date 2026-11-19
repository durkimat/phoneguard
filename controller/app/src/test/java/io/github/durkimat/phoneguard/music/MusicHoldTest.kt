package io.github.durkimat.phoneguard.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicHoldTest {
    @Test fun noReasonAsksNothing() { assertEquals(MusicHold.Radio.NONE, MusicHold.wanted(emptySet())) }
    @Test fun anAlarmDucksTheRadio() { assertEquals(MusicHold.Radio.DUCKED, MusicHold.wanted(setOf(MusicHold.ALARM))) }
    @Test fun aCallPausesIt() { assertEquals(MusicHold.Radio.PAUSED, MusicHold.wanted(setOf(MusicHold.CALL))) }
    @Test fun aCallWinsOverAnAlarm() { assertEquals(MusicHold.Radio.PAUSED, MusicHold.wanted(setOf(MusicHold.ALARM, MusicHold.CALL))) }
}
