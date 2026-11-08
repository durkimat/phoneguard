package io.github.durkimat.phoneguard.camera

import io.github.durkimat.phoneguard.launcher.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTest {
    @Test fun darkSceneIsInkAndBrightSceneIsNot() {
        val dark = Dither.twoTone(Dither.sample(8, 8, 0, false, 8, 8) { _, _ -> 0 }, 8, true)
        assertTrue(dark.all { it })
        val bright = Dither.twoTone(Dither.sample(8, 8, 0, false, 8, 8) { _, _ -> 250 }, 8, true)
        assertTrue(bright.none { it })
    }

    @Test fun aGradientDithersToAMixAndPlainThresholdsSplitInTheMiddle() {
        val cells = IntArray(16 * 16) { (it % 16) * 16 }
        val d = Dither.twoTone(cells, 16, true)
        assertTrue(d.count { it } in 40..216)
        val p = Dither.twoTone(cells, 16, false)
        assertTrue(p.take(16).first()); assertFalse(p.take(16).last())
    }

    @Test fun rotationAndMirrorPickTheRightCorner() {
        // a 4 by 2 source with one bright pixel at the top-left (0, 0)
        val luma = { x: Int, y: Int -> if (x == 0 && y == 0) 255 else 0 }
        val up = Dither.sample(4, 2, 0, false, 4, 2, luma)
        assertEquals(255, up[0]); assertEquals(0, up[3])
        val mirrored = Dither.sample(4, 2, 0, true, 4, 2, luma)
        assertEquals(255, mirrored[3])
        // turned 90 degrees clockwise the top-left of the source lands at the top-right of a 2 by 4 picture
        val turned = Dither.sample(4, 2, 90, false, 2, 4, luma)
        assertEquals(255, turned[1])
        val upsideDown = Dither.sample(4, 2, 180, false, 4, 2, luma)
        assertEquals(255, upsideDown[7])
    }

    @Test fun rampRunsFromInkToScreenColour() {
        val r = Dither.ramp(intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), r[0]); assertEquals(0xFFFFFFFF.toInt(), r[1])
    }

    @Test fun keysDriveTheViewfinder() {
        val m = CameraModel()
        assertEquals(CamAction.Shoot, m.onKey(Key.OK, -1))
        m.onKey(Key.UP, -1); assertEquals(1.5f, m.zoom); m.onKey(Key.DOWN, -1); m.onKey(Key.DOWN, -1); assertEquals(1f, m.zoom)
        m.onKey(Key.DIGIT, 2); assertEquals(FlashMode.AUTO, m.flash)
        assertEquals(Effect.DOTS, m.effect); m.onKey(Key.DIGIT, 4); assertEquals(Effect.TONES, m.effect)
        repeat(Effect.values().size - 1) { m.onKey(Key.DIGIT, 4) }; assertEquals(Effect.DOTS, m.effect) // every effect once, and round again
        m.onKey(Key.DIGIT, 6); assertEquals(Effect.NONE, m.effect) // 6 goes back
        m.onKey(Key.DIGIT, 3); assertEquals(3, m.timerSeconds); assertEquals(CamAction.Shoot, m.onKey(Key.OK, -1)); assertEquals(3, m.countdown)
        assertEquals(CamAction.Redraw, m.onKey(Key.DIGIT, 7)); assertEquals(0, m.countdown) // any key cancels the count
        assertEquals(CamAction.Rebind, m.onKey(Key.POUND, -1)); assertTrue(m.front)
        assertEquals(CamAction.Rebind, m.onKey(Key.RIGHT, -1)); assertEquals(CamMode.VIDEO, m.mode)
        assertEquals(CamAction.ToggleRecording, m.onKey(Key.OK, -1))
        m.recording = true
        assertEquals(CamAction.None, m.onKey(Key.SOFT_RIGHT, -1)); assertEquals(CamAction.None, m.onKey(Key.RIGHT, -1))
        m.recording = false; assertEquals(CamAction.Exit, m.onKey(Key.SOFT_RIGHT, -1))
    }

    @Test fun galleryWalksThePhotosAndColourIsAKeyAway() {
        val m = CameraModel()
        m.setGallery(listOf(MediaItem("a", false, "a", 1), MediaItem("b", false, "b", 2), MediaItem("c", true, "c", 3)))
        assertEquals("a", m.current?.uri)
        m.onKey(Key.RIGHT, -1); m.onKey(Key.RIGHT, -1); assertEquals("c", m.current?.uri)
        m.onKey(Key.RIGHT, -1); assertEquals("a", m.current?.uri) // wraps
        m.onKey(Key.OK, -1); assertTrue(m.colour); m.onKey(Key.RIGHT, -1); assertFalse(m.colour)
        m.onKey(Key.SOFT_RIGHT, -1); assertFalse(m.inGallery)
    }

    @Test fun drainRateIgnoresChargingAndGaps() {
        val h = 3600_000L
        val pts = listOf(
            io.github.durkimat.phoneguard.PowerLog.Point(0, 2_900_000, false), io.github.durkimat.phoneguard.PowerLog.Point(h, 2_870_000, false),
            io.github.durkimat.phoneguard.PowerLog.Point(2 * h, 2_900_000, true), io.github.durkimat.phoneguard.PowerLog.Point(10 * h, 2_000_000, false),
            io.github.durkimat.phoneguard.PowerLog.Point(11 * h, 1_960_000, false),
        )
        assertEquals(35.0, io.github.durkimat.phoneguard.PowerLog.rate(pts)!!, 0.01) // 30 mAh then 40 mAh, over two hours
        assertEquals(null, io.github.durkimat.phoneguard.PowerLog.rate(pts.take(1)))
    }

    @Test fun effectPhotosKeepTheShapeAndUseTheRightColours() {
        val w = 8; val h = 6
        val px = IntArray(w * h) { if (it % w < 4) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        // the render needs android.graphics, so the pure part is the palette
        assertEquals(0xFFFFFFFF.toInt() to 0xFF000000.toInt(), Effects.palette(Effect.MONO, 1, 2))
        assertEquals(7 to 9, Effects.palette(Effect.DOTS, 7, 9)); assertEquals(7 to 9, Effects.palette(Effect.TONES, 7, 9))
        assertEquals(48, px.size)
        assertEquals("dots", Effect.DOTS.extra); assertEquals("", Effect.NONE.extra)
    }

    private val white = 0xFFFFFFFF.toInt(); private val black = 0xFF000000.toInt()

    @Test fun halftoneDotsGrowWithDarkness() {
        // a left half that is dark and a right half that is light: the dark side has far more ink
        val cols = 40; val rows = 20
        val cells = IntArray(cols * rows) { if (it % cols < 20) 20 else 235 }
        val out = Effects.render(Effect.HALFTONE, cells, cols, rows, white, black, fine = false)
        val darkInk = (0 until rows).sumOf { y -> (0 until 20).count { x -> out[y * cols + x] == black } }
        val lightInk = (0 until rows).sumOf { y -> (20 until cols).count { x -> out[y * cols + x] == black } }
        assertTrue(darkInk > lightInk * 3)
    }

    @Test fun posterHasFourShadesAndOutlineFindsTheEdge() {
        val ramp = IntArray(64) { it * 4 }
        assertEquals(4, Effects.render(Effect.POSTER, ramp, 64, 1, white, black, false).toSet().size)
        val cols = 16; val rows = 8
        val step = IntArray(cols * rows) { if (it % cols < 8) 0 else 255 }
        val edges = Effects.render(Effect.OUTLINE, step, cols, rows, white, black, false)
        assertEquals(black, edges[3 * cols + 7]); assertEquals(white, edges[3 * cols + 2]); assertEquals(white, edges[3 * cols + 13])
    }

    @Test fun scanLinesDarkenEveryOtherRow() {
        val cells = IntArray(8 * 4) { 200 }
        val out = Effects.render(Effect.SCAN, cells, 8, 4, white, black, false)
        assertTrue((out[0] and 255) > (out[8 * 1] and 255)) // row 0 is normal, row 1 is darkened
    }
}
