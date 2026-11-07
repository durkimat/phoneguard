package io.github.durkimat.phoneguard.lab

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent timestamped log: shown in the UI and written to a file adb can pull. */
object LabLog {
    private const val TAG = "PGLab"
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    var listener: (() -> Unit)? = null

    fun file(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, "lab.log")
    }

    @Synchronized
    fun log(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        try {
            file(ctx).appendText("${fmt.format(Date())}  $msg\n")
        } catch (e: Exception) {
            Log.w(TAG, "log write failed", e)
        }
        listener?.invoke()
    }

    @Synchronized
    fun tail(ctx: Context, n: Int = 120): String =
        try {
            file(ctx).readLines().takeLast(n).joinToString("\n")
        } catch (e: Exception) {
            ""
        }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (_: Exception) {
        }
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("lab", Context.MODE_PRIVATE)

    fun quarantine(c: Context) = sp(c).getBoolean("quarantine", false)
    fun setQuarantine(c: Context, v: Boolean) = sp(c).edit().putBoolean("quarantine", v).apply()

    fun alarmChain(c: Context) = sp(c).getBoolean("alarmChain", false)
    fun setAlarmChain(c: Context, v: Boolean) = sp(c).edit().putBoolean("alarmChain", v).apply()

    fun alarmMinutes(c: Context) = sp(c).getInt("alarmMinutes", 5)
    fun setAlarmMinutes(c: Context, v: Int) = sp(c).edit().putInt("alarmMinutes", v).apply()

    fun hidden(c: Context): Set<String> = sp(c).getStringSet("hidden", emptySet()) ?: emptySet()
    fun addHidden(c: Context, pkg: String) =
        sp(c).edit().putStringSet("hidden", hidden(c) + pkg).apply()

    fun removeHidden(c: Context, pkg: String) =
        sp(c).edit().putStringSet("hidden", hidden(c) - pkg).apply()

    fun pinnedHome(c: Context): String? = sp(c).getString("pinnedHome", null)
    fun setPinnedHome(c: Context, pkg: String?) = sp(c).edit().putString("pinnedHome", pkg).apply()
}

object Dpm {
    fun dpm(c: Context): DevicePolicyManager = c.getSystemService(DevicePolicyManager::class.java)
    fun admin(c: Context): ComponentName = AdminReceiver.component(c)
    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    fun clear(c: Context, key: String) {
        if (isOwner(c)) dpm(c).clearUserRestriction(admin(c), key)
    }
}

/** Never hide or suspend these: hiding a service package can break the phone (spec 4e). */
object Protected {
    private val hard = setOf(
        "com.android.settings", "com.android.dialer", "com.android.messaging",
        "com.android.contacts", "com.android.documentsui", "com.google.android.gms",
        "com.google.android.gsf", "com.android.vending", "io.github.sspanak.tt9",
        "com.android.inputmethod.latin", "com.android.launcher3", "com.android.systemui",
        "com.android.phone", "com.android.server.telecom", "com.android.providers.telephony",
        "com.android.webview", "com.android.packageinstaller", "com.android.permissioncontroller",
        "eu.dumbdroid.deviceowner", "eu.dumbdroid.updater", "eu.dumbdroid.settingspanel",
        "com.android.emergency", "com.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module", "me.phh.treble.app",
        "io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal",
    )

    fun currentHome(c: Context): String? =
        c.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName

    fun isProtected(c: Context, pkg: String): Boolean {
        if (pkg == c.packageName || pkg in hard || pkg == currentHome(c)) return true
        val ime = Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        return pkg == ime
    }
}

/** Exact-alarm chain used for the Doze/overnight reliability test, plus the safety auto-revert. */
object Alarms {
    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun pending(c: Context, kind: String, planned: Long, code: Int) =
        PendingIntent.getBroadcast(
            c, code,
            Intent(c, AlarmReceiver::class.java).putExtra("kind", kind).putExtra("planned", planned),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun set(c: Context, at: Long, pi: PendingIntent) {
        if (am(c).canScheduleExactAlarms()) {
            am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            LabLog.log(c, "exact alarms NOT permitted, using inexact")
            am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun scheduleChain(c: Context) {
        val at = System.currentTimeMillis() + Prefs.alarmMinutes(c) * 60_000L
        set(c, at, pending(c, "chain", at, 1))
    }

    fun cancelChain(c: Context) = am(c).cancel(pending(c, "chain", 0, 1))

    fun revertDebuggingIn(c: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        set(c, at, pending(c, "revert", at, 2))
    }
}
