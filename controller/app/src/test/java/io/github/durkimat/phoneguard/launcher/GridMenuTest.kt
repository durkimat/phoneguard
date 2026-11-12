package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridMenuTest {
    private fun items(n: Int) = (1..n).map { MenuItem("p.$it", "App $it") }
    private fun menu(n: Int, style: String = "grid"): LauncherModel = LauncherModel(items(n)).also { it.menuStyle = style; it.onKey(Key.OK) }

    @Test fun theListIsTheDefaultAndTheGridIsOnlyUsedWhenAskedFor() {
        assertEquals(Layout.LIST, menu(6, "list").layout)
        assertEquals(Layout.GRID, menu(6).layout)
        assertEquals(Layout.CUSTOM, LauncherModel(items(6)).layout) // the home screen is drawn by hand
        assertEquals(Layout.LIST, LauncherModel(emptyList()).also { it.menuStyle = "grid"; it.onKey(Key.OK) }.layout) // nothing to show: the plain list's "empty" page
    }

    @Test fun leftAndRightStepThroughTheTilesAndNeverOpenOne() {
        val m = menu(5)
        assertEquals(Action.Redraw, m.onKey(Key.RIGHT)); assertEquals(1, m.selected)
        repeat(3) { m.onKey(Key.RIGHT) }; assertEquals(4, m.selected)
        m.onKey(Key.RIGHT); assertEquals(0, m.selected) // wrapped past the end
        m.onKey(Key.LEFT); assertEquals(4, m.selected) // and back round
        assertEquals(Screen.MENU, m.screen)
    }

    @Test fun upAndDownMoveARowAndWrapWithinTheColumn() {
        val m = menu(8) // rows of 3, 3 and 2
        m.onKey(Key.DOWN); assertEquals(3, m.selected)
        m.onKey(Key.DOWN); assertEquals(6, m.selected)
        m.onKey(Key.DOWN); assertEquals(0, m.selected) // off the bottom: top of the same column
        m.onKey(Key.UP); assertEquals(6, m.selected) // off the top: bottom of the column
    }

    @Test fun movingDownIntoAShortLastRowStopsAtTheLastTile() {
        val m = menu(8)
        repeat(5) { m.onKey(Key.RIGHT) }; assertEquals(5, m.selected) // row 1, column 2
        m.onKey(Key.DOWN); assertEquals(7, m.selected) // the row below has only two tiles: the last one
        m.onKey(Key.UP); m.onKey(Key.UP); assertEquals(1, m.selected) // 7 -> 4 -> 1
        m.onKey(Key.UP); assertEquals(7, m.selected) // up from the top row: the bottom of column 1
    }

    @Test fun okAndTheLeftKeyOpenTheTile() {
        val m = menu(5); m.onKey(Key.RIGHT)
        assertEquals(Action.Launch("p.2"), m.onKey(Key.OK))
        assertEquals(Action.Launch("p.2"), m.onKey(Key.SOFT_LEFT))
    }

    @Test fun theRightKeyAndEndGoHomeAndNumberKeysJump() {
        val m = menu(5)
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)); assertEquals(Screen.HOME, m.screen)
        m.onKey(Key.OK); m.onKey(Key.END); assertEquals(Screen.HOME, m.screen)
        m.onKey(Key.OK); assertEquals(Action.Launch("p.3"), m.onKey(Key.DIGIT, 3))
    }

    @Test fun moreRowsThanFitScrollOneRowAtATime() {
        val m = menu(12) // 4 rows, 3 on screen
        assertEquals(0, m.gridFirstRow())
        repeat(2) { m.onKey(Key.DOWN) }; assertEquals(0, m.gridFirstRow()) // row 2 is still in view
        m.onKey(Key.DOWN); assertEquals(1, m.gridFirstRow()) // row 3 pushes the first row off
        m.onKey(Key.DOWN); assertEquals(0, m.gridFirstRow()) // wrapped to the top
    }

    @Test fun touchingATileOpensIt() {
        val m = menu(8)
        assertEquals(Action.Launch("p.1"), m.tap(84f, 170f)) // first row, first column
        assertEquals(Action.Launch("p.5"), m.tap(240f, 300f)) // second row, middle column
        assertEquals(Action.None, m.tap(240f, 100f)) // the title bar
    }

    @Test fun theMenuStyleIsChosenInSettings() {
        val m = LauncherModel(listOf(MenuItem("p.1", "One"), MenuItem(SETTINGS_PKG, "Settings")))
        for (k in listOf(Key.OK, Key.DOWN, Key.OK)) m.onKey(k) // Menu > Settings
        repeat(2) { m.onKey(Key.DOWN) } // Theme, Font, Menu style
        m.onKey(Key.OK); assertEquals(Screen.MENUSTYLE, m.screen); assertEquals(listOf("List *", "Grid"), m.rows)
        m.onKey(Key.DOWN)
        assertEquals(Action.SaveMenuStyle("grid"), m.onKey(Key.OK))
        assertEquals(Screen.SETTINGS, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MENU, m.screen); assertEquals(Layout.GRID, m.layout)
    }

    @Test fun everyKindOfMenuEntryHasAPictureAndTheDrawingsAreWellFormed() {
        assertEquals(emptyList<String>(), PixelIcons.problems())
        val m = LauncherModel(emptyList())
        for (pkg in listOf(MUSIC_PKG, CAMERA_PKG, APPS_PKG, GAMES_PKG, CALC_PKG, LOGSET_PKG, SETTINGS_PKG, NOTES_PKG, WEATHER_PKG, CALLLOG_PKG, RUNNING_PKG, "com.android.dialer", "com.android.messaging", "com.android.contacts", "com.whatsapp", "org.some.other"))
            assertTrue(pkg, m.iconFor(MenuItem(pkg, pkg)) in PixelIcons.names)
        assertEquals("app", m.iconFor(MenuItem("org.some.other", "Other")))
    }
}
