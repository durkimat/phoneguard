package io.github.durkimat.phoneguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Sync with the family companion now", for the setup tool over adb: `am broadcast -n io.github.durkimat.phoneguard/.SyncReceiver`.
 * Protected by the DUMP permission, which the shell and root hold and ordinary apps cannot get, so nothing else on the phone can trigger it.
 * It carries no data; all it does is make the phone ask the companion, which is signed. Rate limited.
 */
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val now = System.currentTimeMillis()
        if (now - last < 3_000) return
        last = now
        Log.log(c, "sync requested over adb")
        io.github.durkimat.phoneguard.companion.WorkerLink.runAsync(c, "adb", goAsync())
    }

    companion object { @Volatile private var last = 0L }
}
