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
    }
}

/** The one exact alarm: fires at the next mode change, the 10-minute warning, or the safety re-check. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Controller.evaluate(c, "alarm") }
}
