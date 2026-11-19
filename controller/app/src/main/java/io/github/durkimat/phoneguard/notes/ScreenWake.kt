package io.github.durkimat.phoneguard.notes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import io.github.durkimat.phoneguard.Log

/**
 * A notification wakes the screen for about fifteen seconds, not the usual minute. The phone's own screen-off timeout is the only thing that
 * decides how long a woken screen stays lit, so for the wake it is lowered to [SHORT_MS] (the original is kept in device-protected prefs, so a
 * crash or reboot cannot lose it) and put back as soon as someone presses a key or touches the screen ([touched]), when the screen goes off, or
 * after a safety delay. Alarms, timers, calls, the camera and the map hold the screen their own way and are not touched by this.
 */
object ScreenWake {
    const val SHORT_MS = 15_000L
    private const val KEY = "screen_off_timeout"
    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var restore: Runnable? = null

    private fun sp(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("screenwake", Context.MODE_PRIVATE)

    /** Wakes the screen if it is off; the short timeout then runs from the wake. */
    @Synchronized fun wake(c: Context) {
        val app = c.applicationContext
        try {
            val pm = app.getSystemService(PowerManager::class.java)
            val sp = sp(app)
            if (!sp.contains("orig")) {
                val cur = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
                if (cur > SHORT_MS) { sp.edit().putInt("orig", cur).commit(); Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, SHORT_MS.toInt()) }
            }
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "phoneguard:message").acquire(2_000L)
            } // already lit: it is in use, and its own timeout runs
            if (receiver == null) {
                val r = object : BroadcastReceiver() { override fun onReceive(ctx: Context, i: Intent) { restore(ctx, "screen off") } }
                app.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF)); receiver = r
            }
            restore?.let { main.removeCallbacks(it) }
            val run = Runnable { restore(app, "safety delay") }
            restore = run; main.postDelayed(run, SHORT_MS + 10_000L)
        } catch (e: Exception) { Log.log(app, "screenwake: ${e.javaClass.simpleName}") }
    }

    /** Someone pressed a key or touched the screen: the normal timeout takes over from here. */
    fun touched(c: Context) { if (receiver != null || sp(c).contains("orig")) restore(c, "touched") }

    /** A short timeout left behind by a restart (nothing in this process is waiting to put it back). */
    fun restoreIfStale(c: Context) { if (receiver == null && sp(c).contains("orig")) restore(c, "left over") }

    /** Puts the original timeout back. */
    @Synchronized fun restore(c: Context, why: String) {
        val app = c.applicationContext
        try {
            restore?.let { main.removeCallbacks(it) }; restore = null
            receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) { } }; receiver = null
            val sp = sp(app)
            val orig = sp.getInt("orig", -1)
            if (orig > 0) {
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, orig)
                sp.edit().remove("orig").commit()
                Log.log(app, "screenwake: timeout back to ${orig / 1000} s ($why)")
            }
        } catch (e: Exception) { Log.log(app, "screenwake: restore ${e.javaClass.simpleName}") }
    }
}
