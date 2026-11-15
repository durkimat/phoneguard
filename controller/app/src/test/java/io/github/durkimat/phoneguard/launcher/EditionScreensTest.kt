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
        assertEquals("1 of 3: Wi-Fi", m.title)
        assertEquals(listOf("Choose Wi-Fi", "Skip"), m.rows)
        m.onKey(Key.END); m.onKey(Key.SOFT_RIGHT)
        assertEquals(Screen.FIRSTRUN, m.screen)
    }

    private fun toPhoneType(m: LauncherModel) { down(m, 1); m.onKey(Key.OK); down(m, 2); m.onKey(Key.OK) } // Skip Wi-Fi, then Next past time and language

    @Test fun theWizardGoesWifiThenTimeAndLanguageThenType() {
        val m = model("first_boot")
        down(m, 1); m.onKey(Key.OK)
        assertEquals("2 of 3: Time, language", m.title)
        assertTrue(m.rows[0].startsWith("Time zone: ") && m.rows[1].startsWith("Language: "))
        assertEquals(listOf("Time zone: ", "Language: ", "Next", "Back").size, m.rows.size)
        down(m, 3); m.onKey(Key.OK) // Back
        assertEquals("1 of 3: Wi-Fi", m.title)
        down(m, 1); m.onKey(Key.OK); down(m, 2); m.onKey(Key.OK)
        assertEquals("3 of 3: Phone type", m.title)
        assertEquals(listOf("Open phone", "Family phone", "Back"), m.rows)
        m.onKey(Key.SOFT_RIGHT) // the right key goes back a step
        assertEquals("2 of 3: Time, language", m.title)
    }

    @Test fun timeZoneAndLanguageAreChosenInPhoneGuardLists() {
        val m = model("first_boot"); m.tzId = "Europe/London"; m.langTag = "en-GB"
        down(m, 1); m.onKey(Key.OK)
        m.onKey(Key.OK)
        assertEquals(Screen.TIMEZONE, m.screen)
        assertEquals(Regional.zones.indexOfFirst { it.id == "Europe/London" }, m.selected)
        down(m, 1)
        assertEquals(Action.SetTimeZone("Europe/Dublin"), m.onKey(Key.OK))
        assertEquals(Screen.FIRSTRUN, m.screen)
        assertEquals("Time zone: Dublin", m.rows[0])
        down(m, 1); m.onKey(Key.OK)
        assertEquals(Screen.LANGUAGE, m.screen)
        down(m, 1)
        assertEquals(Action.SetLanguage("en-US"), m.onKey(Key.OK))
        assertEquals("Language: English (US)", m.rows[1])
    }

    @Test fun openPhoneNeedsConfirmationThenChoosesOnce() {
        val m = model("first_boot")
        toPhoneType(m); assertEquals(Action.Redraw, m.onKey(Key.OK))
        assertEquals(listOf("Nothing is locked.", "No parent controls", "it. Changing this", "later erases it.", "Yes, Open phone", "No, go back"), m.rows)
        assertTrue(m.rows.all { it.length <= 20 }) // the confirmation text must not be cut off on the screen
        m.onKey(Key.SOFT_RIGHT) // back out of the question
        assertEquals(listOf("Open phone", "Family phone", "Back"), m.rows)
        m.onKey(Key.OK) // ask again
        assertEquals(5, m.selected) // the cursor starts on "No, go back"
        m.onKey(Key.DIGIT.let { Key.UP }); // one step up is "Yes, Open phone"
        assertEquals(Action.ChooseOpen, m.onKey(Key.OK))
        assertEquals(Screen.HOME, m.screen)
    }

    @Test fun familyPhoneStartsThePairingScreen() {
        val m = model("first_boot"); toPhoneType(m); down(m, 1)
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
        assertEquals(5, m.selected) // the cursor starts on "No, go back": one stray Enter erases nothing
        assertEquals(Action.None.javaClass, m.onKey(Key.DIGIT, 5).javaClass) // a number key does not choose here (5 is Erase)
        assertEquals(Screen.PHONETYPE, m.screen)
        m.onKey(Key.UP)
        assertEquals(Action.EraseAndRestart, m.onKey(Key.OK))
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
