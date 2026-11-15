package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandGeometryTest {
    @Test fun theDefaultDividerIsTheColonOfTheDefaultClock() { assertEquals(242f, BandGeometry.divider(null), 0f) }

    @Test fun theGapIsTheSameEitherSideOfTheDivider() {
        val d = BandGeometry.divider(null)
        assertEquals(22f, d - BandGeometry.tempInkRight(d), 0f)
        assertEquals(22f, BandGeometry.rightEdge(d) - d, 0f)
        assertEquals(264f, BandGeometry.rightEdge(d), 0f)
    }

    @Test fun theDryTextStartsTwoColumnsInsideSoItsInkLandsOnColumn265() { assertEquals(266f, BandGeometry.dryTextX(242f), 0f) }

    @Test fun theRainBlockStartsAtTheEdgeAndItsTextAfterTheSymbol() {
        val d = BandGeometry.divider(242f)
        assertEquals(BandGeometry.rightEdge(d), BandGeometry.rainIconCx(d) - BandGeometry.RAIN_ICON / 2, 0f) // the symbol's left edge is the block's edge
        assertEquals(318f, BandGeometry.rainTextX(d), 0f)
    }

    @Test fun aMeasuredColonIsFollowedOnlyWhenBelievable() {
        assertEquals(246.5f, BandGeometry.divider(246.5f), 0f)
        assertEquals(242f, BandGeometry.divider(300f), 0f); assertEquals(242f, BandGeometry.divider(10f), 0f)
    }

    @Test fun theLongestRainTextStillFitsAtTheWidestDivider() {
        val d = BandGeometry.divider(252f)
        assertTrue(BandGeometry.rainTextX(d) + 140f < 470f) // "100% chance" at size 24 is about 140 px
    }
}
