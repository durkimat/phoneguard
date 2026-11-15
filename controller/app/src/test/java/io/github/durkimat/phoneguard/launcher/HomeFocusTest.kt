package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFocusTest {
    private fun model(withSettings: Boolean = true) = LauncherModel(if (withSettings) listOf(MenuItem(SETTINGS_PKG, "Settings")) else listOf(MenuItem("pkg.a", "A")))

    @Test fun upFromTheHomeScreenHighlightsASymbolAndOkOpensWhatIsBehindIt() {
        val m = model()
        assertEquals(-1, m.homeFocus)
        m.onKey(Key.UP)
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
        m.onKey(Key.UP); m.onKey(Key.DOWN); m.onKey(Key.DOWN) // signal, Wi-Fi, Bluetooth
        assertEquals("bt", m.homeFocusTarget()?.kind)
        assertEquals(Action.BtLoad, m.onKey(Key.OK)); assertEquals(Screen.BT, m.screen)
        val b = model(); b.onKey(Key.UP); b.onKey(Key.OK)
        assertEquals(Screen.CONNECTIONS, b.screen)
        val c = model(); repeat(1) { c.onKey(Key.UP) }; repeat(3) { c.onKey(Key.DOWN) }
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
        assertTrue(m.homeTargets().isEmpty())
        m.onKey(Key.UP)
        assertEquals(-1, m.homeFocus); assertNull(m.homeFocusTarget())
    }

    @Test fun weatherAndNotificationSymbolsAreReachable() {
        val m = model(); m.badges = listOf(Badge(BadgeKind.SMS, 2))
        val kinds = m.homeTargets().map { it.kind }
        assertEquals(listOf("signal", "wifi", "bt", "battery", "badge"), kinds)
        repeat(1) { m.onKey(Key.UP) }; repeat(4) { m.onKey(Key.DOWN) }
        assertEquals("badge", m.homeFocusTarget()?.kind)
        m.onKey(Key.OK); assertEquals(Screen.INBOX, m.screen)
    }

    @Test fun theWeatherSitsAboveTheClockAndItsTapAndHighlightFollow() {
        val m = model(); m.weather = WeatherData(14, 2, true, emptyList(), emptyList(), 0L)
        val t = m.homeTargets().first { it.kind == "weather" }
        assertEquals(94f, t.t, 0.01f); assertEquals(162f, t.b, 0.01f) // centre line 128
        assertEquals(Action.Redraw, m.tap(240f, 128f)); assertEquals(Screen.WEATHER, m.screen)
        val old = model(); old.weather = m.weather
        assertEquals(Action.None, old.tap(240f, 392f)) // the old place no longer opens the forecast
    }

    @Test fun inTheMacLookTheWeatherStaysLowerBecauseTheFrameTakesTheTop() {
        val m = model(); m.weather = WeatherData(14, 2, true, emptyList(), emptyList(), 0L); m.theme = "mac"
        val t = m.homeTargets().first { it.kind == "weather" }
        assertEquals(338f, t.t, 0.01f)
        assertEquals(372f, weatherCy(true), 0f); assertEquals(440f, homeSlotY(true), 0f); assertEquals(392f, homeSlotY(false), 0f)
    }
}
