package io.github.durkimat.media

import org.junit.Assert.*
import org.junit.Test

class GraphemesTest {
    private fun e(codes: String) = EmojiSet.decode(codes)
    private val face = e("1F600"); private val family = e("1F468+200D+1F469+200D+1F467"); private val flag = e("1F1EC+1F1E7")
    private val thumb = e("1F44D+1F3FD"); private val heart = e("2764+FE0F")

    @Test fun eachOfTheseIsOneCharacter() {
        for (s in listOf(face, family, flag, thumb, heart, e("31+FE0F+20E3"), e("65+301"))) assertEquals(s.length, Graphemes.next(s, 0))
    }

    @Test fun twoFlagsAreTwoCharacters() { assertEquals(listOf(0, 4, 8), Graphemes.boundaries(flag + e("1F1EB+1F1F7"))) }

    @Test fun steppingAndDeletingNeverSplitOne() {
        val s = "a$family$thumb!"
        assertEquals(1 + family.length + thumb.length, Graphemes.previous(s, s.length))
        assertEquals(1, Graphemes.previous(s, 1 + family.length)); assertEquals(1 + family.length, Graphemes.after(s, 1))
        assertEquals("a$family", Graphemes.dropLast("a$family$thumb"))
        assertEquals("", Graphemes.dropLast(face)); assertEquals("", Graphemes.dropLast("")); assertEquals(0, Graphemes.previous(face, 0))
    }

    @Test fun takingNeverLeavesHalfACharacter() {
        assertEquals("ab", Graphemes.take("ab$face", 3)); assertEquals("ab$face", Graphemes.take("ab$face", 4)); assertEquals("abc", Graphemes.take("abc", 10))
    }

    @Test fun everyEmojiInThePickerIsExactlyOneCharacterAndListedOnce() {
        val all = EmojiSet.groups.flatMap { it.emoji }
        for (s in all) assertEquals(s.codePoints().toArray().joinToString("+") { Integer.toHexString(it) }, s.length, Graphemes.next(s, 0))
        assertEquals(all.size, all.toSet().size); assertTrue(all.size > 150)
    }
}
