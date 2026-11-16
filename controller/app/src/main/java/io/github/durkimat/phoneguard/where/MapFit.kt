package io.github.durkimat.phoneguard.where

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Fits a route into a rectangle of the screen: a flat (equirectangular) projection, the longitude squeezed by the cosine of the middle latitude so shapes
 * look right, scaled to fill the box with a margin and centred. North is up. Pure, tested without a phone.
 */
class MapFit(points: List<Pair<Double, Double>>, private val w: Float, private val h: Float, pad: Float) {
    private val minLat: Double; private val maxLat: Double; private val minLon: Double
    private val k: Double; private val cosLat: Double
    private val offX: Double; private val offY: Double

    init {
        val lats = points.map { it.first }; val lons = points.map { it.second }
        minLat = lats.minOrNull() ?: 0.0; maxLat = lats.maxOrNull() ?: 0.0; minLon = lons.minOrNull() ?: 0.0
        val maxLon = lons.maxOrNull() ?: 0.0
        cosLat = cos(Math.toRadians((minLat + maxLat) / 2))
        val dx = (maxLon - minLon) * cosLat; val dy = maxLat - minLat
        val bw = (w - 2 * pad).toDouble(); val bh = (h - 2 * pad).toDouble()
        k = when { dx <= 1e-9 && dy <= 1e-9 -> 0.0; dx <= 1e-9 -> bh / dy; dy <= 1e-9 -> bw / dx; else -> min(bw / dx, bh / dy) }
        offX = pad + (bw - dx * k) / 2; offY = pad + (bh - dy * k) / 2
    }

    /** Screen position inside the box for a latitude and longitude (x to the right, y down). */
    fun xy(lat: Double, lon: Double): Pair<Float, Float> = ((offX + (lon - minLon) * cosLat * k).toFloat()) to ((offY + (maxLat - lat) * k).toFloat())
}

/** One piece of the picture: a leg's line, walking (dotted) or by vehicle (solid), and whether it is the leg being looked at. */
data class MapSeg(val points: List<Pair<Double, Double>>, val walk: Boolean, val current: Boolean)

object MapPicture {
    /** The legs of a journey as drawable pieces, [current] being the index of the leg in focus. Legs with no geometry are skipped. */
    fun segments(legs: List<Leg>, current: Int): List<MapSeg> =
        legs.mapIndexedNotNull { i, l -> l.points?.let { p -> Polyline.decode(p).takeIf { it.size >= 2 }?.let { MapSeg(it, l.mode == "walk", i == current) } } }

    fun allPoints(segs: List<MapSeg>): List<Pair<Double, Double>> = segs.flatMap { it.points }
}
