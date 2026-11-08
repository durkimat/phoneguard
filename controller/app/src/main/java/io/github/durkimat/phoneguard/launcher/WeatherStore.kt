package io.github.durkimat.phoneguard.launcher

import android.content.Context
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.Store
import java.net.HttpURLConnection
import java.net.URL

/** Keeps the last weather answer and fetches a new one now and then, gently: at most every 30 minutes, 15 after a failure. */
object WeatherStore {
    private const val FRESH_MS = 30 * 60_000L
    private const val RETRY_MS = 15 * 60_000L
    private const val STALE_MS = 3 * 60 * 60_000L
    @Volatile private var busy = false

    private fun sp(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun hasLocation(c: Context) = Store.config(c)?.let { it.lat != null && it.lon != null } == true

    /** The last weather, unless there is none or it is more than three hours old (better nothing than wrong). */
    fun current(c: Context): WeatherData? {
        if (!hasLocation(c)) return null
        val text = sp(c).getString("json", null) ?: return null
        val d = WeatherData.parse(text, sp(c).getLong("at", 0)) ?: return null
        return if (Clock.now() - d.fetched > STALE_MS) null else d
    }

    /** Fetch in the background if the location is set and the answer is old. Safe to call often. */
    fun refreshIfDue(c: Context, onDone: () -> Unit) {
        val cfg = Store.config(c) ?: return
        val lat = cfg.lat ?: return; val lon = cfg.lon ?: return
        val p = sp(c); val now = Clock.now()
        val key = "$lat,$lon"
        val sameSpot = p.getString("spot", null) == key
        if (busy || (sameSpot && now - p.getLong("at", 0) < FRESH_MS) || now - p.getLong("tried", 0) < RETRY_MS && sameSpot) return
        busy = true
        Thread {
            try {
                p.edit().putLong("tried", now).apply()
                val conn = URL(WeatherData.url(lat, lon)).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "PhoneGuard/1.0 (family phone)")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (WeatherData.parse(text, now) != null) p.edit().putString("json", text).putLong("at", now).putString("spot", key).apply()
            } catch (e: Exception) { Log.log(c, "weather: ${e.javaClass.simpleName}") }
            finally { busy = false; onDone() }
        }.start()
    }
}
