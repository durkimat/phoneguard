package io.github.durkimat.phoneguard

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/** Experiments for the School lockdown (debug builds only). Everything here is reversible from adb. */
object LabLockdown {
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayTimeout: Runnable? = null

    val ALLOWED = listOf(
        "com.android.launcher3", "com.android.dialer", "com.android.messaging", "com.android.deskclock",
        "com.android.server.telecom", "com.android.phone", "com.android.contacts", "io.github.sspanak.tt9",
        "com.google.android.gms",
    )

    /** Start the normal launcher inside lock-task mode. features: 0 blocks everything the shade, home, recents and power menu offer. */
    fun lockStart(c: Context, features: Int): String {
        val dpm = Dpm.dpm(c)
        val admin = Dpm.admin(c)
        dpm.setLockTaskPackages(admin, (ALLOWED + c.packageName).toTypedArray())
        dpm.setLockTaskFeatures(admin, features)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val info = c.packageManager.resolveActivity(home, 0)?.activityInfo ?: return "no home activity"
        home.setClassName(info.packageName, info.name)
        val opts = ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }
        c.startActivity(home, opts.toBundle())
        return "started ${info.packageName} features=$features packages=${ALLOWED.size + 1}"
    }

    fun lockStop(c: Context): String {
        Dpm.dpm(c).setLockTaskPackages(Dpm.admin(c), emptyArray())
        Dpm.dpm(c).setLockTaskFeatures(Dpm.admin(c), 0)
        return "lock task packages cleared"
    }

    fun lockState(c: Context): String =
        "lockTaskModeState=${c.getSystemService(ActivityManager::class.java).lockTaskModeState} (0 none, 1 locked, 2 pinned by the user)"

    fun tryGrantOverlay(c: Context): String {
        val ok = try {
            Dpm.dpm(c).setPermissionGrantState(Dpm.admin(c), c.packageName, "android.permission.SYSTEM_ALERT_WINDOW",
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        } catch (e: Exception) { false }
        return "selfGrant=$ok canDrawOverlays=${Settings.canDrawOverlays(c)}"
    }

    /** A transparent full-screen window that swallows every touch. Removes itself after [seconds] so a test can never strand the phone. */
    fun overlayOn(c: Context, seconds: Int): String {
        if (!Settings.canDrawOverlays(c)) return "draw-over-apps not granted (adb shell appops set ${c.packageName} SYSTEM_ALERT_WINDOW allow)"
        overlayOff(c)
        val wm = c.getSystemService(WindowManager::class.java)
        val v = View(c).apply { setOnTouchListener { _, _ -> true } }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(v, lp)
        overlay = v
        overlayTimeout = Runnable { overlayOff(c); Log.log(c, "overlay timed out and was removed") }.also { main.postDelayed(it, seconds * 1000L) }
        return "overlay on for ${seconds}s"
    }

    fun overlayOff(c: Context): String {
        overlayTimeout?.let { main.removeCallbacks(it) }; overlayTimeout = null
        val v = overlay ?: return "overlay was not on"
        try { c.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) { }
        overlay = null
        return "overlay off"
    }
}
