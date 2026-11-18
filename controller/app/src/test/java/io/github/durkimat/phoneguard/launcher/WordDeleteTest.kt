package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDeleteTest {
    @Test fun deletesTheWordBeforeTheCursor() {
        assertEquals("hello " to 6, WordDelete.back("hello world", 11))
        assertEquals("hello " to 6, WordDelete.back("hello world  ", 13))   // trailing spaces go with the word
        assertEquals("one two   three" to 8, WordDelete.back("one two world  three", 13))   // in the middle: only what is before the cursor
    }

    @Test fun edges() {
        assertEquals("" to 0, WordDelete.back("word", 4)); assertEquals("x" to 0, WordDelete.back("x", 0)); assertEquals("" to 0, WordDelete.back("", 0))
        assertEquals("  hi" to 0, WordDelete.back("  hi", 0)); assertEquals("  " to 2, WordDelete.back("  hi", 4))
    }

    @Test fun holdingDeleteInTheMessageEditorTakesAWordAtATime() {
        val m = LauncherModel(listOf(MenuItem(WHERE_PKG, "Where to?"), MenuItem(SETTINGS_PKG, "Settings")), contactsApp = "c", messagesApp = "m", dialerApp = "d")
        assertFalse(m.onHoldDelete())                    // not on a text screen: nothing to do
        m.onKey(Key.OK, -1, 0L); m.onKey(Key.OK, -1, 0L)   // Menu, Where to? (a text editor)
        assertEquals(Screen.WRITE, m.screen)
        for (d in listOf(4, 3, 0, 6, 6, 0, 8, 8)) m.onKey(Key.DIGIT, d, 0L)   // some letters, spaces between
        val before = m.writeText; assertTrue(before.isNotEmpty())
        var n = 0; while (m.onHoldDelete() && n < 10) n++
        assertEquals("", m.writeText); assertTrue("took $n holds for '$before'", n in 1..4)
        assertFalse(m.onHoldDelete())                    // nothing left: the repeat stops
    }
}
