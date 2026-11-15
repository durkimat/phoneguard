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
        assertEquals(listOf("Nothing is locked.", "No parent controls", "it. Changing this", "later erases it.", "Yes, Open phone", "No, go back"), m.rows)
        assertTrue(m.rows.all { it.length <= 20 }) // the confirmation text must not be cut off on the screen
        m.onKey(Key.SOFT_RIGHT) // back out of the question
        assertEquals(listOf("Wi-Fi", "Time and language", "Open phone", "Family phone"), m.rows)
        down(m, 0); m.onKey(Key.OK) // the cursor stays on Open phone: ask again
        assertEquals(Action.ChooseOpen, m.onKey(Key.OK)) // the cursor starts on "Yes"
        assertEquals(Screen.HOME, m.screen)
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
        assertTrue(m.rows.contains("Phone type") && m.rows.contains("Phone options"))
        down(m, m.rows.indexOf("Phone type")); m.onKey(Key.OK)
        assertEquals(Screen.PHONETYPE, m.screen)
        down(m, 2); m.onKey(Key.OK)
        assertEquals(listOf("This erases the", "whole phone:", "contacts, messages", "and settings.", "Erase and restart", "No, go back"), m.rows)
        assertTrue(m.rows.all { it.length <= 20 })
        assertEquals(Action.EraseAndRestart, m.onKey(Key.OK)) // the cursor starts on "Erase and restart"
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
