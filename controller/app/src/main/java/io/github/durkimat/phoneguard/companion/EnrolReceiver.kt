package io.github.durkimat.phoneguard.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.durkimat.phoneguard.Log

/**
 * Receives the enrolment bundle from pg-provision.sh (root, at first boot) or from a setup helper over adb. Protected by the DUMP permission,
 * which the shell and root hold and ordinary apps cannot get, so nothing on the phone can plant a Worker address.
 */
class EnrolReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val b = EnrolBundle.parse(i.getStringExtra("bundle"))
        if (b == null) { Log.log(c, "enrol: bundle rejected"); return }
        EnrolStore.save(c, b)
        Log.log(c, "enrol: bundle stored for ${b.workerUrl}")
    }
}
