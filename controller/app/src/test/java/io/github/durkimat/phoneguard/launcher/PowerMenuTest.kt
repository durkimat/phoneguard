package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerMenuTest {
    private fun open() = LauncherModel().also { it.openPowerMenu() }

    @Test fun theMenuOffersPowerOffThenRestart() {
        val m = open()
        assertEquals(Screen.POWERMENU, m.screen); assertEquals(listOf("Power off", "Restart"), m.rows); assertEquals("Phone", m.title)
    }

    @Test fun okChoosesTheHighlightedRowAndDigitsShortcutIt() {
        val m = open(); assertEquals(Action.PowerOff, m.onKey(Key.OK))
        val r = open(); r.onKey(Key.DOWN); assertEquals(Action.PowerRestart, r.onKey(Key.OK))
        assertEquals(Action.PowerOff, open().onKey(Key.DIGIT, 1)); assertEquals(Action.PowerRestart, open().onKey(Key.DIGIT, 2))
    }

    @Test fun backAndEndCloseTheMenu() {
        assertEquals(Action.PowerClose, open().onKey(Key.SOFT_RIGHT)); assertEquals(Action.PowerClose, open().onKey(Key.END))
    }
}
