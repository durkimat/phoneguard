package io.github.durkimat.phoneguard.lab

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock

/**
 * Registers the package-event receiver at process level (not tied to an activity), so we learn
 * whether the process stays alive in the background and how long the quarantine window is.
 */
class LabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val pkg = i.data?.schemeSpecificPart ?: return
                    val replacing = i.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    LabLog.log(c, "PKG ${i.action?.substringAfterLast('.')} $pkg replacing=$replacing")
                    val added = i.action == Intent.ACTION_PACKAGE_ADDED && !replacing
                    if (added && Prefs.quarantine(c) && Dpm.isOwner(c) && !Protected.isProtected(c, pkg)) {
                        val t0 = SystemClock.elapsedRealtime()
                        val ok = try {
                            Dpm.dpm(c).setApplicationHidden(Dpm.admin(c), pkg, true)
                        } catch (e: Exception) {
                            false
                        }
                        LabLog.log(c, "QUARANTINE hide $pkg ok=$ok in ${SystemClock.elapsedRealtime() - t0} ms")
                        if (ok) Prefs.addHidden(c, pkg)
                    }
                }
            },
            filter,
            Context.RECEIVER_EXPORTED,
        )
        LabLog.log(this, "process started (pid ${android.os.Process.myPid()})")
    }
}
