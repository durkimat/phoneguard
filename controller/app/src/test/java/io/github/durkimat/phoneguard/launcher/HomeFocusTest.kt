package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFocusTest {
    private fun model(withSettings: Boolean = true) = LauncherModel(if (withSettings) listOf(MenuItem(SETTINGS_PKG, "Settings")) else listOf(MenuItem("pkg.a", "A")))

    /** The first Up lands on the clock; more Ups climb to the first symbol at the top. */
    private fun top(m: LauncherModel) { m.onKey(Key.UP); while (m.homeFocus > 0) m.onKey(Key.UP) }

    @Test fun upFromTheHomeScreenHighlightsASymbolAndOkOpensWhatIsBehindIt() {
        val m = model()
        assertEquals(-1, m.homeFocus)
        m.onKey(Key.UP)
        assertEquals("clock", m.homeFocusTarget()?.kind)
        top(m)
        assertEquals(0, m.homeFocus)
        assertEquals("signal", m.homeFocusTarget()?.kind)
        m.onKey(Key.DOWN) // Wi-Fi
        assertEquals("wifi", m.homeFocusTarget()?.kind)
        assertEquals(Action.WifiLoad, m.onKey(Key.OK))
        assertEquals(Screen.WIFI, m.screen)
        assertEquals(-1, m.homeFocus)
    }

    @Test fun bluetoothBatteryAndSignalGoToTheirScreens() {
        val m = model(); m.dataShown = true
        top(m); m.onKey(Key.DOWN); m.onKey(Key.DOWN) // signal, Wi-Fi, Bluetooth
        assertEquals("bt", m.homeFocusTarget()?.kind)
        assertEquals(Action.BtLoad, m.onKey(Key.OK)); assertEquals(Screen.BT, m.screen)
        val b = model(); top(b); b.onKey(Key.OK)
        assertEquals(Screen.CONNECTIONS, b.screen)
        val c = model(); top(c); repeat(3) { c.onKey(Key.DOWN) }
        assertEquals("battery", c.homeFocusTarget()?.kind)
        c.onKey(Key.OK); assertEquals(Screen.BATTERY, c.screen)
    }

    @Test fun downPastTheLastSymbolAndBackLeaveTheHighlight() {
        val m = model()
        m.onKey(Key.UP); repeat(5) { m.onKey(Key.DOWN) }
        assertEquals(-1, m.homeFocus)
        m.onKey(Key.UP); m.onKey(Key.SOFT_RIGHT)
        assertEquals(-1, m.homeFocus); assertEquals(Screen.HOME, m.screen)
        m.onKey(Key.UP); m.onKey(Key.END)
        assertEquals(-1, m.homeFocus)
    }

    @Test fun otherKeysStillDoWhatTheyDid() {
        val m = model(); m.onKey(Key.UP)
        m.onKey(Key.DIGIT, 5, 0)
        assertEquals(Screen.DIAL, m.screen); assertEquals(-1, m.homeFocus)
        val n = model(); assertEquals(Screen.MENU.also { n.onKey(Key.OK) }, n.screen) // OK without a highlight still opens the menu
    }

    @Test fun symbolsTheModeHidesCannotBeChosen() {
        val m = model(withSettings = false); m.wifiShown = false; m.btShown = false
        assertEquals(listOf("clock"), m.homeTargets().map { it.kind }) // the clock is always there
        m.onKey(Key.UP)
        assertEquals("clock", m.homeFocusTarget()?.kind)
    }

    @Test fun weatherAndNotificationSymbolsAreReachable() {
        val m = model(); m.badges = listOf(Badge(BadgeKind.SMS, 2))
        val kinds = m.homeTargets().map { it.kind }
        assertEquals(listOf("signal", "wifi", "bt", "battery", "clock", "badge"), kinds)
        m.onKey(Key.UP); m.onKey(Key.DOWN)
        assertEquals("badge", m.homeFocusTarget()?.kind)
        m.onKey(Key.OK); assertEquals(Screen.INBOX, m.screen)
    }

    @Test fun theWeatherSitsAboveTheClockAndItsTapAndHighlightFollow() {
        val m = model(); m.weather = WeatherData(14, 2, true, emptyList(), emptyList(), 0L)
        val t = m.homeTargets().first { it.kind == "weather" }
        assertEquals(96f, t.t, 0.01f); assertEquals(164f, t.b, 0.01f) // centre line 130
        assertEquals(Action.Redraw, m.tap(240f, 130f)); assertEquals(Screen.WEATHER, m.screen)
        val old = model(); old.weather = m.weather
        assertEquals(Action.None, old.tap(240f, 392f)) // the old place no longer opens the forecast
    }

    @Test fun inTheMacLookTheWeatherIsAboveTheClockAndTheClockMovesDown() {
        val m = model(); m.weather = WeatherData(14, 2, true, emptyList(), emptyList(), 0L); m.theme = "mac"
        val t = m.homeTargets().first { it.kind == "weather" }
        assertEquals(140f, t.t, 0.01f) // cy 174 - 34
        assertEquals(174f, weatherCy(true), 0f); assertEquals(296f, clockY(true), 0f); assertEquals(392f, homeSlotY(true), 0f); assertEquals(392f, homeSlotY(false), 0f)
    }
}
