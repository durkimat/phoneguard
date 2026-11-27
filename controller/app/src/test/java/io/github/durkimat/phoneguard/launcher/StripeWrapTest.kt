package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.*
import org.junit.Test

class StripeWrapTest {
    private fun grid(w: Int, h: Int, vararg ink: Pair<Int, Int>) = BooleanArray(w * h).also { for ((x, y) in ink) it[y * w + x] = true }

    @Test fun theGapFollowsTheShapeNotABoxRoundIt() {
        val g = StripeWrap.grow(grid(11, 11, 5 to 5), 11, 11, 3f)
        assertTrue(g[5 * 11 + 8]); assertTrue(g[2 * 11 + 5]); assertTrue(g[7 * 11 + 7])
        assertFalse(g[5 * 11 + 9]); assertFalse("a corner of the square is left to the pattern", g[8 * 11 + 8])
        assertTrue(StripeWrap.grow(grid(3, 3, 0 to 0), 3, 3, 2f)[1]) // ink at an edge does not run off the grid
    }

    @Test fun aStripeIsCutWhereAnyOfItsRowsIsClearedAndShortPiecesGo() {
        val clear = grid(40, 4, 10 to 1, 14 to 2, 30 to 3)
        assertEquals(listOf(0..9, 15..39), StripeWrap.pieces(clear, 40, 1, 3, 0, 40, 6))
        assertEquals(listOf(0..9, 11..13, 15..39), StripeWrap.pieces(clear, 40, 1, 3, 0, 40, 3))
        assertEquals(listOf(8..9, 11..13, 15..31), StripeWrap.pieces(clear, 40, 1, 3, 8, 32, 2))
    }

    @Test fun aWholeAreaCanBeClearedForAShapeThatReadsAsOne() {
        val clear = BooleanArray(40 * 4)
        StripeWrap.block(clear, 40, 4, 20, -3, 50, 9)
        assertEquals(listOf(0..19), StripeWrap.pieces(clear, 40, 0, 4, 0, 40, 6))
    }

    @Test fun onlyTheStripedMacLookMovesTheStripesToTheTopBarAndDropsTheHomeTitle() {
        val m = LauncherModel()
        fun look(theme: String, windows: String): Pair<Boolean, Boolean> { m.theme = theme; m.style = Style(windows = windows); return m.stripedTopBar to m.homeTitled }
        assertEquals(true to false, look("mac", "stripes")); assertEquals(true to false, look("macdark", "stripes"))
        assertEquals(false to true, look("mac", "platinum")); assertEquals(false to true, look("mac", "flat"))
        assertEquals(false to false, look("green", "stripes"))
        // the tap and highlight areas on the home screen move with what is drawn
        look("mac", "stripes"); val striped = m.clockTarget(); look("green", "stripes")
        assertEquals(m.clockTarget().t, striped.t, 0f)
    }
}
