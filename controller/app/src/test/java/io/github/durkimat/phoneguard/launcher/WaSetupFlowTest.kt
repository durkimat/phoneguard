package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The WhatsApp setup screens (SIM method), walked by key. */
class WaSetupFlowTest {
    private fun phone() = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")), contactsApp = "c", messagesApp = "m", dialerApp = "d")
    private fun LauncherModel.key(k: Key, d: Int = -1) = onKey(k, d, 0L)

    private fun LauncherModel.pick(label: String) { var n = 0; while (rows.getOrNull(selected) != label) { key(Key.DOWN); assertTrue("no row $label in $rows", ++n <= rows.size) }; key(Key.OK) }
    private fun LauncherModel.toWhatsAppSettings() { key(Key.OK); key(Key.OK); pick("This phone"); pick("WhatsApp"); assertEquals(Screen.WASET, screen) }

    @Test fun setupIsOnlyOfferedWhenAllowedThenStartsLinksAndEnds() {
        val m = phone(); m.edition = "family"; m.waEnabled = true; m.toWhatsAppSettings()
        assertEquals(listOf("Link account"), m.rows)
        m.waSetupAvail = true
        assertEquals(listOf("Set up WhatsApp", "Link account"), m.rows)
        m.key(Key.OK); assertEquals(Screen.WASETUP, m.screen); assertEquals(listOf("Start setup (30 minutes)"), m.rows)
        assertTrue(m.key(Key.OK) is Action.SetupStart)
        m.waSetupActive = true; m.waSimNumber = "+44 7832 809521"
        assertEquals(listOf("1. WhatsApp is not installed yet", "2. Link this phone to it", "3. Done"), m.rows)
        m.key(Key.OK); assertEquals(Screen.WASETUP, m.screen)                       // not installed: nothing opens
        m.waOfficial = true; assertTrue(m.key(Key.OK) is Action.SetupOpenOfficial)
        m.key(Key.DOWN); m.key(Key.OK); assertEquals(Screen.WAPAIR, m.screen); assertEquals("447832809521", m.waPairNumber)   // the number is prefilled
    }

    @Test fun doneEndsTheWindow() {
        val m = phone(); m.edition = "family"; m.waSetupAvail = true; m.waSetupActive = true; m.toWhatsAppSettings(); m.key(Key.OK); m.key(Key.DOWN); m.key(Key.DOWN)
        assertTrue(m.key(Key.OK) is Action.SetupEnd)
    }
}
