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

    data class Result(val hid: Int, val unhid: Int, val failed: List<String>, val manageable: Set<String>)

    fun launcherPackages(c: Context): Set<String> =
        c.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName }.toSet()

    /** Everything we could manage: apps with a launcher icon, plus apps we already hid (they have no icon now). */
    fun manageable(c: Context, cfg: Config?): Set<String> =
        (launcherPackages(c) + Store.hidden(c)) - Protected.all(c, cfg)

    fun applyApps(c: Context, cfg: Config?, allowed: Allowed): Result {
        val dpm = Dpm.dpm(c)
        val admin = Dpm.admin(c)
        val prot = Protected.all(c, cfg)
        val ours = Store.hidden(c).toMutableSet()
        val mg = launcherPackages(c) + ours
        var hid = 0
        var unhid = 0
        val failed = mutableListOf<String>()
        for (pkg in mg) {
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
        for (p in listOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.CAMERA)) {
            try { dpm.setPermissionGrantState(Dpm.admin(c), c.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (_: Exception) { }
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
