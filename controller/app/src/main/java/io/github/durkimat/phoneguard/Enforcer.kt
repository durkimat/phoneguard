package io.github.durkimat.phoneguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.durkimat.phoneguard.core.Allowed
import io.github.durkimat.phoneguard.core.Config

object Dpm {
    fun dpm(c: Context): DevicePolicyManager = c.getSystemService(DevicePolicyManager::class.java)
    fun admin(c: Context) = ComponentName(c.applicationContext, AdminReceiver::class.java)
    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)
}

/** Applies a decided state to the phone. Idempotent: running it twice changes nothing the second time. */
object Enforcer {
    /** Tested on the J36 (spec section 7). Debugging is never in the default set. */
    val DEFAULT_RESTRICTIONS = setOf(
        "no_factory_reset", "no_safe_boot", "no_config_date_time", "no_add_user", "no_add_managed_profile",
    )

    private val STOCK_HOMES = setOf("com.android.launcher3")

    data class Result(val hid: Int, val unhid: Int, val failed: List<String>, val manageable: Set<String>)

    fun launcherPackages(c: Context): Set<String> =
        c.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName }.toSet()

    /** Everything we could manage: apps with a launcher icon, plus apps we already hid (they have no icon now). */
    fun manageable(c: Context, cfg: Config?): Set<String> =
        (launcherPackages(c) + Store.hidden(c)) - Protected.all(c, cfg)

    /** True when Android already sends the Home key to our launcher (so hiding the stock one cannot leave the phone without a home screen). */
    fun weAreHome(c: Context): Boolean = try {
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == c.packageName
    } catch (_: Exception) { false }

    fun applyApps(c: Context, cfg: Config?, allowed: Allowed, hideStockHome: Boolean = false): Result {
        val dpm = Dpm.dpm(c)
        val admin = Dpm.admin(c)
        val prot = Protected.all(c, cfg)
        val ours = Store.hidden(c).toMutableSet()
        val mg = launcherPackages(c) + ours
        var hid = 0
        var unhid = 0
        val failed = mutableListOf<String>()
        for (pkg in mg) {
            if (pkg in STOCK_HOMES) continue // handled below, so it is not unhidden and hidden again every evaluation
            try {
                val isProt = pkg in prot
                val shouldHide = !isProt && !allowed.all && pkg !in allowed.pkgs
                if (shouldHide && pkg !in ours) {
                    // Already hidden by something else (for example DumberOS App Blocker): leave it alone.
                    if (!dpm.isApplicationHidden(admin, pkg)) {
                        if (dpm.setApplicationHidden(admin, pkg, true)) { ours += pkg; hid++ } else failed += pkg
                    }
                } else if (!shouldHide && pkg in ours) {
                    if (dpm.setApplicationHidden(admin, pkg, false)) { ours -= pkg; unhid++ } else failed += pkg
                }
            } catch (e: Exception) {
                failed += "$pkg(${e.javaClass.simpleName})"
            }
        }
        // the stock home screen is hidden while the Nokia screens are the home (its old window can no longer come back), and put back after
        for (pkg in STOCK_HOMES) {
            try {
                if (hideStockHome && pkg !in ours && !dpm.isApplicationHidden(admin, pkg)) { if (dpm.setApplicationHidden(admin, pkg, true)) { ours += pkg; hid++ } else failed += pkg }
                else if (!hideStockHome && pkg in ours) { if (dpm.setApplicationHidden(admin, pkg, false)) { ours -= pkg; unhid++ } else failed += pkg }
            } catch (e: Exception) { failed += "$pkg(${e.javaClass.simpleName})" }
        }
        Store.setHidden(c, ours)
        return Result(hid, unhid, failed, mg - prot)
    }

    fun applyRestrictions(c: Context, cfg: Config?) {
        val dpm = Dpm.dpm(c)
        val admin = Dpm.admin(c)
        var wanted = cfg?.restrictions ?: DEFAULT_RESTRICTIONS
        if (BuildConfig.DEBUG) wanted = wanted - "no_debugging_features" // never cut adb on a test build
        val prev = Store.restrictions(c)
        (prev - wanted).forEach { try { dpm.clearUserRestriction(admin, it) } catch (_: Exception) { } }
        val ok = mutableSetOf<String>()
        wanted.forEach { try { dpm.addUserRestriction(admin, it); ok += it } catch (e: Exception) { Log.log(c, "restriction $it failed: ${e.javaClass.simpleName}") } }
        Store.setRestrictions(c, ok)
        if ("no_config_date_time" in wanted) {
            try { dpm.setAutoTimeEnabled(admin, true); dpm.setAutoTimeZoneEnabled(admin, true) } catch (_: Exception) { }
        }
    }

    /** Keeps the controller and Companion apps from being force-stopped or cleared from Settings. */
    fun protectProcesses(c: Context) {
        val dpm = Dpm.dpm(c)
        val pkgs = listOf(c.packageName, "io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")
            .filter { try { c.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false } }
        try { dpm.setUserControlDisabledPackages(Dpm.admin(c), pkgs) } catch (e: Exception) { Log.log(c, "protectProcesses failed: ${e.javaClass.simpleName}") }
    }

    fun grantOwnPermissions(c: Context) {
        val dpm = Dpm.dpm(c)
        tryGrantUsageAccess(c)
        val admin = Dpm.admin(c)
        fun grant(p: String) {
            try {
                if (dpm.getPermissionGrantState(admin, c.packageName, p) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                    dpm.setPermissionGrantState(admin, c.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (_: Exception) { }
        }
        grant("android.permission.WRITE_SETTINGS")
        for (p in listOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            android.Manifest.permission.NEARBY_WIFI_DEVICES, android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.READ_SMS, android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR, android.Manifest.permission.SEND_SMS, android.Manifest.permission.CALL_PHONE, android.Manifest.permission.ANSWER_PHONE_CALLS)) {
            grant(p)
        }
    }

    /** Undo everything we did: used when a parent releases the phone. */
    fun restoreEverything(c: Context) {
        val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
        Store.hidden(c).forEach { try { dpm.setApplicationHidden(admin, it, false) } catch (_: Exception) { } }
        Store.setHidden(c, emptySet())
        Store.restrictions(c).forEach { try { dpm.clearUserRestriction(admin, it) } catch (_: Exception) { } }
        Store.setRestrictions(c, emptySet())
        try { dpm.setUserControlDisabledPackages(admin, emptyList()) } catch (_: Exception) { }
        setGray(c, false)
        Log.log(c, "restored: nothing hidden, no restrictions, grayscale off")
    }

    fun setStatusBar(c: Context, disabled: Boolean) {
        try { Dpm.dpm(c).setStatusBarDisabled(Dpm.admin(c), disabled) } catch (e: Exception) { Log.log(c, "status bar change failed: ${e.javaClass.simpleName}") }
    }

    /** Usage access is an app-op: a device owner may be able to grant it itself, otherwise adb at setup. */
    fun tryGrantUsageAccess(c: Context): Boolean {
        if (Usage.allowed(c)) return true
        try {
            Dpm.dpm(c).setPermissionGrantState(Dpm.admin(c), c.packageName, "android.permission.PACKAGE_USAGE_STATS",
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        } catch (_: Exception) { }
        return Usage.allowed(c)
    }

    private const val GRAY = "accessibility_display_daltonizer_enabled"

    /** Switch our side-button service on (a device owner holding WRITE_SECURE_SETTINGS needs no prompt); it is only ever added, never removed. */
    fun ensureSideButton(c: Context) {
        if (!canWriteSecure(c)) return
        try {
            val me = "${c.packageName}/${c.packageName}.keys.F2Service"
            val cur = Settings.Secure.getString(c.contentResolver, "enabled_accessibility_services").orEmpty()
            if (me !in cur.split(':')) Settings.Secure.putString(c.contentResolver, "enabled_accessibility_services", if (cur.isEmpty()) me else "$cur:$me")
            if (Settings.Secure.getInt(c.contentResolver, "accessibility_enabled", 0) != 1) Settings.Secure.putInt(c.contentResolver, "accessibility_enabled", 1)
        } catch (e: Exception) { Log.log(c, "side button service: ${e.javaClass.simpleName}") }
    }

    /** Switch our notification listener on (only ever added). */
    fun ensureNoteListener(c: Context) {
        if (!canWriteSecure(c)) return
        try {
            val me = "${c.packageName}/${c.packageName}.notes.NoteService"
            val cur = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
            if (me !in cur.split(':')) Settings.Secure.putString(c.contentResolver, "enabled_notification_listeners", if (cur.isEmpty()) me else "$cur:$me")
        } catch (e: Exception) { Log.log(c, "notification listener: ${e.javaClass.simpleName}") }
    }

    fun canWriteSecure(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun isGray(c: Context) = Settings.Secure.getInt(c.contentResolver, GRAY, 0) == 1

    fun setGray(c: Context, on: Boolean): Boolean {
        Store.setWantGray(c, on)
        if (!canWriteSecure(c)) { if (on) Log.log(c, "grayscale wanted but WRITE_SECURE_SETTINGS is not granted"); return false }
        if (isGray(c) == on) return true
        return try {
            Settings.Secure.putInt(c.contentResolver, "accessibility_display_daltonizer", 0)
            Settings.Secure.putInt(c.contentResolver, GRAY, if (on) 1 else 0)
        } catch (e: Exception) { Log.log(c, "grayscale write failed: ${e.javaClass.simpleName}"); false }
    }

    fun grayKey() = GRAY
}
