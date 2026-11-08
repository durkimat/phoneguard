package io.github.durkimat.phoneguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import io.github.durkimat.phoneguard.core.Engine
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Position reports for HA, one setting per mode (see Config.location and Config.locEvery). Every N minutes an inexact wake-up asks
 * for one fix from the chosen provider (network for low accuracy, GPS for high) with a short timeout; "live" listens continuously.
 * Fixes wait in a small file and ride along with the next sync. The phone's own location switch is handled by [LocationLock].
 */
object LocationReporter {
    private const val KEEP = 60
    private const val MAX_UPLOAD = 20
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var live: LocationListener? = null
    private var lastLiveSave = 0L
    private var lastLiveSync = 0L

    private fun dir(c: Context) = c.createDeviceProtectedStorageContext()
    private fun file(c: Context) = File(dir(c).filesDir, "fixes.log")
    private fun sp(c: Context) = dir(c).getSharedPreferences("locrep", Context.MODE_PRIVATE)
    private fun alarm(c: Context) = PendingIntent.getBroadcast(c, 3, Intent(c, AlarmReceiver::class.java).putExtra("kind", "loc"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun provider(mode: String) = if (mode == "gps") LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER

    /** Called whenever the mode's settings are evaluated: set the schedule up, change it, or switch it off. */
    fun apply(c: Context, mode: String, everyMin: Int) {
        val key = "$mode/$everyMin"
        val p = sp(c)
        if (p.getString("sched", null) == key) return
        p.edit().putString("sched", key).apply()
        c.getSystemService(AlarmManager::class.java).cancel(alarm(c))
        stopLive(c)
        when {
            mode == "off" -> {}
            everyMin == 0 -> startLive(c, mode)
            else -> c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5_000, alarm(c))
        }
        Log.log(c, "location reports: $key")
    }

    /** The wake-up: one fix, then the next wake-up, then a sync to send it. */
    fun onAlarm(c: Context, pending: BroadcastReceiver.PendingResult?) {
        val eff = Engine.effective(Store.config(c), Store.boost(c), Clock.now())
        if (eff.location == "off" || eff.locEvery == 0) { pending?.finish(); return }
        val next = { c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + eff.locEvery * 60_000L, alarm(c)) }
        val done = { next(); Link.runAsync(c, "location", pending) }
        try {
            val lm = c.getSystemService(LocationManager::class.java)
            val prov = provider(eff.location)
            if (!lm.isLocationEnabled || !lm.isProviderEnabled(prov)) { Log.log(c, "location: $prov is off"); done(); return }
            val cancel = CancellationSignal()
            var finished = false
            val finish = { synchronized(this) { if (!finished) { finished = true; done() } } }
            main.postDelayed({ cancel.cancel(); finish() }, if (prov == LocationManager.GPS_PROVIDER) 60_000L else 20_000L)
            lm.getCurrentLocation(prov, cancel, pool) { loc -> if (loc != null) record(c, loc, eff.location); finish() }
        } catch (e: Exception) { Log.log(c, "location failed: ${e.javaClass.simpleName} ${e.message}"); done() }
    }

    private fun startLive(c: Context, mode: String) {
        try {
            val lm = c.getSystemService(LocationManager::class.java)
            val l = LocationListener { loc ->
                val now = System.currentTimeMillis()
                if (now - lastLiveSave >= 5_000) { lastLiveSave = now; record(c, loc, mode) }
                if (now - lastLiveSync >= 30_000) { lastLiveSync = now; Link.runAsync(c, "location live") }
            }
            lm.requestLocationUpdates(provider(mode), 1_000L, 0f, l, Looper.getMainLooper())
            live = l
        } catch (e: Exception) { Log.log(c, "live location failed: ${e.javaClass.simpleName} ${e.message}") }
    }

    private fun stopLive(c: Context) {
        live?.let { try { c.getSystemService(LocationManager::class.java).removeUpdates(it) } catch (_: Exception) { } }
        live = null
    }

    @Synchronized
    private fun record(c: Context, loc: Location, mode: String) {
        try {
            val line = JSONArray().put(System.currentTimeMillis()).put(loc.latitude).put(loc.longitude).put(loc.accuracy.toDouble()).put(loc.provider ?: "?").put(mode)
            val f = file(c)
            f.appendText(line.toString() + "\n")
            val lines = f.readLines()
            if (lines.size > KEEP) f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
        } catch (e: Exception) { Log.log(c, "location save failed: ${e.javaClass.simpleName}") }
    }

    /** Fixes not yet sent (oldest first); call [commit] once HA answered. */
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
        } catch (_: Exception) { }
        return out
    }

    @Synchronized
    fun commit(c: Context) { val p = sp(c); p.edit().putLong("uploaded", p.getLong("inflight", p.getLong("uploaded", 0))).apply() }
}
