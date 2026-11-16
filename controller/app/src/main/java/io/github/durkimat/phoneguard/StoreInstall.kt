package io.github.durkimat.phoneguard

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import io.github.durkimat.phoneguard.core.AppCmd
import io.github.durkimat.phoneguard.core.StoreDrive
import io.github.durkimat.phoneguard.incall.CallState
import io.github.durkimat.phoneguard.keys.F2Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone gets an app (today only Spotify) through the Aurora Store by itself: the Worker sends a `storeinstall` job, and this opens the app's page in
 * Aurora and has the screen helper (F2Service) press Aurora's Install and then Android's own installer's Install, as a person would. [StoreDrive] decides
 * what to press; this does the waiting, the screen and the tidying up. Aurora is hidden from the child, so it is shown for the job and hidden again after it,
 * whatever happens (a job that was cut short by a restart is tidied up at the next start). Needs Wi-Fi and an unlocked phone; the sign-in to the app stays a person's step.
 */
object StoreInstall {
    private val running = AtomicBoolean(false)
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("storeinstall", Context.MODE_PRIVATE)

    /** What this phone's app can do: reported to the parent page so the Worker only sends the job to a phone that can run it. */
    const val SUPPORTED = true

    /** Why the job should wait (null when it can go now). Waiting is not failing: the Worker keeps the job queued. */
    fun whyWait(c: Context): String? {
        if (!StoreApp.installed(c)) return "the app store is not on this phone yet"
        if (!F2Service.running()) return "the screen helper is not running"
        if (CallState.info != null) return "a call is in progress"
        if (c.getSystemService(KeyguardManager::class.java).isDeviceLocked) return "the phone is locked"
        return null
    }

    private fun isInstalled(c: Context, pkg: String) = try {
        c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).let { (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0 }
    } catch (_: Exception) { false }

    private fun openStorePage(c: Context, pkg: String) {
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(StoreApp.PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) { Log.log(c, "store page: ${e.javaClass.simpleName}") }
    }

    private fun goHome(c: Context) {
        try { c.startActivity(Intent(c, io.github.durkimat.phoneguard.launcher.LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) } catch (_: Exception) { }
    }

    /** Put everything back as it was: Aurora hidden again if it was, the cover gone. Safe to repeat. */
    private fun restore(c: Context) {
        try {
            F2Service.cover(false)
            val p = prefs(c)
            if (p.getBoolean("wasHidden", false)) {
                val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
                if (Dpm.isOwner(c) && !dpm.isApplicationHidden(admin, StoreApp.PKG)) dpm.setApplicationHidden(admin, StoreApp.PKG, true)
            }
            p.edit().putBoolean("active", false).apply()
        } catch (e: Exception) { Log.log(c, "store job tidy-up: ${e.javaClass.simpleName}") }
    }

    /** At start-up and before a job: a job that was cut short (restart, crash) left Aurora shown. */
    fun cleanup(c: Context) {
        if (!running.get() && prefs(c).getBoolean("active", false)) { Log.log(c, "store job: tidying up after an unfinished job"); restore(c); goHome(c) }
    }

    /** Runs on the app-job thread; records the result itself, except when it must wait (a call came in): then nothing is recorded and the job stays queued. */
    fun run(c: Context, cmd: AppCmd, record: (String, String) -> Unit) {
        if (cmd.pkg != "com.spotify.music") { record("failed", "not an app this phone fetches"); return }
        if (isInstalled(c, cmd.pkg)) { record("done", "${cmd.name} is already on the phone"); return }
        if (!running.compareAndSet(false, true)) return
        val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
        val wake = c.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "phoneguard:storeinstall")
        var outcome: Pair<String, String>? = null
        try {
            val wasHidden = Dpm.isOwner(c) && dpm.isApplicationHidden(admin, StoreApp.PKG)
            prefs(c).edit().putBoolean("active", true).putBoolean("wasHidden", wasHidden).putLong("at", System.currentTimeMillis()).apply()
            if (wasHidden) dpm.setApplicationHidden(admin, StoreApp.PKG, false)
            StoreApp.requestOps(c) // showing it dropped its install permission; the phone's root helper sets it again within seconds
            wake.acquire(6 * 60_000L)
            val message = "Getting ${cmd.name}..."
            F2Service.cover(true, message)
            Thread.sleep(2_500)
            openStorePage(c, cmd.pkg)
            val drive = StoreDrive()
            var steps = 0
            while (outcome == null && steps++ < StoreDrive.MAX_STEPS + 5) {
                Thread.sleep(StoreDrive.LOOK_EVERY_MS)
                if (isInstalled(c, cmd.pkg)) { outcome = "done" to "installed ${cmd.name}"; break }
                if (CallState.info != null) { Log.log(c, "store job stopped: a call came in"); outcome = null; return }
                F2Service.cover(true, message)
                val screen = F2Service.readScreen() ?: continue
                when (val s = drive.step(screen.first, screen.second)) {
                    is StoreDrive.Step.Tap -> { val ok = F2Service.clickLabel(s.label); Log.log(c, "store job: ${s.label} ${if (ok) "pressed" else "not pressable"}") }
                    StoreDrive.Step.Open -> openStorePage(c, cmd.pkg)
                    StoreDrive.Step.AskInstallPermission -> StoreApp.requestOps(c)
                    is StoreDrive.Step.Fail -> outcome = "failed" to s.detail
                    StoreDrive.Step.Wait -> {}
                }
            }
            if (outcome == null && isInstalled(c, cmd.pkg)) outcome = "done" to "installed ${cmd.name}"
            if (outcome == null && steps > StoreDrive.MAX_STEPS) outcome = "failed" to "the app store did not finish in time"
        } catch (e: Exception) {
            outcome = "failed" to "something went wrong (${e.javaClass.simpleName})"
        } finally {
            try { if (wake.isHeld) wake.release() } catch (_: Exception) { }
            restore(c); goHome(c); running.set(false)
        }
        outcome?.let { record(it.first, it.second); Controller.evaluateAsync(c, "storeinstall") { } } // the new app lands hidden until a parent allows it
    }
}
