package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherModelTest {
    private val items = listOf(MenuItem("p.phone", "Phone"), MenuItem("p.msg", "Messages"), MenuItem("p.wa", "WhatsApp"))
    private fun model() = LauncherModel(items, contactsPkg = "p.contacts")

    @Test fun startsOnHome() { assertEquals(Screen.HOME, model().screen) }

    @Test fun okLeftKeyAndDpadLeftOpenTheMenuAtTheTop() {
        for (k in listOf(Key.OK, Key.LEFT, Key.SOFT_LEFT)) {
            val m = model()
            assertEquals(Action.Redraw, m.onKey(k))
            assertEquals(Screen.MENU, m.screen); assertEquals(0, m.selected)
        }
    }

    @Test fun rightKeyAndDpadRightOnHomeOpenContacts() {
        for (k in listOf(Key.RIGHT, Key.SOFT_RIGHT)) assertEquals(Action.Launch("p.contacts"), model().onKey(k))
        assertEquals(Action.None, LauncherModel(items, null).onKey(Key.SOFT_RIGHT))
    }

    @Test fun theTwoKeysUnderTheScreenWorkLikeANokia() {
        val m = model()
        assertEquals(Action.Launch("p.contacts"), m.onKey(Key.SOFT_RIGHT)) // first press on Home: Contacts
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_LEFT)) // left key: the menu
        assertEquals(Screen.MENU, m.screen)
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)) // right key in the menu: Back
        assertEquals(Screen.HOME, m.screen)
    }

    @Test fun digitsOnHomeStartDialling() {
        val m = model()
        assertEquals(Action.Dial("7"), m.onKey(Key.DIGIT, 7))
        assertEquals(Action.Dial("*"), m.onKey(Key.STAR))
        assertEquals(Action.Dial("#"), m.onKey(Key.POUND))
        assertEquals(Action.CallKey, m.onKey(Key.CALL))
    }

    @Test fun menuMovesAndWraps() {
        val m = model(); m.onKey(Key.OK)
        m.onKey(Key.DOWN); assertEquals(1, m.selected)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); assertEquals(0, m.selected) // wrapped past the end
        m.onKey(Key.UP); assertEquals(2, m.selected) // and back round the top
    }

    @Test fun menuLaunchesTheSelectedEntry() {
        val m = model(); m.onKey(Key.OK); m.onKey(Key.DOWN)
        assertEquals(Action.Launch("p.msg"), m.onKey(Key.OK))
        assertEquals(Action.Launch("p.msg"), m.onKey(Key.LEFT)) // the "Select" label is D-pad Left
        assertEquals(Action.Launch("p.msg"), m.onKey(Key.SOFT_LEFT)) // and the left key under the screen
    }

    @Test fun numberKeysJumpToEntries() {
        val m = model(); m.onKey(Key.OK)
        assertEquals(Action.Launch("p.wa"), m.onKey(Key.DIGIT, 3))
        assertEquals(Action.None, m.onKey(Key.DIGIT, 9)) // no ninth entry
        assertEquals(Action.None, m.onKey(Key.DIGIT, 0))
    }

    @Test fun backAndDpadRightReturnHome() {
        for (k in listOf(Key.SOFT_RIGHT, Key.RIGHT)) {
            val m = model(); m.onKey(Key.OK); m.onKey(Key.DOWN)
            assertEquals(Action.Redraw, m.onKey(k))
            assertEquals(Screen.HOME, m.screen); assertEquals(0, m.selected)
        }
    }

    @Test fun emptyMenuDoesNotCrash() {
        val m = LauncherModel(emptyList(), null); m.onKey(Key.OK)
        assertEquals(Action.None, m.onKey(Key.DOWN)); assertEquals(Action.None, m.onKey(Key.OK))
    }

    @Test fun shrinkingTheListKeepsTheSelectionValid() {
        val m = model(); m.onKey(Key.OK); m.onKey(Key.DOWN); m.onKey(Key.DOWN)
        m.setItems(items.take(1)); assertTrue(m.selected in 0..0)
    }

    private fun withSettings() = LauncherModel(items + MenuItem(SETTINGS_PKG, "Settings"), contactsPkg = null)

    @Test fun settingsEntryOpensTheLaunchersOwnSettingsNotAnApp() {
        val m = withSettings(); m.onKey(Key.OK)
        m.onKey(Key.UP) // wraps to the last entry, Settings
        assertEquals(Action.Redraw, m.onKey(Key.OK))
        assertEquals(Screen.SETTINGS, m.screen); assertEquals("Settings", m.title); assertEquals(listOf("Theme", "Font"), m.rows)
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)) // Back returns to the menu, on the Settings entry
        assertEquals(Screen.MENU, m.screen); assertEquals(3, m.selected)
    }

    @Test fun choosingAThemeSavesItAndMarksIt() {
        val m = withSettings(); m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK); m.onKey(Key.OK)
        assertEquals(Screen.THEME, m.screen)
        assertEquals(listOf("Green *", "Grey", "Amber"), m.rows)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN)
        assertEquals(Action.SaveTheme("amber"), m.onKey(Key.SOFT_LEFT))
        assertEquals("amber", m.theme); assertEquals(Screen.SETTINGS, m.screen)
    }

    @Test fun themeBackLeavesTheThemeAlone() {
        val m = withSettings(); m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK); m.onKey(Key.OK)
        m.onKey(Key.DOWN)
        assertEquals(Action.Redraw, m.onKey(Key.RIGHT))
        assertEquals("green", m.theme); assertEquals(Screen.SETTINGS, m.screen)
    }

    @Test fun unknownThemeFallsBackToGreen() { assertEquals("green", Themes.byId("nope").id) }

    @Test fun fontIsChosenFromSettingsLikeTheTheme() {
        val m = withSettings(); m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK)
        assertEquals(listOf("Theme", "Font"), m.rows)
        m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals(Screen.FONT, m.screen); assertEquals(listOf("Monospace *", "Sans-serif", "Pixel VT323", "Pixelify"), m.rows)
        m.onKey(Key.DOWN)
        assertEquals(Action.SaveFont("sans"), m.onKey(Key.OK))
        assertEquals("sans", m.font); assertEquals(Screen.SETTINGS, m.screen)
    }
}
