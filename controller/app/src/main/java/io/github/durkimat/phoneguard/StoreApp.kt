package io.github.durkimat.phoneguard

import android.content.Context
import io.github.durkimat.phoneguard.core.AppCmd
import org.json.JSONObject

/**
 * Aurora Store: the ONE app Phone Guard will install on a parent's request (owner decision 2026-11-15, replacing "other apps are never installed from here").
 * The package, the address, the checksum and the size are fixed here, in the app, and are not taken from the parent's settings: a command for any other app, address
 * or file is refused. The app lands hidden like every new app until a parent ticks it for a mode.
 */
object StoreApp {
    const val PKG = "com.aurora.store"
    const val URL = "https://f-droid.org/repo/com.aurora.store_76.apk"
    const val SHA256 = "fd9c75d90d0f4a7c132b9b4a5a2cf1992a45e03b8d8ff988b7dcfbc0db2c4d11"
    const val SIZE = 9_360_088L
    const val VERSION_CODE = 76L
    const val VERSION_NAME = "4.8.4"

    /** Null when the command is the pinned Aurora install, else why it is refused (shown to the parent as the job's "failed" result). */
    fun refusal(cmd: AppCmd): String? = when {
        cmd.kind != "install" -> "not an install"
        cmd.pkg != PKG -> "this phone only installs its app store"
        cmd.url != URL -> "address is not the app store's"
        !cmd.sha256.equals(SHA256, ignoreCase = true) -> "checksum is not the app store's"
        else -> null
    }

    /** The command as the phone will run it: only the pinned values, whatever else was sent. */
    fun pinned(cmd: AppCmd) = cmd.copy(pkg = PKG, url = URL, sha256 = SHA256, size = SIZE)

    fun versionCode(c: Context): Long? = try { c.packageManager.getPackageInfo(PKG, 0).longVersionCode } catch (_: Exception) { null }
    fun versionName(c: Context): String? = try { c.packageManager.getPackageInfo(PKG, 0).versionName } catch (_: Exception) { null }
    fun installed(c: Context) = versionCode(c) != null

    /**
     * What Aurora needs to install and download without asking: notifications, installing other apps, and files. Runtime permissions can be granted by
     * a device owner; the two app-ops (install unknown apps, all files) are also tried that way and otherwise set by the boot script of the image
     * (pg-provision.sh, at every boot). Safe to repeat; does nothing if the store is not installed or this is not a Family phone.
     */
    fun ensureGrants(c: Context) {
        if (!installed(c) || !Dpm.isOwner(c)) return
        val d = Dpm.dpm(c); val a = Dpm.admin(c)
        for (perm in listOf("android.permission.POST_NOTIFICATIONS", "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.MANAGE_EXTERNAL_STORAGE")) {
            try {
                if (d.getPermissionGrantState(a, PKG, perm) != android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                    d.setPermissionGrantState(a, PKG, perm, android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) { Log.log(c, "app store: could not grant ${perm.substringAfterLast('.')}: ${e.javaClass.simpleName}") }
        }
    }

    /** For the sync state. */
    fun state(c: Context): JSONObject = JSONObject().put("installed", installed(c)).put("version", versionName(c) ?: JSONObject.NULL)
}
