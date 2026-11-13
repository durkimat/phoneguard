package io.github.durkimat.phoneguard.mail

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** The mail kept on the phone: the account (the password sealed, never plain), the header lists and the bodies opened so far. */
class MailStore(private val dir: File) {
    init { dir.mkdirs() }
    private fun file(n: String) = File(dir, n)
    private fun read(n: String): String? = try { file(n).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
    private fun write(n: String, s: String) { try { val t = file("$n.tmp"); t.writeText(s); t.renameTo(file(n)) } catch (_: Exception) { } }

    fun account(): MailAccount? = try {
        read("account.json")?.let { val j = JSONObject(it); MailAccount(j.getString("address"), j.optString("name"), j.getString("user"), j.getString("smtpUser"),
            j.getString("imapHost"), j.getInt("imapPort"), j.getString("imapSec"), j.getString("smtpHost"), j.getInt("smtpPort"), j.getString("smtpSec"), j.optString("hint")) }
    } catch (_: Exception) { null }
    fun saveAccount(a: MailAccount, sealedPassword: String) {
        write("account.json", JSONObject().put("address", a.address).put("name", a.name).put("user", a.user).put("smtpUser", a.smtpUser).put("imapHost", a.imapHost)
            .put("imapPort", a.imapPort).put("imapSec", a.imapSec).put("smtpHost", a.smtpHost).put("smtpPort", a.smtpPort).put("smtpSec", a.smtpSec).put("hint", a.hint).toString())
        write("password.txt", sealedPassword)
    }
    fun sealedPassword(): String? = read("password.txt")

    fun headers(sent: Boolean): List<MailHeader> = try {
        val a = JSONArray(read(if (sent) "sent.json" else "inbox.json") ?: "[]")
        (0 until a.length()).map { val j = a.getJSONObject(it); MailHeader(j.getLong("uid"), j.getString("from"), j.getString("addr"), j.optString("to"), j.getString("subject"), j.getLong("date"), j.getBoolean("unread")) }
    } catch (_: Exception) { emptyList() }
    fun saveHeaders(sent: Boolean, list: List<MailHeader>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("uid", it.uid).put("from", it.from).put("addr", it.addr).put("to", it.to).put("subject", it.subject).put("date", it.date).put("unread", it.unread)) }
        write(if (sent) "sent.json" else "inbox.json", a.toString())
    }

    fun body(sent: Boolean, uid: Long): String? = read("body-${if (sent) "s" else "i"}-$uid.txt")
    fun saveBody(sent: Boolean, uid: Long, text: String) { write("body-${if (sent) "s" else "i"}-$uid.txt", text) }
    /** Forgets bodies of messages that are no longer in either list, so the phone does not fill up. */
    fun prune() {
        val keep = headers(false).map { "body-i-${it.uid}.txt" }.toSet() + headers(true).map { "body-s-${it.uid}.txt" }.toSet()
        dir.listFiles()?.filter { it.name.startsWith("body-") && it.name !in keep }?.forEach { it.delete() }
    }

    var lastSync: Long
        get() = read("sync.txt")?.trim()?.toLongOrNull() ?: 0L
        set(v) { write("sync.txt", v.toString()) }

    fun forget() { dir.listFiles()?.forEach { it.delete() } }
}

/** Seals a password with a key the phone keeps in its secure hardware store, so the file on disk is useless on its own. */
object Vault {
    private const val ALIAS = "pg_mail"
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }
    fun seal(plain: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val enc = java.util.Base64.getEncoder()
        return enc.encodeToString(c.iv) + ":" + enc.encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
    fun open(sealed: String): String? = try {
        val (iv, data) = sealed.split(':').map { java.util.Base64.getDecoder().decode(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(c.doFinal(data), Charsets.UTF_8)
    } catch (_: Exception) { null }
}

/** What the launcher calls. Every function that touches the network must be called off the main thread. */
object Mail {
    const val DEMO_DOMAIN = "@demo.local"
    const val HEADERS = 30
    private val demo = DemoMail()
    private fun store(c: Context) = MailStore(File(c.filesDir, "mail"))

    fun account(c: Context): MailAccount? = store(c).account()
    fun provider(c: Context): MailProvider? {
        val s = store(c); val a = s.account() ?: return null
        if (a.address.endsWith(DEMO_DOMAIN)) return demo
        val pw = s.sealedPassword()?.let { Vault.open(it) } ?: return null
        return ImapSmtpProvider(a, pw)
    }
    fun inbox(c: Context) = store(c).headers(false)
    fun sent(c: Context) = store(c).headers(true)
    fun unread(c: Context) = inbox(c).count { it.unread }
    fun lastSync(c: Context) = store(c).lastSync
    fun stale(c: Context, maxAgeMs: Long = 15 * 60_000L) = System.currentTimeMillis() - lastSync(c) > maxAgeMs

    /** Signs in with the new account and, if it works, keeps it. Returns "" or a short reason. */
    fun setup(c: Context, a: MailAccount, password: String): String = try {
        val p: MailProvider = if (a.address.endsWith(DEMO_DOMAIN)) demo else ImapSmtpProvider(a, password)
        p.check()
        val s = store(c); s.forget(); s.saveAccount(a, if (a.address.endsWith(DEMO_DOMAIN)) "" else Vault.seal(password)); ""
    } catch (e: MailException) { (e.message ?: "Failed") + (if (a.hint.isNotBlank() && (e.message ?: "").startsWith("Wrong")) ". " + a.hint else "") }
    catch (e: Exception) { "Failed: " + (e.message ?: e.javaClass.simpleName).take(40) }

    fun remove(c: Context) { store(c).forget() }

    /** Reads both lists from the server and keeps them. Returns "" or a short reason. */
    fun sync(c: Context): String = try {
        val p = provider(c)
        val s = store(c)
        if (p == null) "No account" else {
        val inbox = p.inbox(HEADERS); val sent = try { p.sent(HEADERS) } catch (_: MailException) { s.headers(true) }
        s.saveHeaders(false, inbox); s.saveHeaders(true, sent); s.prune(); s.lastSync = System.currentTimeMillis(); ""
        }
    } catch (e: MailException) { e.message ?: "Failed" } catch (e: Exception) { "Failed" }

    /** The text of a message: kept after the first time. Opening an unread message marks it read here as well as on the server. */
    fun body(c: Context, sent: Boolean, uid: Long): String {
        val s = store(c)
        s.body(sent, uid)?.let { markRead(s, sent, uid); return it }
        return try {
            val t = provider(c)?.body(sent, uid)
            if (t == null) "Not available" else { s.saveBody(sent, uid, t); markRead(s, sent, uid); t }
        } catch (e: MailException) { "Could not load: " + (e.message ?: "") } catch (_: Exception) { "Could not load" }
    }
    private fun markRead(s: MailStore, sent: Boolean, uid: Long) {
        if (sent) return
        val l = s.headers(false); if (l.any { it.uid == uid && it.unread }) s.saveHeaders(false, l.map { if (it.uid == uid) it.copy(unread = false) else it })
    }

    fun setSeen(c: Context, uid: Long, seen: Boolean): String = try {
        provider(c)?.setSeen(uid, seen); val s = store(c); s.saveHeaders(false, s.headers(false).map { if (it.uid == uid) it.copy(unread = !seen) else it }); ""
    } catch (e: MailException) { e.message ?: "Failed" } catch (_: Exception) { "Failed" }

    fun delete(c: Context, sent: Boolean, uid: Long): String = try {
        provider(c)?.delete(sent, uid); val s = store(c); s.saveHeaders(sent, s.headers(sent).filter { it.uid != uid }); ""
    } catch (e: MailException) { e.message ?: "Failed" } catch (_: Exception) { "Failed" }

    fun send(c: Context, to: List<String>, subject: String, body: String): String = try {
        val p = provider(c)
        if (p == null) "No account" else { p.send(to, subject, body); "" }
    } catch (e: MailException) { e.message ?: "Failed" } catch (_: Exception) { "Failed" }
}
