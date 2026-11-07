package io.github.durkimat.phoneguard.lab

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings

/**
 * Lab-only command channel so the test rig can drive every lab action from adb:
 *   adb shell am broadcast -n <pkg>/.CmdReceiver -a <pkg>.CMD --es c hide --es a com.example.app
 * Each command logs one line starting with "CMD <name>" so the rig can read the result.
 */
class CmdReceiver : BroadcastReceiver() {
    companion object {
        private var grayObserver: ContentObserver? = null
        private const val GRAY = "accessibility_display_daltonizer_enabled"
    }

    override fun onReceive(c: Context, i: Intent) {
        val cmd = i.getStringExtra("c") ?: return LabLog.log(c, "CMD: no c extra")
        val a = i.getStringExtra("a") ?: ""
        val b = i.getStringExtra("b") ?: ""
        val out = try {
            run(c, cmd, a, b)
        } catch (e: Exception) {
            "EXCEPTION ${e.javaClass.simpleName}: ${e.message}"
        }
        LabLog.log(c, "CMD $cmd $a => $out")
    }

    private fun run(c: Context, cmd: String, a: String, b: String): String {
        val dpm = Dpm.dpm(c)
        val admin = Dpm.admin(c)
        val um = c.getSystemService(UserManager::class.java)
        val pm = c.packageManager
        fun granted(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (!Dpm.isOwner(c) && cmd != "status") return "NOT device owner"
        return when (cmd) {
            "status" -> "owner=${Dpm.isOwner(c)} adminActive=${dpm.isAdminActive(admin)}"
            "hide", "unhide" -> {
                val hide = cmd == "hide"
                if (hide && Protected.isProtected(c, a)) return "REFUSED protected"
                val t0 = SystemClock.elapsedRealtime()
                val ok = dpm.setApplicationHidden(admin, a, hide)
                if (ok) if (hide) Prefs.addHidden(c, a) else Prefs.removeHidden(c, a)
                "ok=$ok ms=${SystemClock.elapsedRealtime() - t0}"
            }
            "suspend", "unsuspend" -> {
                val s = cmd == "suspend"
                if (s && Protected.isProtected(c, a)) return "REFUSED protected"
                "failed=${dpm.setPackagesSuspended(admin, arrayOf(a), s).toList()}"
            }
            "state" -> "hidden=${dpm.isApplicationHidden(admin, a)} suspended=${dpm.isPackageSuspended(admin, a)}"
            "restrict" -> { dpm.addUserRestriction(admin, a); "effective=${um.hasUserRestriction(a)}" }
            "unrestrict" -> { dpm.clearUserRestriction(admin, a); "effective=${um.hasUserRestriction(a)}" }
            "unrestrict_all" -> {
                val ours = dpm.getUserRestrictions(admin).keySet().toList()
                ours.forEach { dpm.clearUserRestriction(admin, it) }
                "cleared=$ours"
            }
            "restrictions" -> {
                val ours = dpm.getUserRestrictions(admin).keySet().filter { dpm.getUserRestrictions(admin).getBoolean(it) }
                val all = um.userRestrictions.keySet().filter { um.userRestrictions.getBoolean(it) }
                "ours=$ours effective=$all"
            }
            "has_restriction" -> "$a=${um.hasUserRestriction(a)}"
            "gray" -> {
                val on = a == "on"
                Settings.Secure.putInt(c.contentResolver, "accessibility_display_daltonizer", 0)
                "write=${Settings.Secure.putInt(c.contentResolver, GRAY, if (on) 1 else 0)} now=${Settings.Secure.getInt(c.contentResolver, GRAY, -1)}"
            }
            "gray_value" -> "enabled=${Settings.Secure.getInt(c.contentResolver, GRAY, -1)}"
            "gray_watch" -> {
                if (a == "off") {
                    grayObserver?.let { c.contentResolver.unregisterContentObserver(it) }
                    grayObserver = null
                    return "stopped"
                }
                val cr = c.applicationContext.contentResolver
                val t0 = SystemClock.elapsedRealtime()
                grayObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        LabLog.log(c, "GRAYWATCH onChange value=${Settings.Secure.getInt(cr, GRAY, -9)}")
                        if (Settings.Secure.getInt(cr, GRAY, 0) == 0) {
                            val ok = try { Settings.Secure.putInt(cr, GRAY, 1) } catch (e: Exception) { false }
                            LabLog.log(c, "GRAYWATCH switched off -> RE-ASSERTED ok=$ok after ${(SystemClock.elapsedRealtime() - t0) / 1000}s")
                        }
                    }
                }
                cr.registerContentObserver(Settings.Secure.getUriFor(GRAY), false, grayObserver!!)
                "running (lives only while this process lives)"
            }
            "selfgrant" -> {
                val perm = when (a) {
                    "secure" -> Manifest.permission.WRITE_SECURE_SETTINGS
                    "camera" -> Manifest.permission.CAMERA
                    "battery" -> Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    else -> return "unknown perm $a"
                }
                val ok = dpm.setPermissionGrantState(admin, c.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                "call=$ok now=${granted(perm)}"
            }
            "protect" -> {
                val target = b.ifBlank { c.packageName }
                dpm.setUserControlDisabledPackages(admin, if (a == "off") emptyList() else listOf(target))
                "protect=$a target=$target list=${dpm.getUserControlDisabledPackages(admin)}"
            }
            "pinhome" -> {
                if (a == "off") {
                    Prefs.pinnedHome(c)?.let { dpm.clearPackagePersistentPreferredActivities(admin, it) }
                    Prefs.setPinnedHome(c, null)
                    return "unpinned"
                }
                // a = package to pin (blank = current default Home)
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val home = if (a.isNotBlank()) {
                    pm.queryIntentActivities(intent, 0).firstOrNull { it.activityInfo.packageName == a }?.activityInfo
                } else pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                if (home == null) return "home not found"
                val f = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
                dpm.addPersistentPreferredActivity(admin, f, ComponentName(home.packageName, home.name))
                Prefs.setPinnedHome(c, home.packageName)
                "pinned ${home.packageName}/${home.name}"
            }
            "homes" -> pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .joinToString { it.activityInfo.packageName } + " default=${Protected.currentHome(c)}"
            "chain" -> {
                if (a == "stop") { Prefs.setAlarmChain(c, false); Alarms.cancelChain(c); return "stopped" }
                Prefs.setAlarmMinutes(c, a.toIntOrNull()?.coerceIn(1, 120) ?: 5)
                Prefs.setAlarmChain(c, true); Alarms.scheduleChain(c)
                "started every ${Prefs.alarmMinutes(c)} min"
            }
            "quarantine" -> { Prefs.setQuarantine(c, a == "on"); "quarantine=$a" }
            "autotime" -> { dpm.setAutoTimeEnabled(admin, true); dpm.setAutoTimeZoneEnabled(admin, true); "auto time/zone on" }
            "battery" -> {
                val pmr = c.getSystemService(android.os.PowerManager::class.java)
                val usm = c.getSystemService(android.app.usage.UsageStatsManager::class.java)
                "exempt=${pmr.isIgnoringBatteryOptimizations(c.packageName)} bucket=${usm.appStandbyBucket} " +
                    "exactAlarm=${c.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()}"
            }
            "ping" -> "pong"
            else -> "unknown command"
        }
    }
}
