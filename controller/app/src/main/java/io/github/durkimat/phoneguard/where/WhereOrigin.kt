package io.github.durkimat.phoneguard.where

import android.content.Context
import android.location.LocationManager
import io.github.durkimat.phoneguard.Store

/**
 * Where the way starts. The newest recent position the phone already has (no new fix is asked for, so the mode's location rule is respected: when the mode
 * keeps location off there is none); otherwise the home place the parent set (the weather location), said plainly as "home". Null when neither exists.
 */
object WhereOrigin {
    class Origin(val lat: Double, val lon: Double, val label: String)
    private const val FRESH_MS = 20 * 60_000L

    fun get(c: Context): Origin? {
        try {
            val lm = c.getSystemService(LocationManager::class.java)
            if (lm.isLocationEnabled) {
                val now = System.currentTimeMillis()
                val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null } }
                    .filter { now - it.time <= FRESH_MS }.maxByOrNull { it.time }
                if (best != null) return Origin(best.latitude, best.longitude, "my position")
            }
        } catch (_: Exception) { }
        val cfg = Store.config(c)
        val lat = cfg?.lat; val lon = cfg?.lon
        return if (lat != null && lon != null) Origin(lat, lon, "home") else null
    }
}

/** The plain words for a failed place or journey request. */
object WhereReply {
    fun message(code: Int): String = when (code) {
        400 -> "Can't plan that one"
        403 -> "Where to? is off"
        429 -> "Used up for today"
        else -> "Maps aren't working right now"
    }
}
