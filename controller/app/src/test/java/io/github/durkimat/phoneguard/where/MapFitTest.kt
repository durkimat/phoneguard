package io.github.durkimat.phoneguard.where

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFitTest {
    @Test fun northIsUpAndEastIsRightInsideTheBoxWithMargins() {
        val f = MapFit(listOf(51.0 to -0.4, 51.1 to -0.2), 400f, 400f, 20f)
        val sw = f.xy(51.0, -0.4); val ne = f.xy(51.1, -0.2)
        assertTrue(ne.second < sw.second)   // further north is higher up the screen
        assertTrue(ne.first > sw.first)     // further east is further right
        listOf(sw, ne).forEach { assertTrue(it.first in 20f..380f && it.second in 20f..380f) }
    }

    @Test fun theLongerSideFillsTheBoxAndTheShapeIsCentred() {
        val f = MapFit(listOf(51.0 to -0.30, 51.0 to -0.10), 400f, 400f, 0f) // a purely east-west line
        val a = f.xy(51.0, -0.30); val b = f.xy(51.0, -0.10)
        assertEquals(0f, a.first, 0.5f); assertEquals(400f, b.first, 0.5f)
        assertEquals(200f, a.second, 0.5f)
    }

    @Test fun onePointDoesNotBlowUp() {
        val f = MapFit(listOf(51.0 to -0.3), 400f, 300f, 10f)
        val p = f.xy(51.0, -0.3)
        assertTrue(p.first.isFinite() && p.second.isFinite())
    }

    @Test fun segmentsSkipLegsWithoutALineAndMarkTheCurrentOne() {
        val line = "_p~iF~ps|U_ulLnnqC" // two points, precision 5 encoding decodes as something at precision 6 too: only the count matters
        val legs = listOf(Leg("walk", "", "", "", "", "", "", 5, false, line), Leg("bus", "1", "", "", "", "", "", 5, false, null), Leg("train", "", "", "", "", "", "", 5, false, line))
        val segs = MapPicture.segments(legs, 2)
        assertEquals(2, segs.size)
        assertTrue(segs[0].walk && !segs[0].current)
        assertTrue(!segs[1].walk && segs[1].current)
    }

    @Test fun tilesCoverTheBoxAtAMatchingZoom() {
        // about 1 km of St Albans streets
        val f = MapFit(listOf(51.7520 to -0.3360, 51.7610 to -0.3220), 456f, 392f, 36f)
        val z = f.zoom()!!
        assertTrue(z in 14..17)
        val t = f.tiles()
        assertTrue(t.isNotEmpty() && t.size <= MapFit.MAX_TILES)
        assertTrue(t.all { it.z == z })
        assertTrue(t.minOf { it.left } <= 0f && t.maxOf { it.left + it.size } >= 456f)
        assertTrue(t.minOf { it.top } <= 0f && t.maxOf { it.top + it.size } >= 392f)
        assertTrue(t.all { it.size in 150f..400f })
    }

    @Test fun aKnownPointFallsInTheRightTile() {
        // Slippy tile maths: lat 51.5074, lon -0.1278 (London) at zoom 15 is tile 16372/10896
        val f = MapFit(listOf(51.5074 to -0.1278), 456f, 392f, 36f)
        assertEquals(16, f.zoom())
        val z = 15
        val n = 1 shl z
        assertEquals(16372, Math.floor(MapFit.lonToX(-0.1278) * n).toInt()); assertEquals(10896, Math.floor(MapFit.latToY(51.5074) * n).toInt())
    }

    @Test fun aRouteTooWideForTilesGetsNone() {
        val f = MapFit(listOf(51.5 to -0.1, 55.9 to -3.2), 456f, 392f, 36f)
        assertEquals(null, f.zoom()); assertTrue(f.tiles().isEmpty())
    }

    @Test fun zoomingInDoublesTheScaleAndKeepsTheFocusInTheMiddle() {
        val pts = listOf(51.7520 to -0.3360, 51.7610 to -0.3220)
        val whole = MapFit(pts, 456f, 392f, 36f)
        val focus = 51.7560 to -0.3300
        val z1 = MapFit(pts, 456f, 392f, 36f, 1, focus)
        val a = z1.xy(focus.first, focus.second)
        assertEquals(228f, a.first, 0.5f); assertEquals(196f, a.second, 0.5f)
        val p = whole.xy(51.7520, -0.3360); val q = whole.xy(51.7610, -0.3220)
        val p1 = z1.xy(51.7520, -0.3360); val q1 = z1.xy(51.7610, -0.3220)
        assertEquals(2f * Math.abs(q.first - p.first), Math.abs(q1.first - p1.first), 1.5f)
        assertEquals(whole.zoom()!! + 1, z1.zoom())
        assertTrue(z1.zoomed && !whole.zoomed)
        // zoomed out with no focus keeps the route centred
        val out = MapFit(pts, 456f, 392f, 36f, -1, null)
        val mid = out.xy(51.7565, -0.3290)
        assertEquals(228f, mid.first, 6f); assertEquals(196f, mid.second, 6f)
    }

    @Test fun edgeArrowOnlyWhenOffTheBoxAndPointsTowardsIt() {
        assertEquals(null, MapPicture.edgeArrow(200f, 150f, 456f, 392f, 16f))
        val right = MapPicture.edgeArrow(900f, 196f, 456f, 392f, 16f)!!
        assertEquals(440f, right.first, 0.5f); assertEquals(196f, right.second, 0.5f); assertEquals(Math.PI / 2, right.third, 0.01)
        val up = MapPicture.edgeArrow(228f, -500f, 456f, 392f, 16f)!!
        assertEquals(16f, up.second, 0.5f); assertEquals(0.0, up.third, 0.01)
        val corner = MapPicture.edgeArrow(2000f, 2000f, 456f, 392f, 16f)!!
        assertTrue(corner.first <= 440.5f && corner.second <= 376.5f)
    }
}
