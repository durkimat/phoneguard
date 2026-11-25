package io.github.durkimat.phoneguard.gallery

import io.github.durkimat.media.Dir
import io.github.durkimat.media.GridNav
import io.github.durkimat.phoneguard.launcher.Key

/** One photo or video on the phone. [bucket] is the folder name Android gives it ("Camera", "Screenshots"); [seconds] is a video's length. */
data class GItem(val uri: String, val video: Boolean, val name: String, val time: Long, val bucket: String, val seconds: Int = 0)

/** A folder of the phone's pictures. [all] is the one album that holds everything. */
data class Album(val name: String, val items: List<GItem>, val all: Boolean = false)

enum class GView { GRID, VIEWER, FOLDERS }

sealed class GAction {
    object None : GAction()
    object Redraw : GAction()
    /** A different picture is on show in the viewer: the activity loads it. */
    object ShowItem : GAction()
    data class Play(val item: GItem) : GAction()
    object Exit : GAction()
    object Home : GAction()
}

/**
 * What the gallery does with each key, with no Android in it. The phone's pictures are grouped into folders; the grid opens on the
 * photos folder ("Camera") when there is one and on everything otherwise. The activity carries out the actions and loads the pictures.
 */
class GalleryModel(val cols: Int = COLS, val visibleRows: Int = ROWS) {
    companion object {
        const val COLS = 3
        const val ROWS = 3
        const val ALL = "All pictures"
        /** The folder the grid opens on when it exists: where the phone's own camera keeps photos. */
        const val PHOTOS = "Camera"
        private const val LIMIT = 3000

        /** The albums: [ALL] first, then one per folder, the folder with the newest picture first. Empty phone: just [ALL]. */
        fun albums(items: List<GItem>): List<Album> {
            val newest = items.sortedByDescending { it.time }.take(LIMIT)
            val by = newest.groupBy { it.bucket.ifBlank { "Other" } }
            val folders = by.entries.sortedByDescending { e -> e.value.maxOf { it.time } }.map { Album(it.key, it.value) }
            return listOf(Album(ALL, newest, all = true)) + folders
        }

        /** Where the grid opens: the photos folder if there is one, else everything. */
        fun defaultAlbum(albums: List<Album>): Int = albums.indexOfFirst { it.name == PHOTOS && !it.all }.let { if (it < 0) 0 else it }
    }

    var view = GView.GRID
        private set
    var albums: List<Album> = listOf(Album(ALL, emptyList(), all = true))
        private set
    var albumIndex = 0
        private set
    /** The chosen tile (grid and viewer) or the chosen folder (folder list). */
    var sel = 0
        private set
    var top = 0
        private set
    var folderSel = 0
        private set
    /** True when pictures are drawn in colour instead of the theme's dither. */
    var colour = false
        private set
    var loaded = false
        private set

    val album: Album get() = albums[albumIndex]
    val items: List<GItem> get() = album.items
    val current: GItem? get() = items.getOrNull(sel)

    fun setItems(all: List<GItem>) {
        albums = albums(all); albumIndex = defaultAlbum(albums); sel = 0; top = 0; view = GView.GRID; loaded = true
    }

    /** The tiles on screen now, as indexes into [items]. */
    fun visible(margin: Int = 0): IntRange {
        val first = ((top - margin).coerceAtLeast(0)) * cols
        val last = ((top + visibleRows + margin) * cols - 1).coerceAtMost(items.size - 1)
        return if (items.isEmpty()) IntRange.EMPTY else first..last
    }

    /** What the left soft key says. */
    val leftLabel: String get() = when (view) {
        GView.GRID -> "Folders"
        GView.VIEWER -> if (current?.video == true) "Play" else if (colour) "Dots" else "Colour"
        GView.FOLDERS -> "Open"
    }
    val rightLabel: String get() = if (view == GView.GRID) "Back" else "Back"

    fun onKey(key: Key, digit: Int): GAction = when (view) {
        GView.GRID -> grid(key, digit)
        GView.VIEWER -> viewer(key, digit)
        GView.FOLDERS -> folders(key, digit)
    }

    /** The D-pad, or the keypad's 2 4 6 8, as a direction. */
    private fun dir(key: Key, digit: Int): Dir? = when {
        key == Key.UP || (key == Key.DIGIT && digit == 2) -> Dir.UP
        key == Key.DOWN || (key == Key.DIGIT && digit == 8) -> Dir.DOWN
        key == Key.LEFT || (key == Key.DIGIT && digit == 4) -> Dir.LEFT
        key == Key.RIGHT || (key == Key.DIGIT && digit == 6) -> Dir.RIGHT
        else -> null
    }

    private fun isOk(key: Key, digit: Int) = key == Key.OK || (key == Key.DIGIT && digit == 5)

    private fun grid(key: Key, digit: Int): GAction {
        val d = dir(key, digit)
        return when {
            d != null -> { sel = GridNav.move(sel, items.size, cols, d); scroll(); GAction.Redraw }
            key == Key.STAR || key == Key.POUND -> { sel = GridNav.page(sel, items.size, cols, visibleRows, key == Key.POUND); scroll(); GAction.Redraw }
            isOk(key, digit) -> if (items.isEmpty()) GAction.None else { view = GView.VIEWER; GAction.ShowItem }
            key == Key.SOFT_LEFT -> { view = GView.FOLDERS; folderSel = albumIndex; GAction.Redraw }
            key == Key.DIGIT && digit == 1 -> { colour = !colour; GAction.Redraw }
            key == Key.SOFT_RIGHT -> GAction.Exit
            key == Key.END -> GAction.Home
            else -> GAction.None
        }
    }

    private fun viewer(key: Key, digit: Int): GAction {
        val d = dir(key, digit)
        return when {
            d == Dir.LEFT || d == Dir.UP -> { sel = (sel - 1).coerceAtLeast(0); scroll(); GAction.ShowItem }
            d == Dir.RIGHT || d == Dir.DOWN -> { sel = (sel + 1).coerceAtMost((items.size - 1).coerceAtLeast(0)); scroll(); GAction.ShowItem }
            isOk(key, digit) || key == Key.SOFT_LEFT -> {
                val c = current
                if (c != null && c.video) GAction.Play(c) else { colour = !colour; GAction.ShowItem }
            }
            key == Key.DIGIT && digit == 1 -> { colour = !colour; GAction.ShowItem }
            key == Key.SOFT_RIGHT -> { view = GView.GRID; GAction.Redraw }
            key == Key.END -> GAction.Home
            else -> GAction.None
        }
    }

    private fun folders(key: Key, digit: Int): GAction {
        val d = dir(key, digit)
        return when {
            d == Dir.UP -> { folderSel = (folderSel - 1).coerceAtLeast(0); GAction.Redraw }
            d == Dir.DOWN -> { folderSel = (folderSel + 1).coerceAtMost(albums.size - 1); GAction.Redraw }
            isOk(key, digit) || key == Key.SOFT_LEFT -> { albumIndex = folderSel; sel = 0; top = 0; view = GView.GRID; GAction.Redraw }
            key == Key.SOFT_RIGHT -> { view = GView.GRID; GAction.Redraw }
            key == Key.END -> GAction.Home
            else -> GAction.None
        }
    }

    private fun scroll() { top = GridNav.firstRow(sel, items.size, cols, visibleRows, top) }
}
