package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandGeometryTest {
    @Test fun theDefaultDividerIsTheColonOfTheDefaultClock() { assertEquals(242f, BandGeometry.divider(null), 0f) }


    // the weather group is centred on the axis whatever its parts measure
    private fun centre(axis: Float, left: Float, right: Float) = BandGeometry.groupLeft(axis, left, right) + BandGeometry.groupWidth(left, right) / 2

    @Test fun aWideTemperatureWithDryIsCentred() {
        val l = BandGeometry.leftWidth(95f, 55f)              // "-3°"-style wide digits plus the H/L stack
        val r = BandGeometry.rightWidth(false, 60f)           // "Dry"
        assertEquals(62f + 12f + 95f + 10f + 55f, l, 0f); assertEquals(60f, r, 0f)
        assertEquals(l + 22f + r, BandGeometry.groupWidth(l, r), 0f)
        assertEquals(242f, centre(242f, l, r), 0.001f)
    }

    @Test fun aNarrowTemperatureWithRainDueIsCentred() {
        val l = BandGeometry.leftWidth(40f, 55f)
        val r = BandGeometry.rightWidth(true, 140f)           // symbol 44 + 10 + "100% chance"
        assertEquals(44f + 10f + 140f, r, 0f)
        assertEquals(242f, centre(242f, l, r), 0.001f)
        assertTrue(BandGeometry.groupLeft(242f, l, r) >= BandGeometry.EDGE)
    }

    @Test fun withoutAForecastTheCurrentWeatherAloneIsCentred() {
        val l = BandGeometry.leftWidth(70f, 0f)               // no high/low either: symbol + temperature
        assertEquals(62f + 12f + 70f, l, 0f)
        assertEquals(l, BandGeometry.groupWidth(l, 0f), 0f)   // no spacer without a right block
        assertEquals(246.5f, centre(246.5f, l, 0f), 0.001f)   // follows a measured axis
    }

    @Test fun theTemperatureShrinksOnlyIfTheGroupWouldNotFitTheScreen() {
        assertEquals(46f, BandGeometry.tempSize(80f, 300f), 0f)
        assertEquals(46f * 100f / 200f, BandGeometry.tempSize(200f, 480f - 28f - 100f), 0.01f)
    }

    @Test fun aMeasuredColonIsFollowedOnlyWhenBelievable() {
        assertEquals(246.5f, BandGeometry.divider(246.5f), 0f)
        assertEquals(242f, BandGeometry.divider(300f), 0f); assertEquals(242f, BandGeometry.divider(10f), 0f)
    }

    @Test fun theDataStripIsOneGroupCentredOnTheAxis() {
        for (w in listOf(172f, 150f, 171f, 190f)) { // "4.8 GB left", "12 GB left", "999 MB left", "10.5 GB left"
            val gl = BandGeometry.dataGroupLeft(242f, w)
            val bl = BandGeometry.dataBarLeft(gl, w)
            assertEquals(gl + w + 22f, bl, 0f)                                  // words, spacer, bar are adjacent
            assertEquals(242f, (gl + (bl + 200f)) / 2, 0.001f)                  // the whole group's centre is the axis
            assertEquals(26f, BandGeometry.dataTextSize(w, BandGeometry.DATA_WORDS_ROOM), 0f) // never shrinks
        }
        assertEquals(246.5f, (BandGeometry.dataGroupLeft(246.5f, 172f) + BandGeometry.dataBarLeft(BandGeometry.dataGroupLeft(246.5f, 172f), 172f) + 200f) / 2, 0.001f)
    }

    @Test fun veryLongDataWordsShrinkToTheRoomLeft() {
        assertEquals(230f, BandGeometry.DATA_WORDS_ROOM, 0f)
        assertEquals(13f, BandGeometry.dataTextSize(460f, BandGeometry.DATA_WORDS_ROOM), 0.1f) // twice the room: half the size
    }
}
