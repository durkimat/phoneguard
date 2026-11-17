package io.github.durkimat.phoneguard.where

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/** A map tile (OpenStreetMap "slippy" numbering) and where it lands on screen inside the map box. */
data class Tile(val z: Int, val x: Int, val y: Int, val left: Float, val top: Float, val size: Float) { val key: String get() = "$z/$x/$y" }

/**
 * Fits a route into a rectangle of the screen with the Web Mercator projection the map tiles use, scaled to fill the box with a margin and centred.
 * North is up. [tiles] lists the map tiles that cover the box at a zoom whose tiles come out near their natural 256 px. Pure, tested without a phone.
 */
class MapFit(points: List<Pair<Double, Double>>, private val w: Float, private val h: Float, pad: Float, steps: Int = 0, focus: Pair<Double, Double>? = null) {
    private val minWX: Double; private val minWY: Double
    private val k: Double
    private val offX: Double; private val offY: Double
    /** True when the picture is zoomed to a spot other than the whole route. */
    val zoomed = steps != 0

    init {
        val xs = points.map { lonToX(it.second) }; val ys = points.map { latToY(it.first) }
        minWX = xs.minOrNull() ?: 0.0; minWY = ys.minOrNull() ?: 0.0
        val dx = (xs.maxOrNull() ?: 0.0) - minWX; val dy = (ys.maxOrNull() ?: 0.0) - minWY
        val bw = (w - 2 * pad).toDouble(); val bh = (h - 2 * pad).toDouble()
        val k0 = when { dx <= 1e-12 && dy <= 1e-12 -> 0.0; dx <= 1e-12 -> bh / dy; dy <= 1e-12 -> bw / dx; else -> min(bw / dx, bh / dy) }
        if (steps == 0 || k0 <= 0.0) { k = k0; offX = pad + (bw - dx * k) / 2; offY = pad + (bh - dy * k) / 2 }
        else { // zoomed: each step doubles or halves the scale, centred on the focus (or the middle of the route)
            k = k0 * Math.pow(2.0, steps.toDouble())
            val cx = focus?.let { lonToX(it.second) } ?: (minWX + dx / 2); val cy = focus?.let { latToY(it.first) } ?: (minWY + dy / 2)
            offX = w / 2.0 - (cx - minWX) * k; offY = h / 2.0 - (cy - minWY) * k
        }
    }

    /** Screen position inside the box for a latitude and longitude (x to the right, y down). */
    fun xy(lat: Double, lon: Double): Pair<Float, Float> = ((offX + (lonToX(lon) - minWX) * k).toFloat()) to ((offY + (latToY(lat) - minWY) * k).toFloat())

    /** The zoom (11 to 18) at which a 256 px tile is closest to its natural size on this picture; null when the route is too wide for tiles. */
    fun zoom(): Int? {
        if (k <= 0.0) return 16
        val z = Math.round(ln(k / 256.0) / ln(2.0)).toInt()
        return if (z < MIN_ZOOM) null else min(z, MAX_ZOOM)
    }

    /** The tiles covering the whole box, the ones nearest the middle first, at most [MAX_TILES]. Empty when the route is too wide for tiles. */
    fun tiles(): List<Tile> {
        val z = zoom() ?: return emptyList()
        val n = 1 shl z; val size = (k / n).toFloat()
        fun wx(sx: Double) = minWX + (sx - offX) / k
        fun wy(sy: Double) = minWY + (sy - offY) / k
        val x0 = floor(wx(0.0) * n).toInt().coerceIn(0, n - 1); val x1 = floor(wx(w.toDouble()) * n).toInt().coerceIn(0, n - 1)
        val y0 = floor(wy(0.0) * n).toInt().coerceIn(0, n - 1); val y1 = floor(wy(h.toDouble()) * n).toInt().coerceIn(0, n - 1)
        val out = ArrayList<Tile>()
        for (ty in y0..y1) for (tx in x0..x1) out.add(Tile(z, tx, ty, (offX + (tx.toDouble() / n - minWX) * k).toFloat(), (offY + (ty.toDouble() / n - minWY) * k).toFloat(), size))
        return out.sortedBy { t -> val cx = t.left + t.size / 2 - w / 2; val cy = t.top + t.size / 2 - h / 2; cx * cx + cy * cy }.take(MAX_TILES)
    }

    companion object {
        /** How far the child can zoom: steps of a doubling each way from the whole route. */
        const val ZOOM_OUT = -3; const val ZOOM_IN = 3
        const val MIN_ZOOM = 11; const val MAX_ZOOM = 18; const val MAX_TILES = 16
        fun lonToX(lon: Double) = (lon + 180.0) / 360.0
        fun latToY(lat: Double): Double { val r = Math.toRadians(lat.coerceIn(-85.0, 85.0)); return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 }
    }
}

/** One piece of the picture: a leg's line, walking (dotted) or by vehicle (solid), and whether it is the leg being looked at. */
data class MapSeg(val points: List<Pair<Double, Double>>, val walk: Boolean, val current: Boolean)

object MapPicture {
    /** The legs of a journey as drawable pieces, [current] being the index of the leg in focus. Legs with no geometry are skipped. */
    fun segments(legs: List<Leg>, current: Int): List<MapSeg> =
        legs.mapIndexedNotNull { i, l -> l.points?.let { p -> Polyline.decode(p).takeIf { it.size >= 2 }?.let { MapSeg(it, l.mode == "walk", i == current) } } }

    fun allPoints(segs: List<MapSeg>): List<Pair<Double, Double>> = segs.flatMap { it.points }

    /** The map box on the screen and the margin inside it; the skin and the tile loader both fit the picture the same way. */
    const val BOX_W = 456f; const val BOX_H = 392f; const val PAD = 36f
    fun fit(segs: List<MapSeg>, dot: Pair<Double, Double>?, steps: Int = 0): MapFit? =
        allPoints(segs).let { if (dot != null) it + dot else it }.takeIf { it.size >= 2 }?.let { pts ->
            // zoomed in, the picture stays on the child (or on the start of the leg being looked at); zoomed out it stays centred
            val focus = if (steps > 0) dot ?: segs.firstOrNull { it.current }?.points?.firstOrNull() else null
            MapFit(pts, BOX_W, BOX_H, PAD, steps, focus)
        }

    /** Where to draw an arrow on the edge of a [w] x [h] box pointing at a point that is off the box (null when it is inside, [inset] from the edge): x, y and the direction in radians (0 = up, clockwise). */
    fun edgeArrow(px: Float, py: Float, w: Float, h: Float, inset: Float): Triple<Float, Float, Double>? {
        if (px in inset..(w - inset) && py in inset..(h - inset)) return null
        val cx = w / 2; val cy = h / 2; val dx = px - cx; val dy = py - cy
        val sx = if (dx == 0f) Float.MAX_VALUE else ((if (dx > 0) w - inset else inset) - cx) / dx
        val sy = if (dy == 0f) Float.MAX_VALUE else ((if (dy > 0) h - inset else inset) - cy) / dy
        val s = min(sx, sy)
        return Triple(cx + dx * s, cy + dy * s, Math.atan2(dx.toDouble(), -dy.toDouble()))
    }
}
