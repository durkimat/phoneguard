package io.github.durkimat.familychat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart, keeps the connection alive again (once the phone is unlocked, because the account's files are only readable then). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Fc.init(c)
        if (Fc.settings.setupDone) try { ConnectionService.start(c) } catch (_: Throwable) { }
    }
}
