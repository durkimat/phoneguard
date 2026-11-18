package io.github.durkimat.phoneguard.whatsapp

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Controller
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.core.Engine
import io.github.durkimat.phoneguard.core.SetupRun
import io.github.durkimat.phoneguard.core.SetupWindow
import org.json.JSONObject

/**
 * Runs the WhatsApp setup window on the phone (rules in [SetupWindow]). The run is remembered in the app's own storage; while it lasts [Store.boost] shows it as a boost of
 * the built-in setup mode, so everything that already reads the effective settings (apps, touch, calls, radios) follows it, and when it ends the mode in force just carries on.
 */
object SetupRunner {
    private fun prefs(c: Context) = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("wasetup", Context.MODE_PRIVATE)
    @Volatile private var lock: PowerManager.WakeLock? = null

    private fun run(c: Context): SetupRun? {
        val t = prefs(c).getString("run", null) ?: return null
        return try { JSONObject(t).let { SetupRun(it.getLong("at"), it.getLong("el"), it.getInt("x"), it.getInt("r")) } } catch (_: Exception) { null }
    }

    /** When the running window ends (null when none is running). A window that is over, cancelled or from before a reboot is cleared here. */
    fun until(c: Context): Long? {
        val r = run(c) ?: return null
        val u = SetupWindow.until(Store.config(c)?.whatsapp, r, Clock.now(), SystemClock.elapsedRealtime())
        if (u == null) finish(c, "window over")
        return u
    }

    fun active(c: Context) = until(c) != null

    /** Whether "Set up" may be offered now: allowed by the companion, SIM method, and the mode in force allows WhatsApp at all. */
    fun canStart(c: Context): Boolean {
        val cfg = Store.config(c)
        if (!SetupWindow.canStart(cfg?.whatsapp, Clock.now())) return false
        return Engine.effective(cfg, Store.storedBoost(c), Clock.now()).wa != "off"
    }

    fun start(c: Context): Boolean {
        if (active(c)) return true
        if (!canStart(c)) return false
        val r = SetupWindow.start(Store.config(c)?.whatsapp, Clock.now(), SystemClock.elapsedRealtime()) ?: return false
        prefs(c).edit().putString("run", JSONObject().put("at", r.startedAt).put("el", r.startedElapsed).put("x", r.extraAtStart).put("r", r.endRevAtStart).toString()).apply()
        try {
            val l = c.getSystemService(PowerManager::class.java).newWakeLock(@Suppress("DEPRECATION") (PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP), "phoneguard:wasetup")
            l.acquire(90 * 60_000L); lock = l
        } catch (e: Exception) { Log.log(c, "setup window: no wake lock (${e.javaClass.simpleName})") }
        Log.log(c, "WhatsApp setup window started")
        Controller.evaluateAsync(c, "setup window")
        return true
    }

    /** Done pressed, or the window is over: the mode in force is back exactly as it was. */
    fun end(c: Context) { if (run(c) != null) finish(c, "ended") }

    private fun finish(c: Context, why: String) {
        prefs(c).edit().remove("run").apply()
        try { lock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        lock = null
        Log.log(c, "WhatsApp setup window $why")
        Controller.evaluateAsync(c, "setup window end")
    }

    /** This phone's own number as Android reports it (best effort: may be blank), to prefill the link screen. */
    @android.annotation.SuppressLint("MissingPermission", "HardwareIds")
    fun simNumber(c: Context): String = try { c.getSystemService(android.telephony.TelephonyManager::class.java).line1Number.orEmpty() } catch (_: Exception) { "" }

    /** SIM method after setup: the official app should stay in the background (it only opens inside the setup window or where the mode shows it in full). */
    fun officialBlocked(c: Context): Boolean {
        val cfg = Store.config(c) ?: return false
        if (cfg.whatsapp?.setup != "sim" || active(c)) return false
        return Engine.effective(cfg, Store.boost(c), Clock.now()).waApp != "full"
    }

    private fun officialVersion(c: Context): String? = try { c.packageManager.getPackageInfo("com.whatsapp", 0).versionName } catch (_: Exception) { null }

    /** Tells pg-provision (root) whether to keep the official app quiet and unrestricted in the background: Settings.Global pg_wa_quiet, written only when it changes. */
    fun syncQuiet(c: Context) {
        val want = if (Store.config(c)?.whatsapp?.setup == "sim" && officialVersion(c) != null) "1" else "0"
        try {
            if (android.provider.Settings.Global.getString(c.contentResolver, "pg_wa_quiet") != want) android.provider.Settings.Global.putString(c.contentResolver, "pg_wa_quiet", want)
        } catch (e: Exception) { Log.log(c, "wa quiet flag: ${e.javaClass.simpleName}") }
    }

    /** For the check-in: whether the companion has allowed it and whether it is running. */
    fun report(c: Context): JSONObject {
        val cfg = Store.config(c)?.whatsapp
        return JSONObject().put("allowed", SetupWindow.canStart(cfg, Clock.now())).put("active", until(c) != null).put("until", until(c) ?: 0L)
            .put("official", JSONObject().put("installed", officialVersion(c) != null).put("version", officialVersion(c) ?: ""))
    }
}
