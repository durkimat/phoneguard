package io.github.durkimat.phoneguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.net.TrafficStats
import io.github.durkimat.phoneguard.core.Engine
import io.github.durkimat.phoneguard.launcher.LauncherData
import org.json.JSONArray
import java.io.File

/**
 * A small battery diary kept on the phone and sent to HA with each sync: a line every few minutes and at every event that matters
 * (screen on or off, plugged in or out, a mode change, a sync). Each line is a JSON array so it stays short:
 * [time ms, level %, charge counter µAh, current now µA, plugged 0/1, screen on 0/1, mode, signal bars, Wi-Fi 0/1, location 0/1,
 *  temperature in tenths of a degree, our app's network bytes, reason]. The charge counter has far finer steps than the percentage.
 */
object PowerLog {
    private const val KEEP_MS = 3L * 24 * 3600 * 1000
    private const val MIN_GAP_MS = 60_000L
    private const val MAX_UPLOAD = 400

    private fun dir(c: Context) = c.createDeviceProtectedStorageContext()
    private fun file(c: Context) = File(dir(c).filesDir, "power.log")
    private fun sp(c: Context) = dir(c).getSharedPreferences("power", Context.MODE_PRIVATE)

    @Synchronized
    fun sample(c: Context, why: String, force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            val p = sp(c)
            if (!force && now - p.getLong("last", 0) < MIN_GAP_MS) return
            val bm = c.getSystemService(BatteryManager::class.java)
            val sticky = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
            val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val screen = c.getSystemService(PowerManager::class.java).isInteractive
            val mode = try { Engine.effective(Store.config(c), Store.boost(c), Clock.now()).modeId } catch (_: Exception) { "?" }
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val wifi = try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } catch (_: Exception) { false }
            val loc = try { c.getSystemService(android.location.LocationManager::class.java).isLocationEnabled } catch (_: Exception) { false }
            val uid = Process.myUid()
            val bytes = (TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)) + (TrafficStats.getUidTxBytes(uid).coerceAtLeast(0))
            val line = JSONArray().put(now).put(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)).put(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                .put(if (plugged) 1 else 0).put(if (screen) 1 else 0).put(mode).put(LauncherData.signal(c)).put(if (wifi) 1 else 0).put(if (loc) 1 else 0)
                .put(temp).put(bytes).put(why)
            file(c).appendText(line.toString() + "\n")
            p.edit().putLong("last", now).putInt("n", p.getInt("n", 0) + 1).apply()
            if (p.getInt("n", 0) % 200 == 0) prune(c, now)
        } catch (e: Exception) { Log.log(c, "power sample failed: ${e.javaClass.simpleName} ${e.message?.take(80)}") }
    }

    private fun prune(c: Context, now: Long) {
        val f = file(c)
        if (!f.exists()) return
        val keep = f.readLines().filter { l -> try { JSONArray(l).getLong(0) >= now - KEEP_MS } catch (_: Exception) { false } }
        f.writeText(keep.joinToString("\n") + if (keep.isEmpty()) "" else "\n")
    }

    /** Lines not yet sent to HA (oldest first, at most [MAX_UPLOAD]); call [commit] once HA has answered. */
    @Synchronized
    fun pending(c: Context): JSONArray {
        val out = JSONArray()
        try {
            val p = sp(c); val since = p.getLong("uploaded", 0)
            var last = since
            file(c).takeIf { it.exists() }?.forEachLine { l ->
                try { val a = JSONArray(l); val t = a.getLong(0); if (t > since && out.length() < MAX_UPLOAD) { out.put(a); last = t } } catch (_: Exception) { }
            }
            p.edit().putLong("inflight", last).apply()
        } catch (e: Exception) { Log.log(c, "power read failed: ${e.javaClass.simpleName}") }
        return out
    }

    @Synchronized
    fun commit(c: Context) { val p = sp(c); p.edit().putLong("uploaded", p.getLong("inflight", p.getLong("uploaded", 0))).apply() }

    /** One diary line, reduced to what the drain rate needs. */
    data class Point(val t: Long, val uah: Long, val plugged: Boolean)

    /** mAh used per hour while on battery, from consecutive points (gaps over three hours and charging ignored); null if there is nothing to go on. */
    fun rate(points: List<Point>): Double? {
        var used = 0.0; var hours = 0.0
        for ((a, b) in points.zip(points.drop(1))) {
            val dt = b.t - a.t
            if (dt < 30_000 || dt > 3 * 3600_000 || a.plugged || b.plugged) continue
            val d = (a.uah - b.uah) / 1000.0
            if (d < -1) continue
            used += d.coerceAtLeast(0.0); hours += dt / 3600_000.0
        }
        return if (hours >= 0.25) used / hours else null
    }

    /** The last [hours] hours of the diary as points. */
    fun recent(c: Context, hours: Int = 12): List<Point> {
        val out = ArrayList<Point>()
        try {
            val since = System.currentTimeMillis() - hours * 3600_000L
            file(c).takeIf { it.exists() }?.forEachLine { l ->
                try { val a = JSONArray(l); val t = a.getLong(0); if (t >= since) out.add(Point(t, a.getLong(2), a.getInt(4) == 1)) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return out
    }
}
