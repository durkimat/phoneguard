package io.github.durkimat.phoneguard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import io.github.durkimat.phoneguard.core.AppCmd
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Installs, updates and removes apps for a parent, from jobs HA sends in the settings. As device owner it may do this silently.
 * It waits for Wi-Fi, enough battery and enough storage; downloads over https; checks the SHA-256 HA sent; and installs through
 * the package installer (Android itself refuses an app signed by a different key than the one installed). Each job is done once;
 * its result goes back to HA with the next sync. Protected apps can never be removed. Phone Guard may update itself this way.
 */
object AppInstaller {
    private const val MAX_BYTES = 400L * 1024 * 1024
    private const val ACTION = "io.github.durkimat.phoneguard.INSTALL_RESULT"
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    class Result(val status: Int, val message: String)
    private val waiting = ConcurrentHashMap<String, CountDownLatch>()
    private val results = ConcurrentHashMap<String, Result>()

    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("installer", Context.MODE_PRIVATE)
    private fun file(c: Context) = File(c.createDeviceProtectedStorageContext().filesDir, "installs.log")

    /** The finished jobs: [id, "done" or "failed", detail, time ms]; kept until HA has them. */
    @Synchronized
    private fun record(c: Context, id: String, status: String, detail: String) {
        try {
            prefs(c).edit().putString("r_$id", status).apply()
            file(c).appendText(JSONArray().put(id).put(status).put(detail.take(120)).put(System.currentTimeMillis()).toString() + "\n")
            Log.log(c, "app job $id: $status ${detail.take(80)}")
        } catch (_: Exception) { }
    }

    @Synchronized
    fun pending(c: Context): JSONArray {
        val out = JSONArray()
        try {
            val p = prefs(c); val since = p.getLong("uploaded", 0); var last = since
            file(c).takeIf { it.exists() }?.forEachLine { l ->
                try { val a = JSONArray(l); val t = a.getLong(3); if (t > since && out.length() < 50) { out.put(a); last = maxOf(last, t) } } catch (_: Exception) { }
            }
            p.edit().putLong("inflight", last).apply()
        } catch (_: Exception) { }
        return out
    }

    fun commit(c: Context) { val p = prefs(c); p.edit().putLong("uploaded", p.getLong("inflight", p.getLong("uploaded", 0))).apply() }

    /** Start the jobs on their own thread (one at a time); called after each settings update. */
    fun processSoon(c: Context) {
        val app = c.applicationContext
        if (Store.config(app)?.appCommands.isNullOrEmpty() || !running.compareAndSet(false, true)) return
        Thread { try { process(app) } catch (e: Exception) { Log.log(app, "app jobs: ${e.javaClass.simpleName}") } finally { running.set(false) } }.start()
    }

    private fun process(c: Context) {
        val cfg = Store.config(c) ?: return
        val p = prefs(c)
        for (cmd in cfg.appCommands) {
            if (cmd.id.isEmpty() || p.getString("r_${cmd.id}", null) != null) continue // done already
            val wait = whyWait(c, cmd)
            if (wait != null) { Log.log(c, "app job ${cmd.id} waits: $wait"); continue }
            val protectedApps = Protected.all(c, cfg)
            when {
                cmd.kind == "remove" -> if (cmd.pkg in protectedApps || cmd.pkg == c.packageName) record(c, cmd.id, "failed", "protected app") else remove(c, cmd)
                cmd.kind == "install" && cmd.pkg in protectedApps && cmd.pkg != c.packageName -> record(c, cmd.id, "failed", "protected app")
                cmd.kind == "install" || cmd.kind == "update" -> install(c, cmd)
                else -> record(c, cmd.id, "failed", "unknown job")
            }
        }
    }

    /** Why a job should wait for later (null when it can go now). */
    private fun whyWait(c: Context, cmd: AppCmd): String? {
        if (cmd.kind == "remove") return null
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) != true) return "no Wi-Fi"
        val bat = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = (bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100) * 100 / (bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
        if (level < 25 && !charging) return "battery $level%"
        val free = StatFs(c.cacheDir.path).availableBytes
        if (cmd.size > 0 && free < cmd.size * 2 + 50L * 1024 * 1024) return "storage"
        return null
    }

    private fun download(c: Context, cmd: AppCmd): File? {
        if (!cmd.url.startsWith("https://")) { record(c, cmd.id, "failed", "address is not https"); return null }
        val dir = File(c.cacheDir, "apks").apply { mkdirs() }
        val out = File(dir, "${cmd.id}.apk")
        try {
            val h = URL(cmd.url).openConnection() as HttpURLConnection
            h.connectTimeout = 20_000; h.readTimeout = 60_000; h.instanceFollowRedirects = true
            if (h.responseCode != 200) { record(c, cmd.id, "failed", "download HTTP ${h.responseCode}"); return null }
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            h.inputStream.use { ins -> out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n; if (total > MAX_BYTES) { record(c, cmd.id, "failed", "too large"); out.delete(); return null }
                    md.update(buf, 0, n); os.write(buf, 0, n)
                }
            } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(cmd.sha256, ignoreCase = true)) { record(c, cmd.id, "failed", "checksum does not match"); out.delete(); return null }
            return out
        } catch (e: Exception) { record(c, cmd.id, "failed", "download: ${e.javaClass.simpleName}"); out.delete(); return null }
    }

    private fun sender(c: Context, id: String): android.content.IntentSender =
        PendingIntent.getBroadcast(c, id.hashCode(), Intent(c, InstallResultReceiver::class.java).setAction(ACTION).putExtra("job", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender

    private fun install(c: Context, cmd: AppCmd) {
        val apk = download(c, cmd) ?: return
        try {
            val pi = c.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(cmd.pkg)
            if (android.os.Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val sid = pi.createSession(params)
            val session = pi.openSession(sid)
            apk.inputStream().use { ins -> session.openWrite("app.apk", 0, apk.length()).use { os -> ins.copyTo(os); session.fsync(os) } }
            val latch = CountDownLatch(1); waiting[cmd.id] = latch
            session.commit(sender(c, cmd.id))
            session.close()
            if (cmd.pkg == c.packageName) { record(c, cmd.id, "done", "installing the new version"); return } // this process is replaced
            if (!latch.await(180, TimeUnit.SECONDS)) { record(c, cmd.id, "failed", "no answer from Android"); return }
            val r = results.remove(cmd.id)
            if (r?.status == PackageInstaller.STATUS_SUCCESS) record(c, cmd.id, "done", "installed ${cmd.name}") else record(c, cmd.id, "failed", r?.message ?: "install failed")
        } catch (e: Exception) { record(c, cmd.id, "failed", "install: ${e.javaClass.simpleName} ${e.message?.take(60)}") }
        finally { waiting.remove(cmd.id); apk.delete() }
    }

    private fun remove(c: Context, cmd: AppCmd) {
        try {
            if (try { c.packageManager.getPackageInfo(cmd.pkg, 0); false } catch (_: Exception) { true }) { record(c, cmd.id, "done", "was not installed"); return }
            val latch = CountDownLatch(1); waiting[cmd.id] = latch
            c.packageManager.packageInstaller.uninstall(cmd.pkg, sender(c, cmd.id))
            if (!latch.await(60, TimeUnit.SECONDS)) { record(c, cmd.id, "failed", "no answer from Android"); return }
            val r = results.remove(cmd.id)
            if (r?.status == PackageInstaller.STATUS_SUCCESS) record(c, cmd.id, "done", "removed ${cmd.name}") else record(c, cmd.id, "failed", r?.message ?: "remove failed")
        } catch (e: Exception) { record(c, cmd.id, "failed", "remove: ${e.javaClass.simpleName}") }
        finally { waiting.remove(cmd.id) }
    }

    /** Android's answer to a session commit or an uninstall. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val job = i.getStringExtra("job") ?: return
            val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) { results[job] = Result(status, "Android wanted a confirmation"); waiting[job]?.countDown(); return }
            results[job] = Result(status, msg)
            waiting[job]?.countDown()
        }
    }
}
