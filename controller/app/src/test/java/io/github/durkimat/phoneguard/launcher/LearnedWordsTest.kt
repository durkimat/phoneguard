package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedWordsTest {
    private val base = WordList(listOf("good", "home", "gone", "hood", "hi", "the", "ok"))

    @Test fun wordsInSentMessagesAreCountedAndNamesKeepTheirCapital() {
        val l = LearnedWords()
        assertEquals(9, l.learnText("Hello Esther, see you at six. Esther loves pasta"))
        assertEquals(listOf("Esther" to 2), l.forKey(WordList.keyOf("Esther"))) // one entry, capital kept, both mentions counted
        assertEquals(listOf("hello" to 1), l.forKey(WordList.keyOf("hello"))) // a word starting a message is not a name
    }

    @Test fun swearWordsOneLetterWordsAndNumbersAreNotLearned() {
        val l = LearnedWords()
        assertEquals(0, l.learnText("fuck a 12345 wtf"))
        assertEquals(0, l.size)
    }

    @Test fun aWordTypedOftenLeadsAndAWordTypedOnceFollowsTheCommonOnes() {
        val l = LearnedWords()
        val p = LearningPredictor(base, l)
        assertEquals(listOf("good", "home", "gone", "hood"), p.exact("4663")) // nothing learned yet
        repeat(3) { l.learnText("gone") }
        assertEquals("gone", p.exact("4663").first()) // used a lot: first
        val q = LearningPredictor(WordList(listOf("the")), LearnedWords().also { it.learnText("Robin") { w -> w == "the" } }) // a name the list does not know keeps its capital
        assertEquals(listOf("Robin"), q.exact(WordList.keyOf("Robin"))) // unknown to the list, typed once: offered
        val r = LearnedWords().also { it.learnText("hood") }
        assertEquals(listOf("good", "home", "gone", "hood"), LearningPredictor(base, r).exact("4663")) // typed once: does not jump the queue
    }

    @Test fun contactNamesAreSuggestedFromTheStart() {
        val l = LearnedWords(); l.seed("Esther"); l.seed("Dr"); l.seed("x1"); l.seed("fuck")
        val p = LearningPredictor(base, l)
        assertEquals(listOf("Esther"), p.exact(WordList.keyOf("Esther")))
        assertTrue(p.exact(WordList.keyOf("fuck")).isEmpty()) // seeds are filtered too
        assertEquals(0, l.size) // seeds are not saved
    }

    @Test fun forgettingClearsEverythingLearned() {
        val l = LearnedWords(mapOf("Esther" to 5)); assertEquals(1, l.size); l.forget(); assertEquals(0, l.size); assertFalse(l.forKey(WordList.keyOf("Esther")).isNotEmpty())
    }

    @Test fun theLeastUsedWordsGoWhenThereAreTooMany() {
        val l = LearnedWords()
        for (i in 0 until 3050) l.learnText("w${"abcdefghij"[i % 10]}x${"klmnopqrst"[i / 10 % 10]}${"abcdefghij"[i / 100 % 10]}${"uvwxyzabcd"[i / 1000 % 10]}q")
        assertTrue(l.size <= 3000)
    }
}
