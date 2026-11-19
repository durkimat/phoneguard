package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home is six zones (1 status, 2 data, 3 weather, 4 time, 5 now, 6 activity): Up and Down move between them, Left and Right inside one. */
class HomeFocusTest {
    private fun model(withSettings: Boolean = true) = LauncherModel(if (withSettings) listOf(MenuItem(SETTINGS_PKG, "Settings")) else listOf(MenuItem("pkg.a", "A")))
    private fun kind(m: LauncherModel) = m.homeFocusTarget()?.id

    @Test fun firstDownEntersTheTopZoneOnItsMiddleItemAndOkOpensIt() {
        val m = model()
        assertEquals(-1, m.homeFocus)
        m.onKey(Key.DOWN)
        assertEquals("wifi", kind(m)) // signal, wifi, bluetooth, battery: the left of the two middle ones
        assertEquals(Action.WifiLoad, m.onKey(Key.OK))
        assertEquals(Screen.WIFI, m.screen); assertEquals(-1, m.homeFocus)
    }

    @Test fun firstUpEntersTheLastZoneThatHasSomething() {
        val m = model()
        m.onKey(Key.UP); assertEquals("clock", kind(m)) // nothing below the clock yet
        val b = model(); b.badges = listOf(Badge(BadgeKind.SMS, 2), Badge(BadgeKind.WHATSAPP, 1), Badge(BadgeKind.MISSED, 1))
        b.onKey(Key.UP); assertEquals("badge:WHATSAPP", kind(b)) // the middle of three
        assertEquals(Action.Redraw, b.onKey(Key.OK)); assertEquals(Screen.WHATSAPP, b.screen)
    }

    @Test fun leftAndRightMoveInsideTheZoneAndStopAtTheEnds() {
        val m = model(); m.onKey(Key.DOWN) // wifi
        m.onKey(Key.LEFT); assertEquals("signal", kind(m))
        m.onKey(Key.LEFT); assertEquals("signal", kind(m)) // no wrapping, and it never leaves the zone
        repeat(5) { m.onKey(Key.RIGHT) }; assertEquals("battery", kind(m))
        assertEquals(Screen.HOME, m.screen)
    }

    @Test fun upAndDownSkipEmptyZonesAndPastTheEndsClearTheHighlight() {
        val m = model(); m.weather = WeatherData(14, 2, true, emptyList(), emptyList(), 0L)
        m.badges = listOf(Badge(BadgeKind.SMS, 2))
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); assertEquals("weather", kind(m)) // no data zone
        m.onKey(Key.DOWN); assertEquals("clock", kind(m))
        m.onKey(Key.DOWN); assertEquals("badge:SMS", kind(m)) // no zone 5 without pages
        m.onKey(Key.DOWN); assertEquals(-1, m.homeFocus)
        m.onKey(Key.UP); assertEquals("badge:SMS", kind(m))
        m.onKey(Key.UP); m.onKey(Key.UP); m.onKey(Key.UP); assertEquals("wifi", kind(m)) // clock, weather, then the status zone on its middle item
        m.onKey(Key.UP); assertEquals(-1, m.homeFocus)
    }

    @Test fun keysWithNothingChosenAreAsBefore() {
        val m = model(); m.onKey(Key.RIGHT); assertEquals(Screen.CONTACTS, m.screen); m.onKey(Key.END)
        val n = model(); n.onKey(Key.LEFT); assertEquals(Screen.MENU, n.screen)
        val o = model(); o.onKey(Key.DOWN); o.onKey(Key.DIGIT, 5, 0); assertEquals(Screen.DIAL, o.screen); assertEquals(-1, o.homeFocus)
    }

    @Test fun backAndEndLeaveTheHighlight() {
        val m = model(); m.onKey(Key.DOWN); m.onKey(Key.SOFT_RIGHT); assertEquals(-1, m.homeFocus); assertEquals(Screen.HOME, m.screen)
        m.onKey(Key.DOWN); m.onKey(Key.END); assertEquals(-1, m.homeFocus)
    }

    @Test fun symbolsTheModeHidesCannotBeChosen() {
        val m = model(withSettings = false); m.wifiShown = false; m.btShown = false
        assertEquals(listOf("clock"), m.homeTargets().map { it.kind }) // the clock is always there
        m.onKey(Key.DOWN); assertEquals("clock", kind(m))
    }

    @Test fun theBatteryBoxWrapsOnlyTheBatteryIcon() {
        val b = model().homeTargets().first { it.kind == "battery" } // the icon is drawn from x 411 to 467, y 10 to 36
        assertEquals(404f, b.l, 0f); assertEquals(474f, b.r, 0f); assertEquals(4f, b.t, 0f); assertEquals(42f, b.b, 0f)
    }

    @Test fun theHighlightStaysOnTheSameSymbolWhenOthersComeAndGo() {
        val m = model(); m.badges = listOf(Badge(BadgeKind.SMS, 2), Badge(BadgeKind.WHATSAPP, 1), Badge(BadgeKind.MISSED, 1))
        m.onKey(Key.UP); m.onKey(Key.RIGHT); assertEquals("badge:MISSED", kind(m))
        m.badges = listOf(Badge(BadgeKind.MISSED, 1)); assertEquals("badge:MISSED", kind(m)) // the ones before it went
        m.badges = listOf(Badge(BadgeKind.SMS, 2), Badge(BadgeKind.WHATSAPP, 1)); assertEquals("badge:SMS", kind(m)) // it went: the item at the same place
        m.badges = emptyList(); assertEquals("clock", kind(m)) // the whole zone went: the nearest zone
    }

    @Test fun theWeatherSitsAboveTheClockAndItsTapAndHighlightFollow() {
        val m = model(); m.weather = WeatherData(14, 2, true, emptyList(), emptyList(), 0L)
        val t = m.homeTargets().first { it.kind == "weather" }
        assertEquals(100f, t.t, 0.01f); assertEquals(168f, t.b, 0.01f) // centre line 134
        assertEquals(Action.Redraw, m.tap(240f, 134f)); assertEquals(Screen.WEATHER, m.screen)
        val old = model(); old.weather = m.weather
        assertEquals(Action.None, old.tap(240f, 392f)) // the old place no longer opens the forecast
    }

    @Test fun inTheMacLookTheWeatherIsAboveTheClockAndTheClockMovesDown() {
        val m = model(); m.weather = WeatherData(14, 2, true, emptyList(), emptyList(), 0L); m.theme = "mac"
        val t = m.homeTargets().first { it.kind == "weather" }
        assertEquals(140f, t.t, 0.01f) // cy 174 - 34
        assertEquals(174f, weatherCy(true), 0f); assertEquals(296f, clockY(true), 0f); assertEquals(134f, weatherCy(false), 0f); assertEquals(274f, clockY(false), 0f); assertEquals(392f, homeSlotY(true), 0f); assertEquals(392f, homeSlotY(false), 0f)
    }

    // ---- zone 5: the strip under the date
    @Test fun zoneFiveEntersOnTheShowingPageAndPagesWrapRound() {
        val m = model(); m.animationsOn = { false }; m.setNowPages(listOf("music", "event"))
        m.badges = listOf(Badge(BadgeKind.SMS, 1))
        m.onKey(Key.UP); assertEquals("badge:SMS", kind(m))
        m.onKey(Key.UP); assertEquals("now", kind(m)); assertEquals(0, m.nowPage)
        m.onKey(Key.RIGHT); assertEquals(1, m.nowPage)
        m.onKey(Key.RIGHT); assertEquals(0, m.nowPage) // wraps
        m.onKey(Key.LEFT); assertEquals(1, m.nowPage)
        assertEquals(null, m.nowSlide()) // animations are off
        m.onKey(Key.DOWN); m.onKey(Key.UP); assertEquals(1, m.nowPage) // entering again lands on the page showing
    }

    @Test fun okOnZoneFiveOpensNowPlayingOrTheCalendar() {
        val m = model(); m.setNowPages(listOf("music", "event")); m.playingProvider = Provider.RADIO
        m.onKey(Key.UP) // nothing in zone 6, so the first Up lands on the strip
        assertEquals("now", kind(m))
        m.onKey(Key.OK); assertEquals(Screen.MUSIC, m.screen); assertEquals(Provider.RADIO, m.provider)
        val e = model(); e.animationsOn = { false }; e.setNowPages(listOf("music", "event")); e.onKey(Key.UP); e.onKey(Key.RIGHT); e.onKey(Key.OK)
        assertEquals(Screen.CALENDAR, e.screen)
    }

    @Test fun leftAndRightWithNothingChosenStillOpenMenuAndMusicAndOnePageHasNoPaging() {
        val m = model(); m.setNowPages(listOf("event")); m.onKey(Key.UP); assertEquals("now", kind(m))
        m.onKey(Key.RIGHT); assertEquals(0, m.nowPage); assertEquals("now", kind(m))
        val n = model(); n.setNowPages(listOf("music", "event")); n.onKey(Key.LEFT); assertEquals(Screen.MENU, n.screen)
    }

    @Test fun whenThePageGoesTheStripFallsBackToItsFirstPage() {
        val m = model(); m.animationsOn = { false }; m.setNowPages(listOf("music", "event")); m.onKey(Key.UP); m.onKey(Key.RIGHT)
        assertEquals("event", m.nowPages[m.nowPage])
        m.setNowPages(listOf("event")); assertEquals("event", m.nowPages[m.nowPage]) // the same page stays
        m.setNowPages(listOf("music")); assertEquals(0, m.nowPage); assertEquals("now", kind(m))
        m.setNowPages(emptyList()); assertEquals("clock", kind(m)) // the strip went: the nearest zone
    }

    @Test fun tappingTheStripOpensItsPage() {
        val m = model(); m.setNowPages(listOf("event")); assertTrue(m.tap(240f, 400f) is Action.CalLoad); assertEquals(Screen.CALENDAR, m.screen)
    }

    @Test fun theStripAndTheCountsKeepTheirOwnBoxes() {
        val m = model(); m.setNowPages(listOf("event")); m.badges = listOf(Badge(BadgeKind.SMS, 2))
        val now = m.homeTargets().first { it.kind == "now" }; val badge = m.homeTargets().first { it.kind == "badge" }
        assertEquals(366f, now.t, 0f); assertEquals(478f, now.b, 0f)
        assertEquals(484f, badge.t, 0f); assertEquals(544f, badge.b, 0f)
        assertTrue(m.tap(240f, 470f) is Action.CalLoad) // the strip's lower part (the dots) opens the page
        val n = model(); n.badges = listOf(Badge(BadgeKind.SMS, 2))
        assertEquals(Action.None, n.tap(240f, 470f)); assertEquals(Action.Redraw, n.tap(240f, 500f)); assertEquals(Screen.INBOX, n.screen) // the band is y 480 to 556
    }
}
