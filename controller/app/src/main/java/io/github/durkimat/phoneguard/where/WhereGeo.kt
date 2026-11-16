package io.github.durkimat.phoneguard.where

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Small geometry for the map: how far a point is from a route (flat approximation, fine at walking scale). Pure, tested without a phone. */
object WhereGeo {
    /** How far from the line before the map says "Off the route". */
    const val OFF_ROUTE_M = 100.0

    /** Shortest distance in metres from (lat, lon) to the polyline; null when the line has no points. */
    fun metresToLine(lat: Double, lon: Double, line: List<Pair<Double, Double>>): Double? {
        if (line.isEmpty()) return null
        val k = cos(Math.toRadians(lat))
        fun x(lo: Double) = (lo - lon) * k * 111_320.0
        fun y(la: Double) = (la - lat) * 110_540.0
        if (line.size == 1) return hypot(x(line[0].second), y(line[0].first))
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val ax = x(line[i].second); val ay = y(line[i].first); val bx = x(line[i + 1].second); val by = y(line[i + 1].first)
            val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, (-ax * dx - ay * dy) / len2))
            best = min(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    fun offRoute(lat: Double, lon: Double, line: List<Pair<Double, Double>>): Boolean = (metresToLine(lat, lon, line) ?: 0.0) > OFF_ROUTE_M
}
