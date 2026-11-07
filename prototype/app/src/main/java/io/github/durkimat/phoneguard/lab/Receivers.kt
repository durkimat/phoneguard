package io.github.durkimat.phoneguard.lab

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        LabLog.log(context, "admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        LabLog.log(context, "admin disabled")
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx.applicationContext, AdminReceiver::class.java)
    }
}

/** Logs boots, re-arms the alarm chain, and clears the risky debugging restriction (lab safety). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        LabLog.log(c, "BOOT ${i.action} uptime=${SystemClock.elapsedRealtime() / 1000}s owner=${Dpm.isOwner(c)}")
        Dpm.clear(c, UserManager.DISALLOW_DEBUGGING_FEATURES)
        if (Prefs.alarmChain(c)) Alarms.scheduleChain(c)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getStringExtra("kind")) {
            "chain" -> {
                val planned = i.getLongExtra("planned", 0L)
                val late = (System.currentTimeMillis() - planned) / 1000
                val idle = c.getSystemService(PowerManager::class.java).isDeviceIdleMode
                LabLog.log(c, "HEARTBEAT late=${late}s deviceIdle=$idle")
                if (Prefs.alarmChain(c)) Alarms.scheduleChain(c)
            }
            "revert" -> {
                Dpm.clear(c, UserManager.DISALLOW_DEBUGGING_FEATURES)
                LabLog.log(c, "auto-reverted DISALLOW_DEBUGGING_FEATURES")
            }
        }
    }
}

/** Target for the Companion app's command_broadcast_intent. Logs everything it can see. */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val b = i.extras
        val extras = b?.keySet()?.joinToString { k -> "$k=${b.get(k)}" } ?: ""
        // Sender identity is only shared if the sender opts in (Android 14), so expect none.
        val from = try {
            "${sentFromPackage}/${sentFromUid}"
        } catch (e: Throwable) {
            "n/a"
        }
        LabLog.log(c, "NUDGE action=${i.action} from=$from extras=[$extras]")
    }
}
