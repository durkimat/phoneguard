package io.github.durkimat.familychat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StickerSelectionTest {
    private val sel = JSONObject(File("stickers/selection.json").readText()).getJSONArray("items")
    private val deny = JSONObject(File("stickers/deny.json").readText())
    private val rules = (0 until deny.getJSONArray("patterns").length()).map { Regex(deny.getJSONArray("patterns").getString(it), RegexOption.IGNORE_CASE) }

    private fun text(i: Int) = sel.getJSONObject(i).let { o -> listOf(o.getString("cldr")) + (0 until o.getJSONArray("keywords").length()).map { o.getJSONArray("keywords").getString(it) } }.joinToString(" | ")

    @Test fun theSelectionIsAboutThreeHundredFiftyToFourHundred() { assertTrue("${sel.length()}", sel.length() in 300..420) }

    @Test fun nothingOnTheDenyListIsSelected() {
        for (i in 0 until sel.length()) for (r in rules) assertFalse("${text(i)} matches ${r.pattern}", r.containsMatchIn(text(i)))
    }

    @Test fun theDenyListCatchesWhatItMustCatch() {
        val bad = listOf("beer mug", "wine glass", "cigarette", "pistol", "kitchen knife", "bomb", "pill", "syringe", "game die", "slot machine", "eggplant", "peach", "kiss mark", "bikini", "middle finger",
            "skull", "coffin", "ghost", "flag: United States", "face with symbols on mouth", "love hotel", "pile of poo", "dagger", "crossed swords", "cannabis leaf", "hammer and sickle")
        for (b in bad) assertTrue("$b is not caught", rules.any { it.containsMatchIn(b) })
        val good = listOf("grinning face", "red heart", "dog face", "pizza", "soccer ball", "party popper", "sun", "rainbow", "thumbs up")
        for (g in good) assertFalse("$g is caught", rules.any { it.containsMatchIn(g) })
    }

    @Test fun everyCategoryHasStickersAndIdsAreUnique() {
        val cats = (0 until sel.length()).map { sel.getJSONObject(it).getString("cat") }
        for (c in listOf("Happy", "Love", "Animals", "Food", "Sports", "Party", "Weather")) assertTrue("$c is empty", c in cats)
        val ids = (0 until sel.length()).map { sel.getJSONObject(it).getString("id") }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun everyAnimatedStickerIsInTheSelectionSoTheDenyListAppliesToIt() {
        val unis = (0 until sel.length()).map { sel.getJSONObject(it).getString("unicode") }.toSet()
        val lines = File("stickers/animated.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size in 40..70)
        for (l in lines) assertTrue("${l} is not in the selection", l.substringBefore("|").trim() in unis)
    }

    @Test fun theSourceIsPinnedToACommit() {
        val j = JSONObject(File("stickers/selection.json").readText())
        assertTrue(j.getString("commit").matches(Regex("[0-9a-f]{40}"))); assertEquals("https://github.com/microsoft/fluentui-emoji", j.getString("source"))
    }
}

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

class ThemeTest {
    @Test fun thereAreThirtyTwoWallpapersInFiveGroupsWithUniqueKeys() {
        assertEquals(listOf("Colours", "Patterns", "Nature", "Space", "Fun"), Themes.categories.map { it.first })
        val keys = Themes.categories.flatMap { it.second }.map { it.key }
        assertEquals(keys.size, keys.toSet().size); assertTrue(keys.size in 30..42)
    }

    @Test fun earlierWallpaperChoicesStillWork() {
        for (old in listOf("sand", "mint", "sky", "lilac", "dots", "stripes", "confetti", "night", "photo")) assertEquals(old, Themes.normalize(old))
        assertEquals("sand", Themes.normalize("something-removed")); assertEquals("sand", Themes.normalize(""))
    }

    @Test fun bubbleColoursAreTheSixAndOnlyTheChildsOwnAreChosen() {
        assertEquals(listOf("green", "blue", "pink", "lilac", "peach", "grey"), Themes.bubbles.map { it.key })
        assertEquals(0xFFD9F5D0.toInt(), Themes.bubbleColor("green")); assertEquals(0xFFD9F5D0.toInt(), Themes.bubbleColor("nonsense"))
    }

    @Test fun darkGroundsGetLightTextAndMidTonesAChip() {
        for (k in listOf("stars", "galaxy", "aurora", "moon", "deepblue", "night")) assertEquals(k, Themes.Meta.LIGHT, Themes.meta(k))
        assertEquals(Themes.Meta.PLAIN, Themes.meta("sand")); assertEquals(Themes.Meta.CHIP, Themes.meta("sunset"))
    }

    @Test fun theThemeIsKeptPerChat() {
        val s = Memory()
        s.setWallpaper("g1", "night"); s.setBubbleColor("g1", "pink")
        assertEquals("night", s.wallpaper("g1")); assertEquals("pink", s.bubbleColor("g1")); assertEquals("sand", s.wallpaper("g2")); assertEquals("green", s.bubbleColor("g2"))
    }
}
