package io.github.durkimat.phoneguard.picker

import io.github.durkimat.media.Dir
import io.github.durkimat.media.GridNav
import io.github.durkimat.media.Sticker
import io.github.durkimat.media.StickerLibrary
import io.github.durkimat.media.EmojiSet
import io.github.durkimat.phoneguard.launcher.Key

enum class PickKind { EMOJI, STICKER }

/** One thing to pick. [id] is what the recent list remembers; [value] the emoji itself, or the sticker's picture in the app. */
data class PickItem(val id: String, val name: String, val value: String, val animated: Boolean = false)

data class PickGroup(val name: String, val items: List<PickItem>)

enum class PView { GRID, GROUPS, PREVIEW }

sealed class PAction {
    object None : PAction()
    object Redraw : PAction()
    data class Picked(val item: PickItem) : PAction()
    object Exit : PAction()
    object Home : PAction()
}

/**
 * What the emoji and sticker pickers do with each key, with no Android in them. The keys are the gallery's: the D-pad or 2 4 6 8
 * move, OK or 5 chooses, * and # page, the left key lists the groups. An emoji is used at once; a sticker is shown large first,
 * because once sent it cannot be taken back from this phone.
 */
class PickerModel(val kind: PickKind, groups: List<PickGroup>) {
    companion object {
        const val RECENT = "Recent"

        /** The picker's groups, with the things used lately first when there are any. [recent] lists ids, newest first. */
        fun emojiGroups(recent: List<String>): List<PickGroup> =
            withRecent(recent, EmojiSet.groups.map { g -> PickGroup(g.name, g.emoji.map { PickItem(it, g.name, it) }) })

        fun stickerGroups(all: List<Sticker>, recent: List<String>): List<PickGroup> =
            withRecent(recent, StickerLibrary.TABS.filter { it != RECENT }.map { tab -> PickGroup(tab, StickerLibrary.tab(all, tab, emptyList()).map { item(it) }) })

        private fun item(s: Sticker) = PickItem(s.id, s.name, s.asset, s.animated)

        private fun withRecent(recent: List<String>, groups: List<PickGroup>): List<PickGroup> {
            val by = groups.flatMap { it.items }.associateBy { it.id }
            val used = recent.mapNotNull { by[it] }
            return (if (used.isEmpty()) emptyList() else listOf(PickGroup(RECENT, used))) + groups.filter { it.items.isNotEmpty() }
        }
    }

    val groups: List<PickGroup> = groups.ifEmpty { listOf(PickGroup("Nothing here", emptyList())) }
    val cols = if (kind == PickKind.EMOJI) 6 else 3
    val visibleRows = if (kind == PickKind.EMOJI) 6 else 3

    var view = PView.GRID
        private set
    var groupIndex = 0
        private set
    var groupSel = 0
        private set
    var sel = 0
        private set
    var top = 0
        private set

    val group: PickGroup get() = groups[groupIndex]
    val items: List<PickItem> get() = group.items
    val current: PickItem? get() = items.getOrNull(sel)

    /** The tiles on screen now, with [margin] rows either side. */
    fun visible(margin: Int = 0): IntRange =
        if (items.isEmpty()) IntRange.EMPTY else ((top - margin).coerceAtLeast(0)) * cols..((top + visibleRows + margin) * cols - 1).coerceAtMost(items.size - 1)

    val leftLabel: String get() = when (view) { PView.GRID -> "Groups"; PView.GROUPS -> "Open"; PView.PREVIEW -> "Send" }

    fun onKey(key: Key, digit: Int): PAction {
        if (key == Key.END) return PAction.Home
        val d = when {
            key == Key.UP || (key == Key.DIGIT && digit == 2) -> Dir.UP
            key == Key.DOWN || (key == Key.DIGIT && digit == 8) -> Dir.DOWN
            key == Key.LEFT || (key == Key.DIGIT && digit == 4) -> Dir.LEFT
            key == Key.RIGHT || (key == Key.DIGIT && digit == 6) -> Dir.RIGHT
            else -> null
        }
        val ok = key == Key.OK || (key == Key.DIGIT && digit == 5)
        return when (view) {
            PView.GRID -> when {
                d != null -> { sel = GridNav.move(sel, items.size, cols, d); scroll(); PAction.Redraw }
                key == Key.STAR || key == Key.POUND -> { sel = GridNav.page(sel, items.size, cols, visibleRows, key == Key.POUND); scroll(); PAction.Redraw }
                ok -> current?.let { if (kind == PickKind.EMOJI) PAction.Picked(it) else { view = PView.PREVIEW; PAction.Redraw } } ?: PAction.None
                key == Key.SOFT_LEFT -> { view = PView.GROUPS; groupSel = groupIndex; PAction.Redraw }
                key == Key.SOFT_RIGHT -> PAction.Exit
                else -> PAction.None
            }
            PView.GROUPS -> when {
                d == Dir.UP -> { groupSel = (groupSel - 1).coerceAtLeast(0); PAction.Redraw }
                d == Dir.DOWN -> { groupSel = (groupSel + 1).coerceAtMost(groups.size - 1); PAction.Redraw }
                ok || key == Key.SOFT_LEFT -> { groupIndex = groupSel; sel = 0; top = 0; view = PView.GRID; PAction.Redraw }
                key == Key.SOFT_RIGHT -> { view = PView.GRID; PAction.Redraw }
                else -> PAction.None
            }
            PView.PREVIEW -> when {
                ok || key == Key.SOFT_LEFT -> current?.let { PAction.Picked(it) } ?: PAction.None
                key == Key.SOFT_RIGHT -> { view = PView.GRID; PAction.Redraw }
                else -> PAction.None
            }
        }
    }

    private fun scroll() { top = GridNav.firstRow(sel, items.size, cols, visibleRows, top) }
}
