package io.github.durkimat.phoneguard

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import io.github.durkimat.phoneguard.core.AutoUpdate
import io.github.durkimat.phoneguard.core.StoreDrive
import io.github.durkimat.phoneguard.core.UpdatePlan
import io.github.durkimat.phoneguard.incall.CallState
import io.github.durkimat.phoneguard.keys.F2Service
import io.github.durkimat.phoneguard.net.RadioManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * Overnight app updates through the store, for the apps the store installed (rules in [UpdatePlan]). At the quiet time the radios wake, and for each app nobody is using the
 * store's page is opened and its Update button pressed, as a person would (the same screen-helper route as the install job). The version before and after is recorded
 * and reported with every check-in; anything that goes wrong is a note for the companion and never stops the phone working. The store itself is never updated here.
 */
object UpdateRunner {
    private fun prefs(c: Context) = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("updates", Context.MODE_PRIVATE)

    class Alarm : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pending = goAsync()
            Thread { try { run(c.applicationContext) } finally { pending.finish() } }.start()
        }
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 31, Intent(c, Alarm::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sets (or moves) the next overnight alarm to the companion's quiet time. Cheap and repeatable; call after every settings change. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val s = UpdatePlan.settings(Store.config(c))
        if (!s.on) { am.cancel(pending(c)); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, UpdatePlan.nextRun(s.atMin, Clock.now(), ZoneId.systemDefault()), pending(c))
    }

    private fun installer(c: Context, pkg: String): String? = try { c.packageManager.getInstallSourceInfo(pkg).installingPackageName } catch (_: Exception) { null }
    private fun version(c: Context, pkg: String): String? = try { c.packageManager.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).versionName } catch (_: Exception) { null }
    private fun label(c: Context, pkg: String): String = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString() } catch (_: Exception) { pkg }

    /** Foreground (visible, or a foreground service such as music playing) counts as in use. Background processes and cached ones do not. */
    private fun inUse(c: Context, pkg: String): Boolean = try {
        c.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().any { it.pkgList.contains(pkg) && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE }
    } catch (_: Exception) { false }

    private fun network(c: Context, s: AutoUpdate): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return UpdatePlan.network(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true, s)
    }

    /** The apps the store installed that the companion has not left out: what a run updates. */
    private fun candidates(c: Context): List<String> {
        val s = UpdatePlan.settings(Store.config(c))
        return c.packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES).map { it.packageName }
            .filter { UpdatePlan.skip(it, installer(c, it), c.packageName, s, false) == null }.sorted()
    }

    /** Every app the store installed (for the companion's per-app ticks), whether or not it is ticked. */
    private fun storeApps(c: Context): List<String> {
        val all = AutoUpdate()
        return c.packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES).map { it.packageName }
            .filter { UpdatePlan.skip(it, installer(c, it), c.packageName, all, false) == null }.sorted()
    }

    /** The overnight run. Runs on its own thread; records what happened for the check-in. */
    fun run(c: Context) {
        val app = c.applicationContext
        try { schedule(app) } catch (_: Exception) { }   // tomorrow's alarm first, so a failure below never ends the nightly routine
        val s = UpdatePlan.settings(Store.config(app))
        if (!s.on) return
        val apps = candidates(app)
        if (apps.isEmpty()) return
        if (!StoreInstall.running.compareAndSet(false, true)) { note(app, emptyList(), "a store job was already running"); return }
        val results = JSONArray()
        var why: String? = null
        val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "phoneguard:updates")
        try {
            RadioManager.begin(app, "overnight updates")
            RadioManager.awaitNetwork(app, 90_000)
            when {
                network(app, s) != "go" -> why = "no Wi-Fi"
                StoreInstall.whyWait(app) != null -> why = StoreInstall.whyWait(app)   // a locked phone cannot be driven; the companion sees it
            }
            if (why == null) {
                wake.acquire(60 * 60_000L)
                val windowEnd = Clock.now() + s.lenMin * 60_000L
                for (pkg in apps) {
                    if (Clock.now() >= windowEnd) { why = "the update window ended"; break }
                    if (io.github.durkimat.phoneguard.music.PlaylistJob.busy()) { why = "a playlist download was running"; break }   // one overnight window: the two jobs take turns
                    if (network(app, s) != "go") { why = "network lost"; break }
                    if (CallState.info != null) { why = "a call came in"; break }
                    results.put(updateOne(app, pkg))
                }
            }
        } catch (e: Exception) {
            why = "something went wrong (${e.javaClass.simpleName})"
        } finally {
            try { if (wake.isHeld) wake.release() } catch (_: Exception) { }
            StoreInstall.running.set(false)
            RadioManager.end(app, "overnight updates")
        }
        val list = (0 until results.length()).map { results.getJSONObject(it) }
        note(app, list, why)
        Controller.evaluateAsync(app, "updates")   // anything unhidden for the update goes back out of sight
        // the linked WhatsApp client lives in this app, not in the official app, so an update of that one does not drop it: look at it once so a problem is in the log
        try { if (list.any { it.optString("pkg") == "com.whatsapp" && it.optString("status") == "updated" }) { io.github.durkimat.phoneguard.whatsapp.WhatsApp.poll(app); Log.log(app, "updates: WhatsApp updated, link checked: ${io.github.durkimat.phoneguard.whatsapp.WhatsApp.status}") } } catch (_: Exception) { }
    }

    /** One app: one record, with the version before and after. Never throws. */
    private fun updateOne(c: Context, pkg: String): JSONObject {
        val name = label(c, pkg); val before = version(c, pkg).orEmpty()
        fun rec(status: String, detail: String = "") = JSONObject().put("pkg", pkg).put("name", name).put("from", before).put("to", version(c, pkg).orEmpty()).put("status", status).put("detail", detail)
        if (inUse(c, pkg)) return rec("skipped", "in use right now")
        val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
        val wasHidden = Dpm.isOwner(c) && try { dpm.isApplicationHidden(admin, pkg) } catch (_: Exception) { false }
        var outcome: JSONObject? = null
        try {
            val storeHidden = Dpm.isOwner(c) && dpm.isApplicationHidden(admin, StoreApp.PKG)
            StoreInstall.prefs(c).edit().putBoolean("active", true).putBoolean("wasHidden", storeHidden).putLong("at", System.currentTimeMillis()).apply()
            if (storeHidden) dpm.setApplicationHidden(admin, StoreApp.PKG, false)
            if (wasHidden) dpm.setApplicationHidden(admin, pkg, false)
            StoreApp.requestOps(c)
            F2Service.cover(true, "Updating $name...")
            Thread.sleep(2_500)
            StoreInstall.openStorePage(c, pkg)
            val drive = StoreDrive(update = true)
            var steps = 0
            while (outcome == null && steps++ < StoreDrive.MAX_STEPS + 5) {
                Thread.sleep(StoreDrive.LOOK_EVERY_MS)
                if (CallState.info != null) { outcome = rec("failed", "a call came in"); break }
                if ((version(c, pkg).orEmpty()) != before && drive.pressedInstall) { outcome = rec("updated"); break }
                F2Service.cover(true, "Updating $name...")
                val screen = F2Service.readScreen() ?: continue
                when (val s = drive.step(screen.first, screen.second)) {
                    is StoreDrive.Step.Tap -> { val ok = F2Service.clickLabel(s.label); Log.log(c, "update job ($pkg): ${s.label} ${if (ok) "pressed" else "not pressable"}"); if (!ok && s.label == "Update") outcome = rec("failed", "the Update button could not be pressed") }
                    StoreDrive.Step.Open -> StoreInstall.openStorePage(c, pkg)
                    StoreDrive.Step.AskInstallPermission -> StoreApp.requestOps(c)
                    StoreDrive.Step.UpToDate -> outcome = rec("current")
                    is StoreDrive.Step.Fail -> outcome = rec("failed", s.detail)
                    StoreDrive.Step.Wait -> {}
                }
            }
            if (outcome == null) outcome = if (version(c, pkg).orEmpty() != before) rec("updated") else rec("failed", "the store did not finish in time")
        } catch (e: Exception) {
            outcome = rec("failed", "something went wrong (${e.javaClass.simpleName})")
        } finally {
            try { if (wasHidden) dpm.setApplicationHidden(admin, pkg, true) } catch (_: Exception) { }
            StoreInstall.restore(c); StoreInstall.goHome(c)
        }
        Log.log(c, "update job ($pkg): ${outcome!!.optString("status")} ${outcome.optString("from")} -> ${outcome.optString("to")} ${outcome.optString("detail")}")
        return outcome
    }

    /** Remembers a run: when, what happened per app, why it could not run, and when Wi-Fi was last there for it. */
    private fun note(c: Context, results: List<JSONObject>, why: String?) {
        val now = Clock.now(); val p = prefs(c)
        val e = p.edit().putLong("at", now).putString("why", why.orEmpty()).putString("results", JSONArray(results).toString())
        if (why != "no Wi-Fi") e.putLong("lastWifi", now)
        e.apply()
        Log.log(c, "updates: ${results.count { it.optString("status") == "updated" }} updated, ${results.count { it.optString("status") == "failed" }} failed${if (why != null) ", $why" else ""}")
    }

    /** For the check-in: the last run and when the phone last had Wi-Fi for one (0 = never since this started). */
    fun report(c: Context): JSONObject {
        val p = prefs(c); val s = UpdatePlan.settings(Store.config(c))
        return JSONObject().put("on", s.on).put("at", p.getLong("at", 0)).put("why", p.getString("why", "").orEmpty()).put("lastWifi", p.getLong("lastWifi", 0))
            .put("lenMin", s.lenMin).put("apps", JSONArray(storeApps(c).map { JSONObject().put("pkg", it).put("name", label(c, it)).put("version", version(c, it).orEmpty()) }))
            .put("results", try { JSONArray(p.getString("results", "[]")) } catch (_: Exception) { JSONArray() })
    }
}
