package io.github.durkimat.phoneguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import io.github.durkimat.phoneguard.core.Boost
import io.github.durkimat.phoneguard.core.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.Executors

/** The pairing record and the signed conversation with Home Assistant. */
object Link {
    const val POLL_MS = 15 * 60_000L
    private val BACKOFF = longArrayOf(60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, POLL_MS)

    @Volatile var pollMs = POLL_MS // debug builds may shorten it
    private val pool = Executors.newSingleThreadExecutor()

    // ---- the record ------------------------------------------------------------------------

    private fun rec(c: Context): JSONObject =
        try { JSONObject(Store.linkText(c) ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun save(c: Context, o: JSONObject) = Store.setLinkText(c, o.toString())

    fun isPaired(c: Context) = rec(c).optString("did").isNotEmpty()
    fun isPending(c: Context) = rec(c).optBoolean("pending", false)
    fun sas(c: Context): String = rec(c).optString("sas")

    fun summary(c: Context): String {
        val r = rec(c)
        if (!isPaired(c)) return "Not paired"
        val last = r.optLong("lastSync", 0)
        val ago = if (last == 0L) "never" else "${(System.currentTimeMillis() - last) / 1000}s ago"
        val state = if (r.optBoolean("pending")) "waiting for parent to confirm ${r.optString("sas")}" else "paired"
        return "$state; last sync $ago (${r.optString("lastResult", "-")})"
    }

    fun clear(c: Context) { Store.setLinkText(c, null); Keys.delete(); cancelPoll(c) }

    // ---- pairing -----------------------------------------------------------------------------

    @Synchronized
    fun pair(c: Context, qr: String): String {
        val q = JSONObject(qr)
        require(q.getInt("v") == Wire.V) { "unsupported pairing version" }
        val url = q.getString("u"); val did = q.getString("d"); val code = q.getString("c"); val haPk = q.getString("k")
        val pk = Keys.publicB64()
        val n = Wire.nonce()
        val p = JSONObject().put("kind", "pair").put("code", code).put("pk", pk).put("model", Build.MODEL).toString()
        val env = JSONObject().put("v", Wire.V).put("did", did).put("ctr", 1).put("n", n).put("p", p)
            .put("s", Keys.sign(Wire.requestBytes(did, 1, n, p)))
        val reply = JSONObject(post(url, env.toString()))
        reply.optString("error").takeIf { it.isNotEmpty() }?.let { return "pairing refused: $it" }
        val rp = verifyReply(reply, did, n, haPk) ?: return "pairing reply not trusted"
        val sas = Wire.sas(pk, haPk, code)
        if (rp.optString("kind") != "pair_ok" || rp.optString("sas") != sas) return "pairing numbers do not match"
        save(c, JSONObject().put("url", url).put("did", did).put("haPk", haPk).put("ctr", 1L)
            .put("pending", true).put("sas", sas).put("cfgh", "").put("lastResult", "paired"))
        Log.log(c, "PAIRED did=$did, confirm $sas in Home Assistant")
        return "ok sas=$sas"
    }

    private fun verifyReply(reply: JSONObject, did: String, n: String, haPk: String): JSONObject? {
        val p = reply.optString("p"); val t = reply.optLong("t")
        if (reply.optString("did") != did || reply.optString("n") != n) return null
        if (!Wire.verifyHa(haPk, Wire.replyBytes(did, n, t, p), reply.optString("s"))) return null
        return JSONObject(p)
    }

    // ---- sync --------------------------------------------------------------------------------

    @Synchronized
    fun sync(c: Context, reason: String): String {
        val r = rec(c)
        if (!isPaired(c)) return "not paired"
        val did = r.getString("did"); val url = r.getString("url"); val haPk = r.getString("haPk")
        val ctr = r.optLong("ctr", 1) + 1
        r.put("ctr", ctr); save(c, r) // persist first: a counter is never reused, even if the reply is lost
        val n = Wire.nonce()
        val p = payload(c, r.optString("cfgh")).toString()
        val env = JSONObject().put("v", Wire.V).put("did", did).put("ctr", ctr).put("n", n).put("p", p)
            .put("s", Keys.sign(Wire.requestBytes(did, ctr, n, p)))
        val result = try {
            val reply = JSONObject(post(url, env.toString()))
            reply.optString("error").takeIf { it.isNotEmpty() }?.let { throw IllegalStateException("HA said: $it") }
            val rp = verifyReply(reply, did, n, haPk) ?: throw IllegalStateException("reply not trusted")
            apply(c, rp, reply.optLong("t")).also { PowerLog.commit(c); IdleReaper.commit(c); LocationReporter.commit(c) }
        } catch (e: Exception) {
            val f = rec(c).optInt("failures", 0) + 1
            val rr = rec(c).put("failures", f).put("lastResult", "FAILED ${e.javaClass.simpleName}: ${e.message}")
            save(c, rr)
            schedulePoll(c, BACKOFF[(f - 1).coerceAtMost(BACKOFF.size - 1)])
            Log.log(c, "SYNC($reason) failed #$f: ${e.javaClass.simpleName}: ${e.message}")
            return "failed: ${e.message}"
        }
        schedulePoll(c, pollMs)
        Log.log(c, "SYNC($reason) $result")
        return result
    }

    private fun apply(c: Context, rp: JSONObject, serverT: Long): String {
        val r = rec(c).put("lastSync", System.currentTimeMillis()).put("failures", 0).put("drift", serverT - System.currentTimeMillis())
        if (rp.optBoolean("pending")) {
            r.put("pending", true).put("sas", rp.optString("sas")).put("lastResult", "waiting for parent")
            save(c, r); return "pending ${rp.optString("sas")}"
        }
        r.put("pending", false)
        var what = "ok"
        if (!rp.isNull("cfg") && rp.has("cfg")) {
            Store.setConfig(c, rp.getJSONObject("cfg").toString())
            r.put("cfgh", rp.optString("cfgh")); what = "config updated"
        }
        val b = rp.optJSONObject("boost")
        // HA sends an absolute expiry on its own clock; convert it to ours using the server time in the reply
        Store.setBoost(c, if (b == null) null else Boost(b.getString("mode"), b.getLong("until") - serverT + Clock.now()))
        r.put("lastResult", what); save(c, r)
        val cmds = rp.optJSONArray("cmds") ?: JSONArray()
        for (i in 0 until cmds.length()) {
            if (cmds.getJSONObject(i).optString("type") == "release") { release(c); return "RELEASED by parent" }
        }
        Controller.evaluate(c, "sync")
        return what
    }

    /** The parent released this phone: put everything back and give up device owner. */
    private fun release(c: Context) {
        Log.log(c, "release requested by Home Assistant")
        LockdownEnforcer.releaseAll(c)
        Enforcer.restoreEverything(c)
        clear(c)
        Store.clearConfig(c); Store.setBoost(c, null)
        try { Dpm.dpm(c).clearDeviceOwnerApp(c.packageName) } catch (e: Exception) { Log.log(c, "release failed: ${e.message}") }
    }

    private fun payload(c: Context, cfgh: String): JSONObject {
        PowerLog.sample(c, "sync", force = true)
        val cfg = Store.config(c)
        val boost = Store.boost(c)
        val now = Clock.now()
        val eff = Engine.effective(cfg, boost, now)
        val bm = c.getSystemService(BatteryManager::class.java)
        val apps = JSONArray()
        val pm = c.packageManager
        for (pkg in Enforcer.manageable(c, cfg).sorted()) {
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString() } catch (_: Exception) { pkg }
            apps.put(JSONObject().put("pkg", pkg).put("label", label).put("hidden", pkg in Store.hidden(c)))
        }
        val state = JSONObject()
            .put("mode", eff.modeId).put("boost", boost?.let { JSONObject().put("mode", it.mode).put("until", it.until) })
            .put("hidden", Store.hidden(c).size).put("gray", Enforcer.isGray(c)).put("canGray", Enforcer.canWriteSecure(c))
            .put("owner", Dpm.isOwner(c)).put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("version", BuildConfig.VERSION_NAME).put("tz", TimeZone.getDefault().id).put("clock", now)
        state.put("usageAllowed", Usage.allowed(c)).put("canOverlay", TouchBlocker.canDraw(c)).put("canSilence", SoundEnforcer.canSilence(c))
            .put("lockdown", JSONObject().put("launcher", eff.lockdown.launcher).put("touch", eff.lockdown.touch)
                .put("shade", eff.lockdown.shade).put("kiosk", eff.lockdown.kiosk).put("touchBlocked", TouchBlocker.isOn()).put("locked", Kiosk.locked(c)))
        val out = JSONObject().put("kind", "sync").put("cfgh", cfgh).put("state", state).put("apps", apps).put("power", PowerLog.pending(c)).put("reaped", IdleReaper.pending(c)).put("loc", LocationReporter.pending(c))
        try {
            Usage.recent(c)?.let { usage ->
                out.put("usage", usage)
                val labels = JSONObject()
                for (day in usage.keys()) for (pkg in usage.getJSONObject(day).keys()) if (!labels.has(pkg)) {
                    labels.put(pkg, try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString() } catch (_: Exception) { pkg })
                }
                out.put("labels", labels)
            }
        } catch (e: Exception) { Log.log(c, "usage failed: ${e.javaClass.simpleName}") }
        return out
    }

    private fun post(url: String, body: String): String {
        val h = URL(url).openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 15_000; h.readTimeout = 20_000
            h.requestMethod = "POST"; h.doOutput = true
            h.setRequestProperty("Content-Type", "application/json")
            h.outputStream.use { it.write(body.toByteArray()) }
            val code = h.responseCode
            val text = (if (code < 400) h.inputStream else h.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code >= 400) throw IllegalStateException("HTTP $code")
            return text
        } finally { h.disconnect() }
    }

    // ---- scheduling --------------------------------------------------------------------------

    fun runAsync(c: Context, reason: String, pending: BroadcastReceiver.PendingResult? = null) {
        val app = c.applicationContext
        pool.execute { try { sync(app, reason) } catch (e: Exception) { Log.log(app, "SYNC($reason) crashed: ${e.message}") } finally { pending?.finish() } }
    }

    private fun pollIntent(c: Context) = PendingIntent.getBroadcast(
        c, 2, Intent(c, AlarmReceiver::class.java).putExtra("kind", "sync"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedulePoll(c: Context, delayMs: Long) {
        if (!isPaired(c)) return
        c.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pollIntent(c))
    }

    fun cancelPoll(c: Context) { c.getSystemService(AlarmManager::class.java).cancel(pollIntent(c)) }
}
