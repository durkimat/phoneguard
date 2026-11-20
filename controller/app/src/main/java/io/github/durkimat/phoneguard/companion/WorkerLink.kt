package io.github.durkimat.phoneguard.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import io.github.durkimat.phoneguard.AlarmReceiver
import io.github.durkimat.phoneguard.BuildConfig
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Controller
import io.github.durkimat.phoneguard.Dpm
import io.github.durkimat.phoneguard.Keys
import io.github.durkimat.phoneguard.Link
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.Wire
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * The phone's side of the Cloudflare companion (PROTOCOL-DRAFT): pairing with a one-time token, then a signed sync loop. Mirrors [io.github.durkimat.phoneguard.Link]
 * (the Home Assistant link); the two never both run on one phone. State lives in device-protected storage so it works before the PIN is entered.
 */
object WorkerLink {
    const val POLL_MS = 15 * 60_000L
    private val BACKOFF = longArrayOf(60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, POLL_MS)
    private const val V = 1
    private val pool = Executors.newSingleThreadExecutor()

    private fun sp(c: Context) = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("pg-worker", Context.MODE_PRIVATE)
    /** The config version the phone holds (what a change made by message is based on). */
    fun cfgVersion(c: Context): Int = sp(c).getInt("cfgVersion", 0)

    fun isPaired(c: Context) = sp(c).getString("did", "").orEmpty().isNotEmpty()
    fun pendingMatch(c: Context): String? = if (sp(c).getString("pairingId", null) != null && !isPaired(c)) sp(c).getString("match", null) else null
    fun host(c: Context): String = sp(c).getString("host", "").orEmpty()

    fun summary(c: Context): String {
        val s = sp(c)
        if (isPaired(c)) {
            val last = s.getLong("lastSync", 0)
            return "paired with ${host(c)}; last sync ${if (last == 0L) "never" else "${(System.currentTimeMillis() - last) / 1000}s ago"} (${s.getString("lastResult", "-")})"
        }
        return pendingMatch(c)?.let { "waiting for the parent to confirm $it" } ?: "not paired"
    }

    /** "Qin J36" rather than the GSI's generic product model. */
    private fun deviceModel(): String = try {
        val sp = Class.forName("android.os.SystemProperties")
        (sp.getMethod("get", String::class.java, String::class.java).invoke(null, "ro.product.vendor.model", "") as String).ifBlank { Build.MODEL }
    } catch (e: Exception) { Build.MODEL }

    /** Disconnect: forget the Worker, device id and keys' use; everything else on the phone stays as it is. */
    fun disconnect(c: Context) { removeProvisioned(c); forget(c); EncKey.delete(); Log.log(c, "companion: disconnected") }

    fun forget(c: Context) { sp(c).edit().clear().apply(); cancelPoll(c) }

    // ---- pairing -----------------------------------------------------------------------------

    /** Ask the Worker to pair. Returns the 4-digit number to show; the parent types it on their page. Throws with a readable message. */
    @Synchronized
    fun start(c: Context, p: EnrolPayload): String {
        if (isPaired(c)) throw IllegalStateException("already connected: disconnect first")
        val info = JSONObject(http(p.host, "GET", "/api/info", null))
        val serverKey = info.optString("serverKey")
        if (serverKey.isEmpty()) throw IllegalStateException("not a Phone Guard server")
        if (p.fingerprint != null && !CompanionCrypto.fingerprintMatches(serverKey, p.fingerprint)) throw IllegalStateException("server key does not match the code")
        val pub = Keys.publicB64()
        val nonce = Wire.nonce()
        val body = JSONObject().put("token", p.token).put("pub", pub).put("model", deviceModel()).put("app", BuildConfig.VERSION_NAME).put("nonce", nonce)
        EncKey.publicB64()?.let { body.put("encPub", it) }
        Log.log(c, "companion: encryption key (PURPOSE_AGREE_KEY) self-test: ${EncKey.selfTest()}")
        val r = JSONObject(http(p.host, "POST", "/api/pair", body.toString()))
        val match = r.getString("match")
        if (match != CompanionCrypto.matchNumber(p.token, pub)) throw IllegalStateException("server returned a different number")
        sp(c).edit().clear().putString("host", p.host).putString("serverKey", serverKey).putString("pairingId", r.getString("pairingId"))
            .putString("nonce", nonce).putString("match", match).apply()
        Log.log(c, "companion: pairing started with ${p.host}, match $match")
        return match
    }

    /** One poll of a pending pairing: "waiting", "paired" or a refusal text starting "failed". */
    @Synchronized
    fun pollPair(c: Context): String {
        val s = sp(c)
        val id = s.getString("pairingId", null) ?: return "failed: nothing pending"
        val host = s.getString("host", "").orEmpty()
        return try {
            val r = JSONObject(http(host, "GET", "/api/pair/$id", null))
            when (val st = r.optString("status")) {
                "pending" -> "waiting"
                "confirmed" -> {
                    val (rp, t) = verifyReply(c, r.getJSONObject("reply"), s.getString("nonce", "")!!) ?: return "failed: reply not trusted"
                    val did = rp.getString("did")
                    s.edit().putString("did", did).putLong("ctr", 0).putInt("cfgVersion", 0).remove("pairingId").apply()
                    applyConfig(c, rp, t)
                    Log.log(c, "companion: PAIRED did=$did")
                    io.github.durkimat.phoneguard.net.RadioManager.holdFor(c, "first sync", 5 * 60_000L) // no radio sleep until the first sync has had its chance
                    schedulePoll(c, 5_000)
                    "paired"
                }
                else -> { s.edit().remove("pairingId").remove("match").apply(); "failed: $st" }
            }
        } catch (e: Exception) { "waiting (${e.message})" }
    }

    // ---- sync --------------------------------------------------------------------------------

    @Synchronized
    fun sync(c: Context, reason: String): String {
        val s = sp(c)
        if (!isPaired(c)) return "not paired"
        val did = s.getString("did", "")!!; val host = host(c)
        val ctr = nextCtr(c) // persisted first: a counter is never reused, even if the reply is lost
        val n = Wire.nonce()
        val p = payloadWithExtras(c, s.getString("cfgh", "").orEmpty()).toString()
        val env = JSONObject().put("v", V).put("did", did).put("ctr", ctr).put("n", n).put("p", p)
            .put("s", Keys.sign(CompanionCrypto.phoneMessage(V, did, ctr, n, p).toByteArray(Charsets.UTF_8)))
        val result = try {
            val reply = JSONObject(http(host, "POST", "/api/sync", env.toString()))
            val (rp, t) = verifyReply(c, reply, n) ?: throw IllegalStateException("reply not trusted")
            s.edit().putLong("lastSync", System.currentTimeMillis()).putInt("failures", 0).apply()
            val waResults = io.github.durkimat.phoneguard.whatsapp.WaPending.parseResults(rp.optJSONArray("waResults"))
            // the config first (it already carries the changes the Worker applied), THEN the answers: until then a change stays on top of whatever list arrives
            applyConfig(c, rp, t).also { s.edit().putString("lastResult", it).apply(); Link.commitReports(c); io.github.durkimat.phoneguard.whatsapp.WaAdmin.onResults(c, waResults) }
        } catch (e: Exception) {
            val f = s.getInt("failures", 0) + 1
            s.edit().putInt("failures", f).putString("lastResult", "FAILED ${e.javaClass.simpleName}: ${e.message}").apply()
            if (e.message?.contains("HTTP 409") == true) s.edit().putLong("ctr", ctr + 1000).apply() // the server is ahead of us: skip forward
            schedulePoll(c, BACKOFF[(f - 1).coerceAtMost(BACKOFF.size - 1)])
            Log.log(c, "companion sync($reason) failed #$f: ${e.javaClass.simpleName}: ${e.message}")
            if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException) io.github.durkimat.phoneguard.net.RadioManager.ensureReachable(c, "companion sync")
            return "failed: ${e.message}"
        }
        try { io.github.durkimat.phoneguard.calendar.Subscriptions.refresh(c) } catch (e: Exception) { Log.log(c, "calendar refresh crashed: ${e.javaClass.simpleName}") }
        schedulePoll(c, POLL_MS)
        Log.log(c, "companion sync($reason) $result")
        return result
    }

    /** The next signed-request counter, shared by the sync and voice requests so two threads never use the same one. */
    @Synchronized private fun nextCtr(c: Context): Long { val s = sp(c); val ctr = s.getLong("ctr", 0) + 1; s.edit().putLong("ctr", ctr).apply(); return ctr }

    /**
     * Sends a recording to the Worker for transcription (`POST /voice/transcribe`, body = WAV, header X-Voice-Mode). Signed like the sync: the headers
     * X-Pg-V, X-Pg-Did, X-Pg-Ctr, X-Pg-N and X-Pg-S carry version, device id, counter, nonce and the signature over `phoneMessage(V, did, ctr, n, p)`
     * with `p = "voice:" + mode + ":" + sha256hex(body)`, so the body cannot be swapped. The audio is not stored on the phone.
     */
    fun voiceTranscribe(c: Context, wav: ByteArray, mode: String): io.github.durkimat.phoneguard.voice.VoiceReply {
        if (!isPaired(c)) return io.github.durkimat.phoneguard.voice.VoiceReply.Failed("Voice needs the family app")
        return try {
            val (code, text) = signedPost(c, "/api/voice/transcribe", "voice:$mode", wav, "audio/wav", mapOf("X-Voice-Mode" to mode), 30_000)
            io.github.durkimat.phoneguard.voice.VoiceReply.parse(code, text)
        } catch (e: Exception) {
            Log.log(c, "voice: ${e.javaClass.simpleName}")
            io.github.durkimat.phoneguard.voice.VoiceReply.Failed(if (e is java.net.UnknownHostException || e is java.net.ConnectException) io.github.durkimat.phoneguard.voice.VoiceReply.NO_NETWORK else "Voice isn't working right now")
        }
    }

    /**
     * "Navigate": `kind` is "search" or "route" (`/api/geo/<kind>`), [json] the request body. Signed like voice with the tag `geo:<kind>`. Returns the status
     * code and body, or null when the Worker could not be reached (no network).
     */
    fun geo(c: Context, kind: String, json: String): Pair<Int, String>? {
        if (!isPaired(c)) return 0 to "{}"
        return try { signedPost(c, "/api/geo/$kind", "geo:$kind", json.toByteArray(Charsets.UTF_8), "application/json", emptyMap(), 25_000) }
        catch (e: Exception) { Log.log(c, "geo $kind: ${e.javaClass.simpleName}"); null }
    }

    /** One map tile (`GET /api/geo/tile/z/x/y`, signed with the tag `geo:tile` over the text "z/x/y"): the status code and the PNG bytes, or null with no network. */
    fun tile(c: Context, z: Int, x: Int, y: Int): Pair<Int, ByteArray>? {
        if (!isPaired(c)) return 0 to ByteArray(0)
        val key = "$z/$x/$y"
        return try { signedGet(c, "/api/geo/tile/$key", "geo:tile", key, 20_000) }
        catch (e: Exception) { Log.log(c, "geo tile: ${e.javaClass.simpleName}"); null }
    }

    private fun signedGet(c: Context, path: String, tag: String, signed: String, readTimeoutMs: Int): Pair<Int, ByteArray> {
        val s = sp(c)
        val did = s.getString("did", "")!!; val host = host(c)
        val ctr = nextCtr(c); val n = Wire.nonce()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(signed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val sig = Keys.sign(CompanionCrypto.phoneMessage(V, did, ctr, n, "$tag:$hash").toByteArray(Charsets.UTF_8))
        val h = URL("https://$host$path").openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 15_000; h.readTimeout = readTimeoutMs; h.instanceFollowRedirects = false
            h.requestMethod = "GET"
            h.setRequestProperty("X-Pg-V", V.toString()); h.setRequestProperty("X-Pg-Did", did); h.setRequestProperty("X-Pg-Ctr", ctr.toString())
            h.setRequestProperty("X-Pg-N", n); h.setRequestProperty("X-Pg-S", sig)
            val code = h.responseCode
            val bytes = (if (code < 400) h.inputStream else h.errorStream)?.readBytes() ?: ByteArray(0)
            if (code == 409) s.edit().putLong("ctr", ctr + 1000).apply()
            return code to bytes
        } finally { h.disconnect() }
    }

    /**
     * One signed POST with a binary or JSON body: headers X-Pg-V, X-Pg-Did, X-Pg-Ctr, X-Pg-N and X-Pg-S, the signature over
     * `phoneMessage(V, did, ctr, n, p)` with `p = tag + ":" + sha256hex(body)` so the body cannot be swapped. The counter is shared with the sync.
     * Returns the status code and body text; throws on network errors.
     */
    private fun signedPost(c: Context, path: String, tag: String, body: ByteArray, contentType: String, extra: Map<String, String>, readTimeoutMs: Int): Pair<Int, String> {
        val s = sp(c)
        val did = s.getString("did", "")!!; val host = host(c)
        val ctr = nextCtr(c); val n = Wire.nonce()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val sig = Keys.sign(CompanionCrypto.phoneMessage(V, did, ctr, n, "$tag:$hash").toByteArray(Charsets.UTF_8))
        val h = URL("https://$host$path").openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 15_000; h.readTimeout = readTimeoutMs; h.instanceFollowRedirects = false
            h.requestMethod = "POST"; h.doOutput = true
            h.setRequestProperty("Content-Type", contentType)
            extra.forEach { (k, v) -> h.setRequestProperty(k, v) }
            h.setRequestProperty("X-Pg-V", V.toString()); h.setRequestProperty("X-Pg-Did", did); h.setRequestProperty("X-Pg-Ctr", ctr.toString())
            h.setRequestProperty("X-Pg-N", n); h.setRequestProperty("X-Pg-S", sig)
            h.setFixedLengthStreamingMode(body.size)
            h.outputStream.use { it.write(body) }
            val code = h.responseCode
            val text = (if (code < 400) h.inputStream else h.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code == 409) s.edit().putLong("ctr", ctr + 1000).apply() // the server is ahead of us: skip forward
            return code to text
        } finally { h.disconnect() }
    }

    /** Signature, device id and echoed nonce must all check out against the pinned server key; returns the signed payload. */
    private fun verifyReply(c: Context, reply: JSONObject, nonce: String): Pair<JSONObject, Long>? {
        val s = sp(c)
        val did = reply.optString("did"); val p = reply.optString("p")
        if (reply.optString("n") != nonce) return null
        val mine = s.getString("did", "").orEmpty()
        if (mine.isNotEmpty() && did != mine) return null
        val key = s.getString("serverKey", "").orEmpty()
        val msg = CompanionCrypto.serverMessage(reply.optInt("v"), did, nonce, reply.optLong("t"), p)
        if (!CompanionCrypto.verify(key, msg, reply.optString("s"))) return null
        return JSONObject(p) to reply.optLong("t")
    }

    private fun applyConfig(c: Context, rp: JSONObject, serverT: Long): String {
        val v = rp.optInt("configVersion", 0)
        mailRetry = false
        val what = Link.applyShared(c, rp, serverT, { withOpenedSecrets(c, it) },
            { sp(c).edit().putString("cfgh", if (mailRetry || io.github.durkimat.phoneguard.ScreenLock.retry) "" else it).putInt("cfgVersion", v).apply() }, // an empty hash makes the server send the config again next time
            { removeProvisioned(c); forget(c); EncKey.delete() })
        return "$what (v$v)"
    }

    /**
     * The profile's sealed secrets are opened with the phone's encryption key and handed to the existing code under the names it already reads
     * (the Spotify refresh token goes to `spotifyRefresh`, which Spotify.adopt() takes up); the sealed blobs themselves are not kept.
     */
    private fun withOpenedSecrets(c: Context, profile: JSONObject): JSONObject {
        val out = JSONObject(profile.toString())
        val secrets = out.optJSONObject("secrets") ?: JSONObject()
        out.remove("secrets")
        val did = sp(c).getString("did", "").orEmpty()
        val key = EncKey.privateKey()
        secrets.optJSONObject("spotify.refresh")?.let { sealed ->
            val plain = if (key == null) null else SealedSecret.open(key, did, "spotify.refresh", sealed)
            if (plain != null) { out.put("spotifyRefresh", plain); Log.log(c, "companion: opened sealed spotify.refresh") }
            else Log.log(c, "companion: could not open sealed spotify.refresh")
        }
        provisionMail(c, out, secrets, did, key)
        provisionCalendars(c, out, secrets, did, key)
        try { io.github.durkimat.phoneguard.ScreenLock.applyFromConfig(c, out.optJSONObject("lock"), secrets, did, key) } catch (e: Exception) { Log.log(c, "screen lock: crashed: ${e.javaClass.simpleName}") }
        return out
    }

    // ---- email and calendars given by the parent ----------------------------------------------------

    /** Set when this config pass hit a network problem setting up the mail account, so the same config is sent again at the next check-in. */
    @Volatile private var mailRetry = false

    private fun shortErr(s: String) = s.replace(Regex("[\\r\\n]+"), " ").take(60)

    /**
     * `config.email` + `secrets["email.password"]`: signs in and keeps the account (Mail.setup) only when the account or password changed (a fingerprint
     * is kept, never the password). A wrong password is not retried until the parent sends a new one; a network failure is. If the parent removes the
     * account, the phone removes it too, but only an account the parent provisioned (never one typed on the phone).
     */
    private fun provisionMail(c: Context, cfg: JSONObject, secrets: JSONObject, did: String, key: java.security.PrivateKey?) {
        val s = sp(c)
        val e = cfg.optJSONObject("email")
        if (e == null) {
            if (s.getBoolean("mailProvisioned", false)) {
                io.github.durkimat.phoneguard.mail.Mail.remove(c); s.edit().remove("mailProvisioned").remove("mailFp").remove("mailErr").apply()
                Log.log(c, "companion: email removed by the parent")
            }
            return
        }
        try {
            val slot = e.optString("secret", "email.password")
            val plain = secrets.optJSONObject(slot)?.let { if (key == null) null else SealedSecret.open(key, did, slot, it) }
            if (plain == null) { s.edit().putString("mailErr", "Password could not be opened").apply(); Log.log(c, "companion: could not open sealed $slot"); return }
            val imap = e.getJSONObject("imap"); val smtp = e.getJSONObject("smtp")
            val address = e.getString("address"); val user = e.optString("user", address).ifBlank { address }
            val acct = io.github.durkimat.phoneguard.mail.MailAccount(address, user = user, smtpUser = user,
                imapHost = imap.getString("host"), imapPort = imap.getInt("port"), imapSec = imap.getString("security"),
                smtpHost = smtp.getString("host"), smtpPort = smtp.getInt("port"), smtpSec = smtp.getString("security"))
            val fp = CompanionCrypto.sha256Hex(listOf(acct.address, acct.user, acct.imapHost, acct.imapPort, acct.imapSec, acct.smtpHost, acct.smtpPort, acct.smtpSec, plain).joinToString("|"))
            val have = io.github.durkimat.phoneguard.mail.Mail.account(c)
            if (s.getString("mailFp", "") == fp && have != null && s.getString("mailErr", "").isNullOrEmpty()) return // nothing changed: no new sign-in
            if (s.getString("mailFp", "") == fp && s.getString("mailErr", "").orEmpty().startsWith("Wrong")) return // already refused: wait for a new password
            val err = io.github.durkimat.phoneguard.mail.Mail.setup(c, acct, plain)
            if (err.isEmpty()) { s.edit().putString("mailFp", fp).putBoolean("mailProvisioned", true).remove("mailErr").apply(); Log.log(c, "companion: email account set up") }
            else {
                s.edit().putString("mailFp", fp).putString("mailErr", shortErr(err)).apply()
                if (!err.startsWith("Wrong")) mailRetry = true
                Log.log(c, "companion: email setup failed: ${shortErr(err)}") // the reason from the mail code never contains the password
            }
        } catch (ex: Exception) { s.edit().putString("mailErr", "Bad email settings").apply(); Log.log(c, "companion: email config unusable: ${ex.javaClass.simpleName}") }
    }

    /** `config.calendars` + `secrets["calendar.<id>"]` become read-only subscriptions; what the parent removed is removed here. */
    private fun provisionCalendars(c: Context, cfg: JSONObject, secrets: JSONObject, did: String, key: java.security.PrivateKey?) {
        val list = cfg.optJSONArray("calendars")
        val wanted = ArrayList<Triple<String, String, String?>>()
        if (list != null) for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("id"); if (id.isEmpty()) continue
            val slot = o.optString("secret", "calendar.$id")
            val url = secrets.optJSONObject(slot)?.let { if (key == null) null else SealedSecret.open(key, did, slot, it) }
            if (url == null) Log.log(c, "companion: could not open sealed calendar link")
            wanted += Triple(id, o.optString("name", "Calendar").ifBlank { "Calendar" }, url)
        }
        io.github.durkimat.phoneguard.calendar.Subscriptions.apply(c, wanted)
    }

    /** Disconnecting or being released: the mail account and calendars the parent provisioned go with the link. */
    private fun removeProvisioned(c: Context) {
        val s = sp(c)
        if (s.getBoolean("mailProvisioned", false)) io.github.durkimat.phoneguard.mail.Mail.remove(c)
        io.github.durkimat.phoneguard.calendar.Subscriptions.clear(c)
    }

    private fun payloadWithExtras(c: Context, cfgh: String): JSONObject {
        val p = Link.payload(c, cfgh)
        val st = p.optJSONObject("state") ?: JSONObject().also { p.put("state", it) }
        val s = sp(c)
        val prov = s.getBoolean("mailProvisioned", false) || !s.getString("mailErr", "").isNullOrEmpty()
        if (prov) {
            val m = JSONObject().put("configured", s.getBoolean("mailProvisioned", false) && io.github.durkimat.phoneguard.mail.Mail.account(c) != null)
            s.getString("mailErr", "")?.takeIf { it.isNotEmpty() }?.let { m.put("error", it) }
            st.put("mail", m)
        }
        val cals = io.github.durkimat.phoneguard.calendar.Subscriptions.status(c)
        if (cals.length() > 0) st.put("calendars", cals)
        io.github.durkimat.phoneguard.whatsapp.WhatsApp.allowSummary(c).let { (all, n) ->
            st.put("wa", JSONObject().put("linked", io.github.durkimat.phoneguard.whatsapp.WhatsApp.linked(c)).put("status", io.github.durkimat.phoneguard.whatsapp.WhatsApp.status).put("all", all).put("allowed", n)
                // the changes made by message in the admin group and not yet answered by the Worker, and the groups the phone is in: they go in THIS sync, before the reply's config is applied
                .put("admin", io.github.durkimat.phoneguard.whatsapp.WaPending.payload(c, io.github.durkimat.phoneguard.whatsapp.WaPending.groupsAt(c), io.github.durkimat.phoneguard.whatsapp.WaPending.cachedGroups(c))))
        }
        try { st.put("lock", io.github.durkimat.phoneguard.ScreenLock.state(c)) } catch (_: Exception) { }
        try { io.github.durkimat.phoneguard.net.DataUsage.state(c)?.let { st.put("data", it) } } catch (_: Exception) { }
        try { st.put("store", io.github.durkimat.phoneguard.StoreApp.state(c)) } catch (_: Exception) { }
        st.put("storeInstall", io.github.durkimat.phoneguard.StoreInstall.SUPPORTED)
        st.put("spotifyInstalled", io.github.durkimat.phoneguard.music.MusicController.spotifyInstalled(c))
        return p
    }

    // ---- network and scheduling ----------------------------------------------------------------

    private fun http(host: String, method: String, path: String, body: String?): String {
        val h = URL("https://$host$path").openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 15_000; h.readTimeout = 20_000; h.instanceFollowRedirects = false
            h.requestMethod = method
            if (body != null) { h.doOutput = true; h.setRequestProperty("Content-Type", "application/json"); h.outputStream.use { it.write(body.toByteArray()) } }
            val code = h.responseCode
            val text = (if (code < 400) h.inputStream else h.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code >= 400) throw IllegalStateException("HTTP $code ${runCatching { JSONObject(text).optString("error") }.getOrDefault("")}".trim())
            return text
        } finally { h.disconnect() }
    }

    fun runAsync(c: Context, reason: String, pending: android.content.BroadcastReceiver.PendingResult? = null) {
        val app = c.applicationContext
        if (!isPaired(app)) { pending?.finish(); return }
        io.github.durkimat.phoneguard.net.RadioManager.begin(app, "companion $reason")
        pool.execute {
            try {
                val radio = io.github.durkimat.phoneguard.net.RadioManager
                // no usable network in the window (Wi-Fi switched on but not connected, data off): turn mobile data on and wait again, rather than failing the sync
                if (!radio.awaitNetwork(app, 20_000)) { radio.ensureReachable(app, "companion $reason"); radio.awaitNetwork(app, 40_000) }
                sync(app, reason)
            }
            catch (e: Exception) { Log.log(app, "companion sync($reason) crashed: ${e.message}") }
            finally { io.github.durkimat.phoneguard.net.RadioManager.end(app, "companion $reason"); pending?.finish() }
        }
    }

    /** Pairing runs on the caller's thread-pool slot too, so it never blocks the UI. */
    fun async(block: () -> Unit) { pool.execute(block) }

    private fun pollIntent(c: Context) = PendingIntent.getBroadcast(
        c, 3, Intent(c, AlarmReceiver::class.java).putExtra("kind", "wsync"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedulePoll(c: Context, delayMs: Long) {
        if (!isPaired(c)) return
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pollIntent(c))
    }

    fun cancelPoll(c: Context) { c.getSystemService(AlarmManager::class.java).cancel(pollIntent(c)) }
}
