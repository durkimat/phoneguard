package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class WordListTest {
    private val list = WordList(listOf("the", "good", "home", "gone", "hood", "hello", "I", "don't", "London", "goodbye"))

    @Test fun exactMatchesComeBackMostCommonFirst() { assertEquals(listOf("good", "home", "gone", "hood"), list.exact("4663")) }

    @Test fun apostrophesAreSkippedWhenSpelling() { assertEquals(listOf("don't"), list.exact("3668")) }

    @Test fun capitalisedWordsMatchTheirLetters() { assertEquals(listOf("London"), list.exact("566366")) }

    @Test fun longerWordsBeginningWithThePressedKeys() {
        assertEquals(listOf("goodbye"), list.longer("46632", 5)) // good + b
        assertEquals(listOf("good", "home", "gone", "hood", "goodbye").filter { WordList.keyOf(it).length > 3 }.take(3), list.longer("466", 3))
    }

    @Test fun unknownSequenceGivesNothing() { assertEquals(emptyList<String>(), list.exact("2222")) }

    @Test fun keyOfRejectsAnythingButLetters() { assertEquals("", WordList.keyOf("a1b")); assertEquals("43556", WordList.keyOf("Hello")) }
}
