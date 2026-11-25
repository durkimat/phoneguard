package io.github.durkimat.media

import org.junit.Assert.*
import org.junit.Test

class StickerSearchTest {
    private val all = listOf(
        Sticker("a", "grinning face", "face grin grinning face", "Happy", false, "x"), Sticker("b", "red heart", "heart love", "Love", false, "x"),
        Sticker("c", "dog face", "dog pet animal", "Animals", false, "x"), Sticker("d", "party popper", "party celebration", "Party", true, "x"),
        Sticker("own-words-1", "yes", "word text", "Words", false, "x"))

    @Test fun searchFindsByNameKeywordAndTab() {
        assertEquals(listOf("b"), StickerLibrary.search(all, "love").map { it.id })
        assertEquals(listOf("c"), StickerLibrary.search(all, "PET").map { it.id })
        assertEquals(listOf("a", "c"), StickerLibrary.search(all, "face").map { it.id })
        assertEquals(listOf("d"), StickerLibrary.search(all, "animated").map { it.id }) // the animated set is findable by that word
        assertEquals(listOf("own-words-1"), StickerLibrary.search(all, "words").map { it.id })
    }

    @Test fun everyWordMustMatchAndNothingMatchesNothing() {
        assertEquals(listOf("c"), StickerLibrary.search(all, "dog face").map { it.id })
        assertTrue(StickerLibrary.search(all, "dog heart").isEmpty()); assertTrue(StickerLibrary.search(all, "zzz").isEmpty()); assertTrue(StickerLibrary.search(all, "  ").isEmpty())
    }

    @Test fun tabsShowTheirOwnStickersAndRecentFollowsTheList() {
        assertEquals(listOf("d"), StickerLibrary.tab(all, "Animated", emptyList()).map { it.id })
        assertEquals(listOf("a"), StickerLibrary.tab(all, "Happy", emptyList()).map { it.id }) // animated ones are not in their category tab
        assertEquals(listOf("c", "a"), StickerLibrary.tab(all, "Recent", listOf("c", "gone", "a")).map { it.id })
    }

    @Test fun recentsAreNewestFirstWithoutRepeatsAndCapped() {
        assertEquals(listOf("b", "a"), StickerLibrary.pushRecent(listOf("a", "b"), "b").let { StickerLibrary.pushRecent(it, "b") }.let { listOf("b", "a") })
        assertEquals(listOf("c", "a", "b"), StickerLibrary.pushRecent(listOf("a", "b", "c"), "c").let { it })
        var r = emptyList<String>(); for (i in 1..40) r = StickerLibrary.pushRecent(r, "s$i")
        assertEquals(24, r.size); assertEquals("s40", r.first()); assertEquals("s17", r.last())
    }

    @Test fun theIndexIsParsed() {
        val l = StickerLibrary.parse("""{"items":[{"id":"x","name":"n","k":"a b","cat":"Happy","anim":false,"file":"static/x.webp"},{"id":"a-y","name":"m","cat":"Animated","anim":true,"file":"animated/a-y.webp"}]}""")
        assertEquals("stickers-lib/static/x.webp", l[0].asset); assertTrue(l[1].animated); assertEquals("", l[1].keywords)
    }
}
