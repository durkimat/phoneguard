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
 * What the emoji and sticker pickers do with each key, with no Android in them. Everything is ONE grid: the things used lately,
 * then every group, one after another, each starting on a new row, so that moving down reaches all of it and no page is a dead
 * end. The keys are the gallery's: the D-pad or 2 4 6 8 move, OK or 5 chooses, * and # page; the left key lists the groups, to
 * jump to one. An emoji is used at once; a sticker is shown large first, because once sent it cannot be taken back from this phone.
 */
class PickerModel(val kind: PickKind, groups: List<PickGroup>) {
    companion object {
        const val RECENT = "Recent"
        /** How many rows the things used lately may take: more would push everything else out of sight. */
        const val RECENT_ROWS = 2
        fun columns(kind: PickKind) = if (kind == PickKind.EMOJI) 6 else 3

        /** The picker's groups, with the things used lately first when there are any. [recent] lists ids, newest first. */
        fun emojiGroups(recent: List<String>): List<PickGroup> =
            withRecent(PickKind.EMOJI, recent, EmojiSet.groups.map { g -> PickGroup(g.name, g.emoji.map { PickItem(it, g.name, it) }) })

        fun stickerGroups(all: List<Sticker>, recent: List<String>): List<PickGroup> =
            withRecent(PickKind.STICKER, recent, StickerLibrary.TABS.filter { it != RECENT }.map { tab -> PickGroup(tab, StickerLibrary.tab(all, tab, emptyList()).map { item(it) }) })

        private fun item(s: Sticker) = PickItem(s.id, s.name, s.asset, s.animated)

        private fun withRecent(kind: PickKind, recent: List<String>, groups: List<PickGroup>): List<PickGroup> {
            val by = groups.flatMap { it.items }.associateBy { it.id }
            val used = recent.mapNotNull { by[it] }.take(columns(kind) * RECENT_ROWS)
            return (if (used.isEmpty()) emptyList() else listOf(PickGroup(RECENT, used))) + groups.filter { it.items.isNotEmpty() }
        }
    }

    val groups: List<PickGroup> = groups.filter { it.items.isNotEmpty() }.ifEmpty { listOf(PickGroup("Nothing here", emptyList())) }
    val cols = columns(kind)
    val visibleRows = if (kind == PickKind.EMOJI) 6 else 3

    /** Where each group's first tile is in [cells]. */
    private val starts = ArrayList<Int>()
    /** The grid, row after row. A gap (null) fills out a group's last row, so that the next group starts a new one. */
    val cells: List<PickItem?> = ArrayList<PickItem?>().also { out ->
        for (g in this.groups) {
            starts.add(out.size); out.addAll(g.items)
            while (out.size % cols != 0) out.add(null)
        }
    }

    var view = PView.GRID
        private set
    var groupSel = 0
        private set
    /** The chosen tile in [cells]; never a gap. */
    var sel = 0
        private set
    var top = 0
        private set

    /** The group the chosen tile is in. */
    val groupIndex: Int get() = starts.indexOfLast { it <= sel }.coerceAtLeast(0)
    val group: PickGroup get() = groups[groupIndex]
    /** The chosen tile's place within its group, from 1 (0 when there is nothing). */
    val position: Int get() = if (current == null) 0 else sel - starts[groupIndex] + 1
    val current: PickItem? get() = cells.getOrNull(sel)
    /** Whether a new group starts on this row of the grid (the screen draws a line above it). */
    fun startsGroup(row: Int): Boolean = row > 0 && row * cols in starts

    /** The cells on screen now, with [margin] rows either side. */
    fun visible(margin: Int = 0): IntRange =
        if (cells.isEmpty()) IntRange.EMPTY else ((top - margin).coerceAtLeast(0)) * cols..((top + visibleRows + margin) * cols - 1).coerceAtMost(cells.size - 1)

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
                d != null -> { sel = move(sel, d); scroll(); PAction.Redraw }
                key == Key.STAR || key == Key.POUND -> { repeat(visibleRows) { sel = move(sel, if (key == Key.POUND) Dir.DOWN else Dir.UP) }; scroll(); PAction.Redraw }
                ok -> current?.let { if (kind == PickKind.EMOJI) PAction.Picked(it) else { view = PView.PREVIEW; PAction.Redraw } } ?: PAction.None
                key == Key.SOFT_LEFT -> { view = PView.GROUPS; groupSel = groupIndex; PAction.Redraw }
                key == Key.SOFT_RIGHT -> PAction.Exit
                else -> PAction.None
            }
            PView.GROUPS -> when {
                d == Dir.UP -> { groupSel = (groupSel - 1).coerceAtLeast(0); PAction.Redraw }
                d == Dir.DOWN -> { groupSel = (groupSel + 1).coerceAtMost(groups.size - 1); PAction.Redraw }
                ok || key == Key.SOFT_LEFT -> { jumpTo(groupSel); view = PView.GRID; PAction.Redraw }
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

    /**
     * Left and Right step through the tiles in reading order, across groups; Up and Down move a row. Gaps are passed over: a move
     * into a short row lands on its last tile. Nothing wraps round from the end to the start.
     */
    private fun move(from: Int, d: Dir): Int {
        if (current == null) return 0
        return when (d) {
            Dir.LEFT -> (from - 1 downTo 0).firstOrNull { cells[it] != null } ?: from
            Dir.RIGHT -> (from + 1 until cells.size).firstOrNull { cells[it] != null } ?: from
            Dir.UP, Dir.DOWN -> {
                val to = GridNav.move(from, cells.size, cols, d)
                if (to / cols == from / cols) from else (to downTo to / cols * cols).first { cells[it] != null }
            }
        }
    }

    /** Chooses a group's first tile and brings its row to the top of the screen, as far as the grid allows. */
    private fun jumpTo(index: Int) {
        sel = starts[index]
        val rows = cells.size / cols
        top = (sel / cols).coerceAtMost((rows - visibleRows).coerceAtLeast(0))
    }

    private fun scroll() { top = GridNav.firstRow(sel, cells.size, cols, visibleRows, top) }
}
