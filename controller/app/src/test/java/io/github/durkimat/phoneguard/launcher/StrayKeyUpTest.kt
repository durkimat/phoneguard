package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Back loop (0.4.79 to 0.4.81): Home acts on the right key when it comes up, Back on any other screen acts when it goes down, so the
 * UP of that Back landed on Home and was taken for a short press = Contacts. [Keys] is the same decision the activity makes (DownGate in front
 * of the Home right-key rule), driven with real key sequences on the real model.
 */
class StrayKeyUpTest {
    private class Keys(val m: LauncherModel) {
        val gate = DownGate()
        var fired = false

        /** What the activity does with the right soft key on Home with nothing chosen, and with everything else on DOWN. */
        fun down(k: Key, digit: Int = -1) {
            gate.down(code(k), m.screen)
            if (k == Key.SOFT_RIGHT && m.screen == Screen.HOME && m.homeFocus < 0) { fired = false; return } // armed: acts when it comes up, or the hold fires
            m.onKey(k, digit)
        }
        fun up(k: Key) {
            val ok = gate.up(code(k), m.screen)
            if (k == Key.SOFT_RIGHT && m.screen == Screen.HOME && m.homeFocus < 0) { if (!fired && ok) m.onKey(k, -1); fired = false }
        }
        fun hold() { if (m.screen == Screen.HOME) { fired = true; m.openMusicFromHome() } } // the long-press timer
        fun tap(k: Key) { down(k); up(k) }
        private fun code(k: Key) = k.ordinal
    }

    private val items = listOf(MenuItem("p.phone", "Phone"), MenuItem("p.msg", "Messages"), MenuItem("p.wa", "WhatsApp"), MenuItem(MUSIC_PKG, "Music"))
    private fun keys() = Keys(LauncherModel(items, contactsApp = "p.contacts"))

    @Test fun backFromTheMenuEndsOnHomeAndStaysThere() {
        val k = keys()
        k.tap(Key.SOFT_LEFT); assertEquals(Screen.MENU, k.m.screen)
        k.down(Key.SOFT_RIGHT); assertEquals(Screen.HOME, k.m.screen) // Back goes Home on DOWN
        k.up(Key.SOFT_RIGHT) // the UP lands on Home
        assertEquals(Screen.HOME, k.m.screen)
    }

    @Test fun backFromContactsEndsOnHomeAndStaysThere() {
        val k = keys()
        k.tap(Key.SOFT_RIGHT); assertEquals(Screen.CONTACTS, k.m.screen) // a real short press
        k.down(Key.SOFT_RIGHT); assertEquals(Screen.HOME, k.m.screen)
        k.up(Key.SOFT_RIGHT); assertEquals(Screen.HOME, k.m.screen)
        // and the next real short press still opens Contacts, then Back again is clean
        k.tap(Key.SOFT_RIGHT); assertEquals(Screen.CONTACTS, k.m.screen)
        k.down(Key.SOFT_RIGHT); k.up(Key.SOFT_RIGHT); assertEquals(Screen.HOME, k.m.screen)
    }

    @Test fun backFromEveryFirstLevelScreenEndsOnHome() {
        for (row in items.indices) {
            val k = keys()
            k.tap(Key.SOFT_LEFT)
            repeat(row) { k.tap(Key.DOWN) }
            k.tap(Key.OK)
            var n = 0
            while (k.m.screen != Screen.HOME && n++ < 6) { k.down(Key.SOFT_RIGHT); k.up(Key.SOFT_RIGHT) }
            assertEquals("row $row", Screen.HOME, k.m.screen)
            k.up(Key.SOFT_RIGHT) // one more stray UP changes nothing
            assertEquals("row $row stays", Screen.HOME, k.m.screen)
        }
    }

    @Test fun aRealShortPressOnHomeStillOpensContacts() {
        val k = keys(); k.tap(Key.SOFT_RIGHT); assertEquals(Screen.CONTACTS, k.m.screen)
    }

    @Test fun aRealLongPressOnHomeStillOpensMusicAndNotContacts() {
        val k = keys()
        k.down(Key.SOFT_RIGHT); k.hold(); k.up(Key.SOFT_RIGHT)
        assertTrue(k.m.screen == Screen.MUSIC || k.m.screen == Screen.MUSICMENU)
    }

    @Test fun aStrayUpWithNoDownDoesNothing() {
        val k = keys()
        k.up(Key.SOFT_RIGHT); assertEquals(Screen.HOME, k.m.screen)
        k.up(Key.SOFT_RIGHT); assertEquals(Screen.HOME, k.m.screen)
    }

    @Test fun theGateOnlyPassesAnUpThatWentDownOnTheSameScreen() {
        val g = DownGate()
        g.down(4, Screen.MENU); assertFalse(g.up(4, Screen.HOME)) // went down on Menu, came up on Home
        assertFalse(g.up(4, Screen.HOME)) // and it was forgotten
        g.down(4, Screen.HOME); assertTrue(g.up(4, Screen.HOME))
        assertFalse(g.up(4, Screen.HOME))
        g.down(4, Screen.HOME); g.down(7, Screen.MENU); assertTrue(g.up(7, Screen.MENU)); assertTrue(g.up(4, Screen.HOME)) // keys do not mix
    }
}
