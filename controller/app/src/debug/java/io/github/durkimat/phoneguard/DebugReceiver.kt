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
        if (cmd == "pair" || cmd == "sync" || cmd.startsWith("radio")) { // these use the network
            val pending = goAsync()
            Thread { val out = go(); Log.log(c, "DBG $cmd $a => $out"); pending.finish() }.start()
            return
        }
        Log.log(c, "DBG $cmd $a => ${go()}")
    }

    private fun run(c: Context, cmd: String, a: String, b: String): String = when (cmd) {
        "camrot" -> { io.github.durkimat.phoneguard.camera.CameraActivity.debugHeld = a.toIntOrNull(); io.github.durkimat.phoneguard.camera.CameraActivity.refresh?.invoke(); "held=$a" }
        "torch" -> { io.github.durkimat.phoneguard.keys.Torch.toggle(c); "torch on=${io.github.durkimat.phoneguard.keys.Torch.on}" }
        "radio-wifi" -> { val on = a == "on"; val t0 = System.currentTimeMillis(); val r = io.github.durkimat.phoneguard.net.WifiControl.setEnabled(c, on)
            var ms = -1L; for (n in 1..40) { Thread.sleep(250); if (io.github.durkimat.phoneguard.net.WifiControl.enabled(c) == on) { ms = System.currentTimeMillis() - t0; break } }
            "call=$r now=${io.github.durkimat.phoneguard.net.WifiControl.enabled(c)} settled_ms=$ms" }
        "radio-bt" -> { val on = a == "on"; val t0 = System.currentTimeMillis(); val r = io.github.durkimat.phoneguard.net.BluetoothControl.setEnabled(c, on)
            var ms = -1L; for (n in 1..40) { Thread.sleep(250); if (io.github.durkimat.phoneguard.net.BluetoothControl.enabled(c) == on) { ms = System.currentTimeMillis() - t0; break } }
            "msg='$r' now=${io.github.durkimat.phoneguard.net.BluetoothControl.enabled(c)} settled_ms=$ms" }
        "radio-data" -> { val on = a == "on"; val out = StringBuilder()
            val tm = c.getSystemService(android.telephony.TelephonyManager::class.java)
            out.append("before=${android.provider.Settings.Global.getInt(c.contentResolver, "mobile_data", -1)} tmEnabled=${try { tm.isDataEnabled } catch (e: Exception) { e.javaClass.simpleName }} ")
            if (b == "tm") out.append("setDataEnabled=" + try { tm.setDataEnabled(on); "ok" } catch (e: Exception) { e.javaClass.simpleName + ":" + e.message })
            else out.append("putInt=" + try { android.provider.Settings.Global.putInt(c.contentResolver, "mobile_data", if (on) 1 else 0) } catch (e: Exception) { e.javaClass.simpleName + ":" + e.message })
            Thread.sleep(1500)
            out.append(" after=${android.provider.Settings.Global.getInt(c.contentResolver, "mobile_data", -1)} tmEnabled=${try { tm.isDataEnabled } catch (e: Exception) { e.javaClass.simpleName }}"); out.toString() }
        "radio-air" -> { val on = a == "on"; val out = StringBuilder()
            out.append("before=${android.provider.Settings.Global.getInt(c.contentResolver, "airplane_mode_on", -1)} ")
            if (b == "cm") out.append("cm=" + try { val cm = c.getSystemService(android.net.ConnectivityManager::class.java); cm.javaClass.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType).invoke(cm, on); "ok" } catch (e: Exception) { e.javaClass.simpleName + ":" + (e.cause?.javaClass?.simpleName ?: e.message) })
            else {
                out.append("putInt=" + try { android.provider.Settings.Global.putInt(c.contentResolver, "airplane_mode_on", if (on) 1 else 0) } catch (e: Exception) { e.javaClass.simpleName + ":" + e.message })
                out.append(" broadcast=" + try { c.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on)); "sent" } catch (e: Exception) { e.javaClass.simpleName + ":" + e.message })
            }
            Thread.sleep(3000)
            out.append(" after=${android.provider.Settings.Global.getInt(c.contentResolver, "airplane_mode_on", -1)} wifi=${io.github.durkimat.phoneguard.net.WifiControl.enabled(c)} bt=${io.github.durkimat.phoneguard.net.BluetoothControl.enabled(c)}"); out.toString() }
        "radios" -> io.github.durkimat.phoneguard.net.RadioManager.describe(c)
        "ping" -> "pong"
        "camera" -> { c.startActivity(android.content.Intent(c, io.github.durkimat.phoneguard.camera.CameraActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); "camera $a" }
        "lockwp" -> { // paint the lock wallpaper for a theme id and forget the cache so it really repaints
            c.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().remove("lock_wp").apply()
            io.github.durkimat.phoneguard.launcher.LockWallpaper.apply(c, a); "lockwp $a"
        }
        "badges" -> { // a=sms b=whatsapp (and missed calls = a+b) as fake counts; "off" clears
            io.github.durkimat.phoneguard.launcher.LauncherData.debugBadges = if (a == "off") null else listOf(
                io.github.durkimat.phoneguard.launcher.Badge(io.github.durkimat.phoneguard.launcher.BadgeKind.SMS, a.toInt()),
                io.github.durkimat.phoneguard.launcher.Badge(io.github.durkimat.phoneguard.launcher.BadgeKind.WHATSAPP, b.toInt()),
                io.github.durkimat.phoneguard.launcher.Badge(io.github.durkimat.phoneguard.launcher.BadgeKind.MISSED, 1)); "badges $a $b"
        }
        "statusbar" -> try { val ok = Dpm.dpm(c).setStatusBarDisabled(Dpm.admin(c), a == "on"); "setStatusBarDisabled($a) -> $ok" } catch (e: Exception) { "statusbar failed: ${e.javaClass.simpleName} ${e.message}" }
        "location" -> { // patch the stored config with a weather location: a=lat b=lon
            val o = org.json.JSONObject(Store.configText(c) ?: "{}"); o.put("location", org.json.JSONObject().put("lat", a.toDouble()).put("lon", b.toDouble()))
            Store.setConfig(c, o.toString()); "location $a,$b"
        }
        "keyguard" -> try { // can a device owner switch the keyguard off while a PIN is set? (a=on|off)
            val ok = Dpm.dpm(c).setKeyguardDisabled(Dpm.admin(c), a == "on"); "setKeyguardDisabled($a) -> $ok"
        } catch (e: Exception) { "keyguard failed: ${e.javaClass.simpleName} ${e.message}" }
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
        // WhatsApp bridge: a = open | pair | connect | disconnect | allow | send | status | logout; b = number / numbers / "number|text"
        "wa" -> {
            val w = io.github.durkimat.phoneguard.whatsapp.WhatsApp
            when (a) {
                "close" -> { w.close(); "closed" }
                "resync" -> { Thread { w.resync(c) }.start(); "resyncing" }
                // only for looking at the link screens: hides or shows the "linked" flag the screens use (the real link is untouched)
                "flag" -> { c.createDeviceProtectedStorageContext().getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).edit().putBoolean("wa_linked", b == "on").apply(); "flag=$b" }
                "poll" -> { Thread { w.poll(c) }.start(); "polling" }
                "policy" -> "policy=${w.policy(c)} alarm=${c.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock != null}"
                "open" -> { w.open(c); "status=${w.status} paired=${w.isPaired(c)}" }
                "pair" -> { val r = w.pair(c, b); Thread.sleep(6000); "$r code=${w.pairCode}" }
                "connect" -> w.connect(c)
                "disconnect" -> { w.disconnect(); "ok" }
                "allow" -> { w.setAllowed(c, if (b == "all") "" else b, b == "all"); "allowed=$b" }
                "rejectcalls" -> { w.rejectCalls(c, b == "on"); "rejectcalls=$b" }
                "send" -> w.send(c, b.substringBefore('|'), b.substringAfter('|'))
                "logout" -> w.logout(c)
                else -> "status=${w.status} connected=${w.isConnected()} paired=${w.isPaired(c)} unread=${w.store(c).unreadTotal()} chats=${w.store(c).chats().map { it.number + ":" + (it.last?.body?.length ?: 0) }}"
            }
        }
        // show a made-up call on the call screen without a SIM: a = ringing | dialling | active | ended | off, b = name or number
        "fakecall" -> {
            val who = b.ifBlank { "Mum" }
            val info = when (a) {
                "ringing" -> io.github.durkimat.phoneguard.launcher.CallInfo("07700900111", who, io.github.durkimat.phoneguard.launcher.CallPhase.RINGING)
                "dialling" -> io.github.durkimat.phoneguard.launcher.CallInfo("07700900222", who, io.github.durkimat.phoneguard.launcher.CallPhase.DIALING)
                "active" -> io.github.durkimat.phoneguard.launcher.CallInfo("07700900222", who, io.github.durkimat.phoneguard.launcher.CallPhase.ACTIVE, startedAt = Clock.now() - 42_000, muted = true, speaker = true)
                "ended" -> io.github.durkimat.phoneguard.launcher.CallInfo("07700900222", who, io.github.durkimat.phoneguard.launcher.CallPhase.ENDED, seconds = 42)
                else -> null
            }
            io.github.durkimat.phoneguard.incall.CallState.set(info)
            if (info != null) c.startActivity(Intent(c, io.github.durkimat.phoneguard.incall.CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "fakecall $a"
        }
        "lockdown" -> "home=${HomeApp.target(c, false)?.packageName} pinnedNokia=${c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name?.endsWith("LauncherActivity")} touchBlocked=${TouchBlocker.isOn()} menuKeyOff=${HardKeys.isDisabled(c)} canDraw=${TouchBlocker.canDraw(c)} locked=${Kiosk.locked(c)} ringer=${c.getSystemService(android.media.AudioManager::class.java).ringerMode} filter=${c.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter} canSilence=${SoundEnforcer.canSilence(c)} inboundBlocked=${CallBlocker.blocking(c)}"
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
