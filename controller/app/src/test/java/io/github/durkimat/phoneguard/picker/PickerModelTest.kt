package io.github.durkimat.phoneguard.picker

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
        assertEquals(listOf("Animated", "Happy", "Love", "Party").filter { it != "Party" }, sticker().groups.map { it.name }) // the animated one is only in Animated
    }

    @Test fun recentThingsComeFirstNewestFirstAndUnknownOnesAreForgotten() {
        val m = sticker(listOf("b", "gone", "a"))
        assertEquals("Recent", m.group.name); assertEquals(listOf("b", "a"), m.items.map { it.id })
    }

    @Test fun anEmojiIsUsedAtOnce() {
        val m = emoji(); m.onKey(Key.RIGHT, -1)
        val a = m.onKey(Key.OK, -1); assertTrue(a is PAction.Picked && a.item == m.items[1])
    }

    @Test fun aStickerIsShownLargeBeforeItIsSent() {
        val m = sticker()
        assertEquals(PAction.Redraw, m.onKey(Key.OK, -1)); assertEquals(PView.PREVIEW, m.view); assertEquals("Send", m.leftLabel)
        assertEquals(PAction.Redraw, m.onKey(Key.SOFT_RIGHT, -1)); assertEquals(PView.GRID, m.view) // Back: not sent
        m.onKey(Key.DIGIT, 5); val a = m.onKey(Key.SOFT_LEFT, -1); assertTrue(a is PAction.Picked)
    }

    @Test fun theLeftKeyListsTheGroupsAndBackStepsOutOneAtATime() {
        val m = emoji(); assertEquals("Groups", m.leftLabel)
        m.onKey(Key.SOFT_LEFT, -1); assertEquals(PView.GROUPS, m.view)
        m.onKey(Key.DOWN, -1); m.onKey(Key.OK, -1); assertEquals("Hearts and hands", m.group.name); assertEquals(0, m.sel)
        assertEquals(PAction.Exit, m.onKey(Key.SOFT_RIGHT, -1)); assertEquals(PAction.Home, m.onKey(Key.END, -1))
    }

    @Test fun theEmojiGridIsSixWideAndPages() {
        val m = emoji(); m.onKey(Key.DOWN, -1); assertEquals(6, m.sel); m.onKey(Key.POUND, -1); assertEquals(42, m.sel); assertTrue(42 in m.visible())
    }

    @Test fun anEmptyLibraryIsSafe() {
        val m = PickerModel(PickKind.STICKER, emptyList()); assertEquals(PAction.None, m.onKey(Key.OK, -1)); assertTrue(m.visible().isEmpty())
    }
}
