package io.github.durkimat.phoneguard

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) { Log.log(context, "admin enabled") }
    override fun onDisabled(context: Context, intent: Intent) { Log.log(context, "admin disabled") }
}

/** Boot, app update and clock changes: re-evaluate and make sure the service is running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Log.log(c, "trigger ${i.action?.substringAfterLast('.')}")
        Controller.evaluate(c, i.action?.substringAfterLast('.') ?: "boot")
        GuardService.start(c)
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Link.schedulePoll(c, 30_000) // soon after boot, once the network is up
        }
    }
}

/** The one exact alarm: fires at the next mode change, the 10-minute warning, or the safety re-check. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getStringExtra("kind") == "sync") Link.runAsync(c, "poll", goAsync())
        else if (i.getStringExtra("kind") == "loc") LocationReporter.onAlarm(c, goAsync())
        else Controller.evaluate(c, "alarm")
    }
}

/**
 * "Sync now". HA's Companion app broadcasts this when a parent changes something. It carries no data
 * and the sender is not verified, so all it can do is make us ask HA, which is signed. Rate limited.
 */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val now = System.currentTimeMillis()
        if (now - last < 5_000) return
        last = now
        Log.log(c, "nudge received")
        Link.runAsync(c, "nudge", goAsync())
    }

    companion object { @Volatile private var last = 0L }
}
