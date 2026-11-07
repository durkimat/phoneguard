package io.github.durkimat.phoneguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.durkimat.phoneguard.core.Boost
import java.io.File

/**
 * Rig command channel, compiled into debug builds only:
 *   adb shell am broadcast -n io.github.durkimat.phoneguard/.DebugReceiver -a io.github.durkimat.phoneguard.DEBUG --es c <cmd> --es a <arg> --es b <arg>
 * Every command logs "DBG <cmd> <a> => <result>".
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val cmd = i.getStringExtra("c") ?: return
        val a = i.getStringExtra("a") ?: ""
        val b = i.getStringExtra("b") ?: ""
        fun go(): String = try { run(c, cmd, a, b) } catch (e: Exception) { "EXCEPTION ${e.javaClass.simpleName}: ${e.message}" }
        if (cmd == "pair" || cmd == "sync") { // these use the network
            val pending = goAsync()
            Thread { val out = go(); Log.log(c, "DBG $cmd $a => $out"); pending.finish() }.start()
            return
        }
        Log.log(c, "DBG $cmd $a => ${go()}")
    }

    private fun run(c: Context, cmd: String, a: String, b: String): String = when (cmd) {
        "ping" -> "pong"
        "status" -> "owner=${Dpm.isOwner(c)} summary=${Store.summary(c)} hidden=${Store.hidden(c).size} cfg=${Store.config(c)?.version}"
        "config" -> {
            val f = File(c.getExternalFilesDir(null), a)
            Store.setConfig(c, f.readText()); Controller.evaluate(c, "debug config"); "loaded ${f.name} v${Store.config(c)?.version}"
        }
        "noconfig" -> { Store.clearConfig(c); Controller.evaluate(c, "debug noconfig"); "cleared" }
        "clock" -> {
            Clock.offset = if (a == "real") 0L else a.toLong() - System.currentTimeMillis()
            Controller.evaluate(c, "debug clock"); "offset=${Clock.offset}ms now=${Clock.now()}"
        }
        "boost" -> { Store.setBoost(c, Boost(a, Clock.now() + b.toLong() * 60_000)); Controller.evaluate(c, "debug boost"); "boost $a for ${b}m" }
        "unboost" -> { Store.setBoost(c, null); Controller.evaluate(c, "debug unboost"); "cleared" }
        "eval" -> { Controller.evaluate(c, "debug eval"); Store.summary(c) }
        "hidden" -> Store.hidden(c).sorted().toString()
        "restrictions" -> Store.restrictions(c).sorted().toString()
        "gray" -> "want=${Store.wantGray(c)} actual=${Enforcer.isGray(c)} canWrite=${Enforcer.canWriteSecure(c)}"
        "reset" -> { // return the phone to normal: no config, nothing hidden, restrictions cleared
            Store.clearConfig(c); Store.setBoost(c, null); Store.setLastWarned(c, 0); Clock.offset = 0
            val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
            Store.hidden(c).forEach { try { dpm.setApplicationHidden(admin, it, false) } catch (_: Exception) { } }
            Store.setHidden(c, emptySet())
            Store.restrictions(c).forEach { try { dpm.clearUserRestriction(admin, it) } catch (_: Exception) { } }
            Store.setRestrictions(c, emptySet()); Enforcer.setGray(c, false); Link.clear(c); LockdownEnforcer.releaseAll(c); "reset"
        }
        "pair" -> { // a = file in the app's files dir holding the pairing QR text
            val r = Link.pair(c, File(c.getExternalFilesDir(null), a).readText().trim())
            if (r.startsWith("ok")) Link.runAsync(c, "after pairing")
            r
        }
        "sync" -> Link.sync(c, "debug")
        "lockdown" -> "home=${HomeApp.target(c, false)?.packageName} pinnedNokia=${c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name?.endsWith("LauncherActivity")} touchBlocked=${TouchBlocker.isOn()} menuKeyOff=${HardKeys.isDisabled(c)} canDraw=${TouchBlocker.canDraw(c)} locked=${Kiosk.locked(c)} ringer=${c.getSystemService(android.media.AudioManager::class.java).ringerMode} filter=${c.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter} canSilence=${SoundEnforcer.canSilence(c)}"
        "lt_start" -> LabLockdown.lockStart(c, a.toIntOrNull() ?: 0)
        "lt_stop" -> LabLockdown.lockStop(c)
        "lt_state" -> LabLockdown.lockState(c)
        "overlay_grant" -> LabLockdown.tryGrantOverlay(c)
        "overlay_on" -> LabLockdown.overlayOn(c, a.toIntOrNull() ?: 60)
        "overlay_off" -> LabLockdown.overlayOff(c)
        "launcher" -> { // a = pin | unpin: make our launcher the Home app (or give Home back to the old one)
            val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
            if (a == "unpin") { dpm.clearPackagePersistentPreferredActivities(admin, c.packageName); "unpinned" }
            else {
                val f = android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
                dpm.addPersistentPreferredActivity(admin, f, android.content.ComponentName(c.packageName, "io.github.durkimat.phoneguard.launcher.LauncherActivity"))
                "pinned Phone Guard Home"
            }
        }
        "statusbar" -> { // a = on|off: disable the pull-down shade and the status bar
            val ok = Dpm.dpm(c).setStatusBarDisabled(Dpm.admin(c), a == "on"); "setStatusBarDisabled($a) ok=$ok"
        }
        "forgetcfg" -> { // drop the local config and its version so the next sync fetches HA's again
            val r = org.json.JSONObject(Store.linkText(c) ?: "{}").put("cfgh", "")
            Store.setLinkText(c, r.toString()); Store.clearConfig(c); "config forgotten"
        }
        "usage" -> Usage.recent(c)?.toString() ?: "usage access not granted"
        "link" -> Link.summary(c)
        "unpair" -> { Link.clear(c); "unpaired" }
        "pollms" -> { Link.pollMs = a.toLong(); "poll every ${Link.pollMs}ms" }
        "release" -> { Dpm.dpm(c).clearDeviceOwnerApp(c.packageName); "device owner released" }
        else -> "unknown command"
    }
}
