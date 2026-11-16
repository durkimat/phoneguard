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

    @Test fun theDataBarStartsOnTheDividerAndTheDateAndSymbolsAreNudgedOntoTheAxis() {
        assertEquals(242f, BandGeometry.dataBarLeft(BandGeometry.divider(null)), 0f)
        assertEquals(246.5f, BandGeometry.dataBarLeft(BandGeometry.divider(246.5f)), 0f)
        assertEquals(0f, BandGeometry.axisShift(242f), 0f); assertEquals(4.5f, BandGeometry.axisShift(246.5f), 0f)
    }

    @Test fun theDataWordsKeepTheirSizeWhenTheyFitAndShrinkOnlyWhenTheyDoNot() {
        val room = BandGeometry.dataTextRight(BandGeometry.divider(null)) - 14f
        assertEquals(206f, room, 0f)
        for (w in listOf(150f, 172f, 171f)) assertEquals(26f, BandGeometry.dataTextSize(w, room), 0f) // "12 GB left", "4.8 GB left", "999 MB left" (measured widths)
        assertEquals(13f, BandGeometry.dataTextSize(412f, room) , 0.01f * 26f) // twice the room: half the size
    }
}
