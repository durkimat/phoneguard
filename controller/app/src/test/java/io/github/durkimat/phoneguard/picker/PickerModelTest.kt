package io.github.durkimat.phoneguard.picker

import io.github.durkimat.media.EmojiSet
import io.github.durkimat.media.Sticker
import io.github.durkimat.phoneguard.launcher.Key
import org.junit.Assert.*
import org.junit.Test

class PickerModelTest {
    private fun emoji(recent: List<String> = emptyList()) = PickerModel(PickKind.EMOJI, PickerModel.emojiGroups(recent))
    private val stickers = listOf(
        Sticker("a", "grin", "", "Happy", false, "s/a.webp"), Sticker("b", "heart", "", "Love", false, "s/b.webp"), Sticker("c", "party", "", "Party", true, "s/c.webp"),
    )
    private fun sticker(recent: List<String> = emptyList()) = PickerModel(PickKind.STICKER, PickerModel.stickerGroups(stickers, recent))

    @Test fun withNothingUsedYetThePickerOpensOnTheFirstGroup() {
        assertEquals("Faces", emoji().group.name)
        assertEquals(listOf("Animated", "Happy", "Love"), sticker().groups.map { it.name }) // the animated one is only in Animated
    }

    @Test fun recentThingsComeFirstNewestFirstAndUnknownOnesAreForgotten() {
        val m = sticker(listOf("b", "gone", "a"))
        assertEquals("Recent", m.group.name); assertEquals(listOf("b", "a"), m.group.items.map { it.id })
    }

    @Test fun oneThingUsedLatelyIsNotADeadEndMovingDownReachesEverything() {
        val first = EmojiSet.groups.first().emoji
        val m = emoji(listOf(first[2]))
        assertEquals("Recent", m.group.name); assertEquals(1, m.group.items.size)
        m.onKey(Key.DOWN, -1); assertEquals("Faces", m.group.name); assertEquals(first[0], m.current!!.value); assertEquals(1, m.position)
        val seen = LinkedHashSet<String>()
        repeat(100) { m.onKey(Key.DOWN, -1); seen.add(m.group.name) }
        assertEquals(EmojiSet.groups.map { it.name }, seen.toList()) // every group, in order, with Down alone
        assertEquals(m.groups.last().name, m.group.name)
    }

    @Test fun rightAndLeftStepAcrossGroupsAndNeverRestOnAGap() {
        val m = emoji(listOf(EmojiSet.groups.first().emoji[5]))
        m.onKey(Key.RIGHT, -1); assertEquals("Faces", m.group.name); m.onKey(Key.LEFT, -1); assertEquals("Recent", m.group.name)
        m.onKey(Key.LEFT, -1); assertEquals(0, m.sel)
        var tiles = 1
        while (true) { val was = m.sel; m.onKey(Key.RIGHT, -1); if (m.sel == was) break; assertNotNull(m.current); tiles++ }
        assertEquals(m.groups.sumOf { it.items.size }, tiles)
    }

    @Test fun downIntoAShortRowLandsOnItsLastTileAndUpGoesBack() {
        val m = emoji(listOf(EmojiSet.groups.first().emoji[0]))
        m.onKey(Key.DOWN, -1); repeat(3) { m.onKey(Key.RIGHT, -1) }; assertEquals(4, m.position) // fourth in Faces
        m.onKey(Key.UP, -1); assertEquals("Recent", m.group.name); assertEquals(1, m.position)
    }

    @Test fun eachGroupStartsOnANewRowWithALineAboveIt() {
        val m = emoji(listOf(EmojiSet.groups.first().emoji[0]))
        assertTrue(m.cells.size % m.cols == 0); assertFalse(m.startsGroup(0)); assertTrue(m.startsGroup(1)); assertEquals(EmojiSet.groups.first().emoji[0], m.cells[6]!!.value)
        assertEquals((1 until m.cols).map { null }, m.cells.subList(1, m.cols))
    }

    @Test fun recentTakesTwoRowsAtMost() {
        val used = EmojiSet.groups.first().emoji.take(24)
        assertEquals(12, emoji(used).groups.first().items.size)
    }

    @Test fun anEmojiIsUsedAtOnce() {
        val m = emoji(); m.onKey(Key.RIGHT, -1)
        val a = m.onKey(Key.OK, -1); assertTrue(a is PAction.Picked && a.item == m.groups.first().items[1])
    }

    @Test fun aStickerIsShownLargeBeforeItIsSent() {
        val m = sticker()
        assertEquals(PAction.Redraw, m.onKey(Key.OK, -1)); assertEquals(PView.PREVIEW, m.view); assertEquals("Send", m.leftLabel)
        assertEquals(PAction.Redraw, m.onKey(Key.SOFT_RIGHT, -1)); assertEquals(PView.GRID, m.view) // Back: not sent
        m.onKey(Key.DIGIT, 5); val a = m.onKey(Key.SOFT_LEFT, -1); assertTrue(a is PAction.Picked)
    }

    @Test fun theLeftKeyListsTheGroupsToJumpToOne() {
        val m = emoji(); assertEquals("Groups", m.leftLabel)
        m.onKey(Key.SOFT_LEFT, -1); assertEquals(PView.GROUPS, m.view)
        m.onKey(Key.DOWN, -1); m.onKey(Key.OK, -1); assertEquals("Hearts and hands", m.group.name); assertEquals(1, m.position)
        assertEquals(m.sel / m.cols, m.top) // its first row is at the top of the screen
        m.onKey(Key.UP, -1); assertEquals("Faces", m.group.name) // and what came before is just above
        assertEquals(PAction.Exit, m.onKey(Key.SOFT_RIGHT, -1)); assertEquals(PAction.Home, m.onKey(Key.END, -1))
    }

    @Test fun theEmojiGridIsSixWideAndPages() {
        val m = emoji(); m.onKey(Key.DOWN, -1); assertEquals(6, m.sel); m.onKey(Key.POUND, -1); assertEquals(42, m.sel); assertTrue(42 in m.visible())
        m.onKey(Key.STAR, -1); assertEquals(6, m.sel)
    }

    @Test fun anEmptyLibraryIsSafe() {
        val m = PickerModel(PickKind.STICKER, emptyList()); assertEquals(PAction.None, m.onKey(Key.OK, -1)); assertTrue(m.visible().isEmpty())
        for (k in listOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.POUND)) m.onKey(k, -1)
        assertEquals(0, m.sel); assertEquals(0, m.position)
    }
}
