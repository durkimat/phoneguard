package io.github.durkimat.phoneguard.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the real bundled word list ranks everyday texting words sensibly. Run from the app module directory. */
class RealWordListTest {
    private val list: WordList by lazy { WordList.parse(File("src/main/assets/words/words-en-gb.txt").readText()) }
    private fun top(keys: String) = list.exact(keys).firstOrNull()

    @Test fun everydayWordsComeFirst() {
        assertEquals("hi", top("44"))       // not "ii" or "gi"
        assertEquals("good", top("4663"))   // before home, gone, hood
        assertEquals("yes", top("937"))
        assertTrue(list.exact("65").take(3).contains("ok")) // "ok" is among the first two or three for 6-5
        assertEquals("mum", top("686"))
        assertEquals("bye", top("293"))
    }

    @Test fun contractionsAreOfferedAndRankedAboveTheirPlainForms() {
        assertEquals("don't", top("3668")); assertEquals("it's", top("487")); assertEquals("can't", top("2268"))
        assertTrue(list.exact("46").take(3).contains("I'm")) // "in" is more common, but I'm is one * away
        assertTrue(list.exact("487").indexOf("its") > list.exact("487").indexOf("it's"))
    }

    @Test fun namesAndPlacesStillCome() { assertTrue(list.exact(WordList.keyOf("Monday")).contains("Monday")); assertTrue(list.exact("566366").contains("London")) }

    @Test fun strongSwearWordsAreNotSuggested() {
        for (w in listOf("fuck", "shit", "cunt", "bitch")) assertFalse("$w should not be suggested", list.exact(WordList.keyOf(w)).any { it.equals(w, true) })
    }

    @Test fun theListIsFullSized() { assertTrue(list.exact("4663").size >= 3); assertTrue(File("src/main/assets/words/words-en-gb.txt").readLines().size > 45000) }
}
