package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherModelTest {
    private val items = listOf(MenuItem("p.phone", "Phone"), MenuItem("p.msg", "Messages"), MenuItem("p.wa", "WhatsApp"))
    private fun model() = LauncherModel(items, contactsPkg = "p.contacts")

    @Test fun startsOnHome() { assertEquals(Screen.HOME, model().screen) }

    @Test fun okOpensMenuAtTheTop() {
        val m = model()
        assertEquals(Action.Redraw, m.onKey(Key.OK))
        assertEquals(Screen.MENU, m.screen); assertEquals(0, m.selected)
    }

    @Test fun rightSoftKeyOnHomeOpensContacts() {
        assertEquals(Action.Launch("p.contacts"), model().onKey(Key.SOFT_RIGHT))
        assertEquals(Action.None, LauncherModel(items, null).onKey(Key.SOFT_RIGHT))
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
        assertEquals(Action.Launch("p.msg"), m.onKey(Key.SOFT_LEFT))
    }

    @Test fun numberKeysJumpToEntries() {
        val m = model(); m.onKey(Key.OK)
        assertEquals(Action.Launch("p.wa"), m.onKey(Key.DIGIT, 3))
        assertEquals(Action.None, m.onKey(Key.DIGIT, 9)) // no ninth entry
        assertEquals(Action.None, m.onKey(Key.DIGIT, 0))
    }

    @Test fun backReturnsHome() {
        val m = model(); m.onKey(Key.OK); m.onKey(Key.DOWN)
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT))
        assertEquals(Screen.HOME, m.screen); assertEquals(0, m.selected)
    }

    @Test fun emptyMenuDoesNotCrash() {
        val m = LauncherModel(emptyList(), null); m.onKey(Key.OK)
        assertEquals(Action.None, m.onKey(Key.DOWN)); assertEquals(Action.None, m.onKey(Key.OK))
    }

    @Test fun shrinkingTheListKeepsTheSelectionValid() {
        val m = model(); m.onKey(Key.OK); m.onKey(Key.DOWN); m.onKey(Key.DOWN)
        m.setItems(items.take(1)); assertTrue(m.selected in 0..0)
    }
}
