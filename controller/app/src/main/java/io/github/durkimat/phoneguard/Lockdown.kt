package io.github.durkimat.phoneguard

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import io.github.durkimat.phoneguard.core.Lockdown
import io.github.durkimat.phoneguard.launcher.LauncherActivity
import io.github.durkimat.phoneguard.launcher.LauncherData

/**
 * The School-style lockdown, one setting at a time. Each function takes the wanted state and makes the
 * phone match, so calling them again with the same answer changes nothing.
 */
object LockdownEnforcer {
    private val main = Handler(Looper.getMainLooper())

    fun apply(c: Context, ld: Lockdown, visible: Set<String>) {
        Pregrant.run(c, visible)
        HomeApp.apply(c, ld.launcher == "nokia")
        Enforcer.setStatusBar(c, disabled = !ld.shade || ld.kiosk)
        TouchBlocker.set(c, block = !ld.touch)
        HardKeys.menuKeyOverlay(c, allowed = ld.shade && !ld.kiosk)
        // the kiosk allows what the launcher menu shows, nothing else (Settings is visible but must stay out of reach)
        Kiosk.apply(c, ld.kiosk, LauncherData.items(c).map { it.pkg }.toSet())
    }

    /** Put everything back: used on release and by the debug reset. */
    fun releaseAll(c: Context) {
        TouchBlocker.set(c, false)
        Kiosk.apply(c, false, emptySet())
        Enforcer.setStatusBar(c, disabled = false)
        HardKeys.menuKeyOverlay(c, allowed = true)
        try { Dpm.dpm(c).clearPackagePersistentPreferredActivities(Dpm.admin(c), c.packageName) } catch (_: Exception) { }
        HomeApp.apply(c, false)
    }
}

/**
 * DumberOS opens its own "notifications and quick settings" window from the Menu key (the left key
 * under the screen), whatever the status bar says. That key's action is a standard LineageOS button
 * setting, so while the shade is meant to be off we set it to "Nothing" and put the original back
 * afterwards. The write permission is a normal one, granted at install.
 */
object HardKeys {
    private val uri = android.net.Uri.parse("content://lineagesettings/system")
    private val keys = listOf("key_menu_action", "key_menu_long_press_action")

    private fun read(c: Context, name: String): String? = try {
        c.contentResolver.query(uri, arrayOf("value"), "name=?", arrayOf(name), null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    /**
     * Only ever insert. Deleting (or updating by selection) makes the provider notify the whole table, and
     * DumberOS's InputSettingsObserver then throws a NullPointerException that takes down system_server.
     * An absent setting is restored by writing the platform default instead.
     */
    private val defaults = mapOf("key_menu_action" to "1", "key_menu_long_press_action" to "0")

    private fun write(c: Context, name: String, value: String?) {
        c.contentResolver.insert(uri, android.content.ContentValues().apply { put("name", name); put("value", value ?: defaults[name] ?: "0") })
    }

    fun menuKeyOverlay(c: Context, allowed: Boolean) {
        try {
            val saved = Store.menuKeySaved(c)
            if (!allowed) {
                if (saved == null) {
                    val snap = org.json.JSONObject()
                    keys.forEach { snap.put(it, read(c, it) ?: org.json.JSONObject.NULL) }
                    Store.setMenuKeySaved(c, snap.toString())
                }
                // press: "voice search" (4) starts a VOICE_ASSIST activity our launcher catches; long press: nothing
                write(c, "key_menu_action", "4"); write(c, "key_menu_long_press_action", "0")
                AssistPin.apply(c, true)
            } else if (saved != null) {
                val snap = org.json.JSONObject(saved)
                keys.forEach { write(c, it, if (snap.isNull(it)) null else snap.getString(it)) }
                Store.setMenuKeySaved(c, null)
                AssistPin.apply(c, false)
            }
        } catch (e: Exception) { Log.log(c, "menu key setting unavailable: ${e.javaClass.simpleName}") }
    }

    fun isDisabled(c: Context) = read(c, "key_menu_action") == "4"
}

/** Makes our launcher the one and only handler of VOICE_ASSIST, so the left key never shows a chooser. */
const val VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"
const val WEB_SEARCH = "android.speech.action.WEB_SEARCH" // what DumberOS starts for the "voice search" key action

object AssistPin {
    fun apply(c: Context, on: Boolean) {
        val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
        try {
            if (!on) return // the pin is cleared with the Home pin when the Home app changes back
            for (a in listOf(VOICE_ASSIST, WEB_SEARCH)) {
                val f = IntentFilter(a).apply { addCategory(Intent.CATEGORY_DEFAULT) }
                dpm.addPersistentPreferredActivity(admin, f, ComponentName(c, LauncherActivity::class.java))
            }
        } catch (e: Exception) { Log.log(c, "assist pin failed: ${e.javaClass.simpleName}") }
    }
}

/** Which Home app is the default. Set explicitly, because a second Home app makes Android ask every time. */
object HomeApp {
    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** The launcher used when we are not showing ours: the system's own, never ours. */
    fun normalHome(c: Context): ComponentName? {
        val all = c.packageManager.queryIntentActivities(homeIntent(), 0).map { it.activityInfo }
            .filter { it.packageName != c.packageName && it.packageName != "com.android.settings" }
        val pick = all.firstOrNull { it.packageName == "com.android.launcher3" } ?: all.firstOrNull() ?: return null
        return ComponentName(pick.packageName, pick.name)
    }

    fun target(c: Context, nokia: Boolean): ComponentName? =
        if (nokia) ComponentName(c, LauncherActivity::class.java) else normalHome(c)

    fun apply(c: Context, nokia: Boolean) {
        val want = target(c, nokia) ?: return
        val now = c.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        if (now != null && now.packageName == want.packageName && now.name == want.className) return
        val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
        try {
            for (pkg in setOf(c.packageName, "com.android.launcher3", want.packageName)) dpm.clearPackagePersistentPreferredActivities(admin, pkg)
            val f = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
            dpm.addPersistentPreferredActivity(admin, f, want)
            Log.log(c, "Home app is now ${want.packageName}")
        } catch (e: Exception) { Log.log(c, "could not set the Home app: ${e.javaClass.simpleName}") }
    }
}

/** A transparent full-screen window that swallows every touch. Keys are unaffected. */
object TouchBlocker {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    @Volatile var wanted = false

    fun canDraw(c: Context) = Settings.canDrawOverlays(c)

    fun set(c: Context, block: Boolean) {
        wanted = block
        val app = c.applicationContext
        main.post {
            if (block && view == null) {
                if (!canDraw(app)) { Log.log(app, "touch blocking wanted but draw-over-apps is not granted"); return@post }
                val v = View(app).apply { setOnTouchListener { _, _ -> true } }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                try { app.getSystemService(WindowManager::class.java).addView(v, lp); view = v; Log.log(app, "touch blocked") }
                catch (e: Exception) { Log.log(app, "touch overlay failed: ${e.javaClass.simpleName}") }
            } else if (!block && view != null) {
                try { app.getSystemService(WindowManager::class.java).removeView(view) } catch (_: Exception) { }
                view = null; Log.log(app, "touch enabled")
            }
        }
    }

    fun isOn() = view != null
}

/** Lock-task ("kiosk") mode: stay inside the allowed apps, no recents, no power menu. */
object Kiosk {
    /** Apps that must run for calls, typing and permission questions to work inside the lock. */
    private val infrastructure = listOf(
        "com.android.server.telecom", "com.android.phone", "com.android.dialer", "com.android.contacts",
        "io.github.sspanak.tt9", "com.google.android.gms", "com.android.permissioncontroller",
        "com.android.providers.telephony", "com.android.emergency",
    )

    fun locked(c: Context) = c.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED

    fun apply(c: Context, on: Boolean, visible: Set<String>) {
        val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
        try {
            if (!on) {
                val was = locked(c)
                dpm.setLockTaskPackages(admin, emptyArray())
                dpm.setLockTaskFeatures(admin, 0)
                if (was) {
                    Log.log(c, "kiosk off, back to the Home app")
                    c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return
            }
            dpm.setLockTaskPackages(admin, (visible + infrastructure + c.packageName).toTypedArray())
            // Everything off except the keyguard: lock-task otherwise switches the PIN screen off, so a
            // child could lock the phone and wake it without ever needing the PIN.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
            if (!locked(c)) {
                val i = Intent(c, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                c.startActivity(i, ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }.toBundle())
                Log.log(c, "kiosk on")
            }
        } catch (e: Exception) { Log.log(c, "kiosk change failed: ${e.javaClass.simpleName}: ${e.message}") }
    }
}

/**
 * Runtime permissions an allowed app would otherwise ask for on first use. With touch off a prompt
 * cannot be answered reliably, so a device owner answers them in advance. Only non-sensor groups:
 * camera, microphone and location stay a parent's decision.
 */
object Pregrant {
    private val ok = setOf(
        "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "android.permission.GET_ACCOUNTS",
        "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.CALL_PHONE",
        "android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS", "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
        "android.permission.RECEIVE_MMS", "android.permission.RECEIVE_WAP_PUSH", "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
    )

    fun run(c: Context, pkgs: Set<String>) {
        val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
        var done = 0
        for (pkg in pkgs) {
            val info = try { c.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) } catch (_: Exception) { continue }
            val key = "$pkg:${info.longVersionCode}"
            if (key in Store.pregranted(c)) continue
            for (perm in info.requestedPermissions.orEmpty()) {
                if (perm in ok) try { dpm.setPermissionGrantState(admin, pkg, perm, android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED); done++ } catch (_: Exception) { }
            }
            Store.addPregranted(c, key)
        }
        if (done > 0) Log.log(c, "pre-granted $done permissions")
    }
}
