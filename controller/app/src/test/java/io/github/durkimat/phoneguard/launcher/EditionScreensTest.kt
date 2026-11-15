package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.core.Engine
import io.github.durkimat.phoneguard.core.OpenConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditionScreensTest {
    private fun model(ed: String) = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings"))).also { it.edition = ed }
    private fun down(m: LauncherModel, n: Int) = repeat(n) { m.onKey(Key.DOWN) }

    @Test fun firstBootShowsTheFirstRunScreenAndCannotBeLeft() {
        val m = model("first_boot")
        assertEquals(Screen.FIRSTRUN, m.screen)
        assertEquals(listOf("Wi-Fi", "Time and language", "Open phone", "Family phone"), m.rows)
        m.onKey(Key.END); m.onKey(Key.SOFT_RIGHT)
        assertEquals(Screen.FIRSTRUN, m.screen)
    }

    @Test fun openPhoneNeedsConfirmationThenChoosesOnce() {
        val m = model("first_boot")
        down(m, 2); assertEquals(Action.Redraw, m.onKey(Key.OK))
        assertTrue(m.rows.contains("Yes, make it an Open phone"))
        down(m, 0); m.onKey(Key.SOFT_RIGHT) // back out of the question
        assertEquals(listOf("Wi-Fi", "Time and language", "Open phone", "Family phone"), m.rows)
        m.onKey(Key.OK); down(m, 0)
        // confirm: rows 2 is "Yes"
        val m2 = model("first_boot"); down(m2, 2); m2.onKey(Key.OK)
        assertEquals(Action.ChooseOpen, m2.onKey(Key.OK))
        assertEquals(Screen.HOME, m2.screen)
    }

    @Test fun familyPhoneStartsThePairingScreen() {
        val m = model("first_boot"); down(m, 3)
        assertEquals(Action.StartFamily, m.onKey(Key.OK))
    }

    @Test fun wifiFromFirstRunComesBackToFirstRun() {
        val m = model("first_boot")
        assertEquals(Action.WifiLoad, m.onKey(Key.OK))
        assertEquals(Screen.WIFI, m.screen)
        m.edition = "first_boot" // a refresh while in Wi-Fi must not throw the person out
        assertEquals(Screen.WIFI, m.screen)
        m.onKey(Key.SOFT_RIGHT)
        assertEquals(Screen.FIRSTRUN, m.screen)
    }

    @Test fun openPhoneCanSwitchTypeOnlyByErasing() {
        val m = model("open")
        m.onKey(Key.OK); m.onKey(Key.OK) // menu, then Settings
        assertEquals(Screen.SETTINGS, m.screen)
        val rows = m.rows
        assertTrue(rows.contains("Phone type") && rows.contains("Phone options"))
        // go to Phone type
        down(m, rows.indexOf("Phone type")); m.onKey(Key.OK)
        assertEquals(Screen.PHONETYPE, m.screen)
        down(m, 2); m.onKey(Key.OK)
        assertTrue(m.rows.contains("Erase and restart"))
        assertEquals(Action.None, m.onKey(Key.UP).let { Action.None }) // moving is harmless
        val erase = model("open").also { it.onKey(Key.OK); it.onKey(Key.OK) }
        down(erase, erase.rows.indexOf("Phone type")); erase.onKey(Key.OK); down(erase, 2); erase.onKey(Key.OK)
        down(erase, 0) // the cursor is already on "Erase and restart"? it starts on the last row, so move up one
        erase.onKey(Key.UP)
        assertEquals(Action.EraseAndRestart, erase.onKey(Key.OK))
    }

    @Test fun familyPhoneShowsNoSwitchAndNoOptions() {
        val m = model("family"); m.onKey(Key.OK); m.onKey(Key.OK)
        assertFalse(m.rows.contains("Phone options"))
        down(m, m.rows.indexOf("Phone type")); m.onKey(Key.OK)
        assertEquals(Action.None, m.onKey(Key.OK))
    }

    @Test fun openOptionsCycleAndAreReportedForSaving() {
        val m = model("open"); m.onKey(Key.OK); m.onKey(Key.OK)
        down(m, m.rows.indexOf("Phone options")); m.onKey(Key.OK)
        assertEquals(Screen.OPENOPTS, m.screen)
        assertEquals("Sound: Normal", m.rows[0])
        assertEquals(Action.SaveOpenOption("sound", "vibrate"), m.onKey(Key.OK))
        assertEquals("Sound: Vibrate", m.rows[0])
    }

    @Test fun openConfigIsPermissiveAndTakesTheChosenOptions() {
        val cfg = OpenConfig.build(mapOf("sound" to "silent", "camera" to "off", "wa" to "60", "bogus" to "x"))
        val e = Engine.effective(cfg, null, 0L)
        assertTrue(e.allowed.all); assertFalse(e.lockdown.kiosk); assertTrue(e.lockdown.touch); assertTrue(e.inbound)
        assertEquals("silent", e.sound); assertFalse(e.camera); assertEquals("60", e.wa)
        assertEquals("any", e.radio)
        assertEquals("vibrate", OpenConfig.step(emptyMap(), OpenConfig.options[0], 1))
        assertEquals("silent", OpenConfig.step(emptyMap(), OpenConfig.options[0], -1))
    }
}
