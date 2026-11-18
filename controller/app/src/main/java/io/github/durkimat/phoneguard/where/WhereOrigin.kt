package io.github.durkimat.phoneguard.where

import android.content.Context
import android.location.LocationManager
import io.github.durkimat.phoneguard.Store

/**
 * Where the way starts. A position under two minutes old if the phone has one; otherwise a fresh one-off fix is asked for and waited on (up to the time the
 * caller allows), which is allowed because the mode that offers Where to? always has location on; otherwise the newest position under 20 minutes old;
 * otherwise the home place the parent set (the weather location), said plainly as "home". Null when none of these exists.
 */
object WhereOrigin {
    /** [lastKnown]: not a current fix (a position a few minutes old, or the home place); the answer then says so. */
    class Origin(val lat: Double, val lon: Double, val label: String, val lastKnown: Boolean = false)
    private const val NEW_MS = 2 * 60_000L
    private const val FRESH_MS = 20 * 60_000L

    fun get(c: Context, waitMs: Long = 0): Origin? {
        try {
            val lm = c.getSystemService(LocationManager::class.java)
            if (lm.isLocationEnabled) {
                val now = System.currentTimeMillis()
                val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null } }.maxByOrNull { it.time }
                if (best != null && now - best.time <= NEW_MS) return Origin(best.latitude, best.longitude, "my position")
                if (waitMs > 0) fix(lm, waitMs)?.let { return Origin(it.latitude, it.longitude, "my position") }
                if (best != null && now - best.time <= FRESH_MS) return Origin(best.latitude, best.longitude, "my position", true)
            }
        } catch (_: Exception) { }
        val cfg = Store.config(c)
        val lat = cfg?.lat; val lon = cfg?.lon
        return if (lat != null && lon != null) Origin(lat, lon, "home", true) else null
    }

    /** One fix asked for now (GPS when it is on, else the network), waited on for up to [waitMs]. */
    private fun fix(lm: LocationManager, waitMs: Long): android.location.Location? {
        val provider = when { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER; lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER; else -> return null }
        val got = java.util.concurrent.atomic.AtomicReference<android.location.Location?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        val cancel = android.os.CancellationSignal()
        try {
            lm.getCurrentLocation(provider, cancel, { it.run() }) { l -> got.set(l); latch.countDown() }
            latch.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: SecurityException) { } catch (_: Exception) { } finally { cancel.cancel() }
        return got.get()
    }
}

/** The plain words for a failed place or journey request. */
object WhereReply {
    /** The words when a place search fails: a service that is down is "Can't search places now"; the rest as for any request. */
    fun search(code: Int): String = if (code == 503) "Can't search places now" else message(code)

    fun message(code: Int): String = when (code) {
        400 -> "Can't plan that one"
        403 -> "Where to? is off in this mode"
        429 -> "Used up for today"
        else -> "Maps aren't working right now"
    }
}
