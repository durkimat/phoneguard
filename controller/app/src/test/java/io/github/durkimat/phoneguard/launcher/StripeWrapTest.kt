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

    @Test fun theTopBarsHighlightBoxesSitSquarelyRoundTheirSymbols() {
        val m = LauncherModel(); m.wifiShown = true; m.btShown = true
        val boxes = m.homeTargets().associateBy { it.kind }
        for ((kind, centre) in listOf("wifi" to 138f, "bt" to 188f)) { val b = boxes.getValue(kind); assertEquals(kind, centre, (b.l + b.r) / 2, 0.01f) }
        for (b in listOf("signal", "wifi", "bt").map { boxes.getValue(it) }) { assertEquals(BAR_BOX_TOP, b.t, 0f); assertEquals(BAR_BOX_BOTTOM, b.b, 0f) }
        assertEquals("centred on the middle line of the symbols", 23f, (BAR_BOX_TOP + BAR_BOX_BOTTOM) / 2, 0f)
        assertTrue(boxes.getValue("signal").r < boxes.getValue("wifi").l && boxes.getValue("wifi").r < boxes.getValue("bt").l)
    }

    @Test fun theAllowanceComesBackExactlyAsEntered() {
        fun gb(b: Long) = io.github.durkimat.phoneguard.core.DataPlan.gbText(b)
        assertEquals("10", gb(10_000_000_000L)); assertEquals("7.5", gb(7_500_000_000L)); assertEquals("12.75", gb(12_750_000_000L)); assertEquals("0.5", gb(500_000_000L))
    }

    @Test fun aPlanSavedOnThePhoneTakesPriorityOverAndroids() {
        val android = io.github.durkimat.phoneguard.core.AndroidPlan.parse("warn=2000000000 limit=30000000000 day=3")
        val mine = io.github.durkimat.phoneguard.core.DataPlan(allowance = 10_000_000_000L, renewDay = 14, correctionMb = 250)
        assertEquals(mine, mine.inForce(android)); assertFalse(mine.usesAndroid(android))
        val none = io.github.durkimat.phoneguard.core.DataPlan(warnPct = 20)
        val used = none.inForce(android)
        assertEquals(30_000_000_000L, used.allowance); assertEquals(3, used.renewDay); assertEquals(20, used.warnPct); assertTrue(none.usesAndroid(android))
        assertEquals(none, none.inForce(null)); assertFalse(none.usesAndroid(null))
    }

    @Test fun theTopBarHasRoomBetweenTheTorchAndTheClock() {
        val clockLeft = 480f - 14 - 56 + 1 - 8 - BAR_CLOCK_ROOM // its right edge, less its widest
        assertTrue(clockLeft - (TORCH_X + 12) >= 20f)
        assertEquals("one step after Bluetooth, as the radios are from each other", 50f, TORCH_X - 188f, 0f)
    }

    @Test fun theDateHasOnePlaceBelowTheLineUnderTheClock() {
        assertEquals(DATE_BASE, dateY(false, true), 0f); assertEquals(DATE_BASE, dateY(false, false), 0f)
        assertTrue("a clear gap under the alarm and timer line", DATE_BASE - (clockY(false) + 28f) >= 44f)
        assertTrue("its descenders stay above the strip", DATE_BASE + 10f <= NOW_TOP)
        assertEquals(352f, dateY(true, true), 0f); assertEquals(332f, dateY(true, false), 0f)
    }
}
