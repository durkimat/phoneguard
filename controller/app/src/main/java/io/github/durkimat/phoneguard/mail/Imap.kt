package io.github.durkimat.phoneguard.mail

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** "ssl" (implicit TLS), "starttls", or "none" (tests only). */
object Security { const val SSL = "ssl"; const val STARTTLS = "starttls"; const val NONE = "none" }

class MailException(message: String) : Exception(message)

/** Opens a connection the way the account says, always checking the server's name when TLS is used. */
internal fun openSocket(host: String, port: Int, security: String, timeoutMs: Int): Socket {
    val plain = Socket()
    plain.soTimeout = timeoutMs
    plain.connect(InetSocketAddress(host, port), timeoutMs)
    return if (security == Security.SSL) startTls(plain, host, port) else plain
}

internal fun startTls(plain: Socket, host: String, port: Int): SSLSocket {
    val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
    val p = s.sslParameters; p.endpointIdentificationAlgorithm = "HTTPS"; s.sslParameters = p
    s.soTimeout = plain.soTimeout
    s.startHandshake()
    return s
}

/**
 * Just enough IMAP4rev1 for a small mail screen: log in, list folders, read headers and bodies, set flags, move to trash. One connection per
 * job, so there is no state to go stale. Responses with literals ({n}) are read exactly; no other IMAP feature is used.
 */
class ImapClient(private val host: String, private val port: Int, private val security: String, private val timeoutMs: Int = 20_000) : AutoCloseable {
    private var sock: Socket? = null
    private lateinit var input: InputStream
    private lateinit var out: OutputStream
    private var n = 0

    /** One untagged response: its text with each literal replaced by \u0001 and its place number, and the literals themselves. */
    class Item(val text: String, val literals: List<ByteArray>)
    class Reply(val items: List<Item>, val status: String, val tail: String)

    fun connect() {
        val s = openSocket(host, port, if (security == Security.SSL) Security.SSL else Security.NONE, timeoutMs)
        use(s)
        val greeting = readItem()
        if (!greeting.text.startsWith("* OK") && !greeting.text.startsWith("* PREAUTH")) throw MailException("Server said: " + greeting.text.take(60))
        if (security == Security.STARTTLS) {
            val r = command("STARTTLS"); if (r.status != "OK") throw MailException("STARTTLS refused")
            use(startTls(sock!!, host, port))
        }
    }

    private fun use(s: Socket) { sock = s; input = BufferedInputStream(s.getInputStream()); out = s.getOutputStream() }

    private fun readLine(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw MailException("Connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return String(b.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readItem(): Item {
        val text = StringBuilder(); val lits = ArrayList<ByteArray>()
        var line = readLine()
        while (true) {
            val m = Regex("\\{(\\d+)\\}$").find(line)
            if (m == null) { text.append(line); break }
            text.append(line.substring(0, m.range.first)).append('\u0001').append(lits.size)
            val len = m.groupValues[1].toInt(); val buf = ByteArray(len); var got = 0
            while (got < len) { val r = input.read(buf, got, len - got); if (r < 0) throw MailException("Connection closed"); got += r }
            lits += buf
            line = readLine()
        }
        return Item(text.toString(), lits)
    }

    /** Sends one command and collects everything until its tagged answer. [secret] hides the text from error messages. */
    fun command(cmd: String): Reply {
        val tag = "A" + (++n).toString().padStart(3, '0')
        out.write((tag + " " + cmd + "\r\n").toByteArray(Charsets.UTF_8)); out.flush()
        val items = ArrayList<Item>()
        while (true) {
            val it = readItem()
            if (it.text.startsWith("$tag ")) {
                val rest = it.text.substring(tag.length + 1)
                return Reply(items, rest.substringBefore(' ').uppercase(), rest.substringAfter(' ', ""))
            }
            items += it
        }
    }

    private fun ok(r: Reply, what: String): Reply { if (r.status != "OK") throw MailException(what + ": " + r.tail.take(60)); return r }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun login(user: String, pass: String) {
        val r = command("LOGIN " + q(user) + " " + q(pass))
        if (r.status != "OK") throw MailException(if (r.tail.contains("AUTHENTICATIONFAILED", true) || r.tail.contains("invalid", true) || r.tail.contains("credentials", true)) "Wrong password or sign-in refused" else "Sign-in failed: " + r.tail.take(50))
    }

    class Box(val name: String, val attrs: Set<String>)

    fun list(): List<Box> {
        val r = ok(command("LIST \"\" \"*\""), "List")
        val re = Regex("^\\* LIST \\(([^)]*)\\) (?:\"[^\"]*\"|NIL) (?:\"((?:[^\"\\\\]|\\\\.)*)\"|(\\S+))")
        return r.items.mapNotNull { re.find(it.text)?.let { m -> Box((m.groupValues[2].ifEmpty { m.groupValues[3] }).replace("\\\"", "\"").replace("\\\\", "\\"), m.groupValues[1].split(' ').filter { a -> a.isNotEmpty() }.map { a -> a.lowercase() }.toSet()) } }
    }

    fun select(box: String): Int {
        val r = ok(command("SELECT " + q(box)), "Open folder")
        return r.items.firstNotNullOfOrNull { Regex("^\\* (\\d+) EXISTS").find(it.text)?.groupValues?.get(1)?.toInt() } ?: 0
    }

    /** Every message number in the open folder, oldest first. */
    fun uids(): List<Long> {
        val r = ok(command("UID SEARCH ALL"), "Search")
        return r.items.firstOrNull { it.text.startsWith("* SEARCH") }?.text?.removePrefix("* SEARCH")?.trim()?.split(' ')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
    }

    class RawHeader(val uid: Long, val seen: Boolean, val internal: String, val header: String)

    fun headers(uids: List<Long>): List<RawHeader> {
        if (uids.isEmpty()) return emptyList()
        val r = ok(command("UID FETCH " + uids.joinToString(",") + " (UID FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE)])"), "Fetch")
        return r.items.mapNotNull { it ->
            if (!it.text.contains(" FETCH ")) return@mapNotNull null
            val uid = Regex("UID (\\d+)").find(it.text)?.groupValues?.get(1)?.toLong() ?: return@mapNotNull null
            val flags = Regex("FLAGS \\(([^)]*)\\)").find(it.text)?.groupValues?.get(1) ?: ""
            val internal = Regex("INTERNALDATE \"([^\"]*)\"").find(it.text)?.groupValues?.get(1) ?: ""
            RawHeader(uid, flags.contains("\\Seen", true), internal, it.literals.firstOrNull()?.let { b -> String(b, Charsets.ISO_8859_1) } ?: "")
        }
    }

    fun body(uid: Long): ByteArray {
        val r = ok(command("UID FETCH $uid (BODY.PEEK[])"), "Fetch")
        return r.items.firstOrNull { it.literals.isNotEmpty() }?.literals?.first() ?: throw MailException("Message not found")
    }

    fun setSeen(uid: Long, seen: Boolean) { ok(command("UID STORE $uid ${if (seen) "+" else "-"}FLAGS.SILENT (\\Seen)"), "Mark") }

    /** Moves a message to [trash] (a copy, then removal from this folder), or just removes it when there is no trash folder. */
    fun delete(uid: Long, trash: String?) {
        if (trash != null) ok(command("UID COPY $uid " + q(trash)), "Move to trash")
        ok(command("UID STORE $uid +FLAGS.SILENT (\\Deleted)"), "Delete")
        val r = command("UID EXPUNGE $uid")
        if (r.status != "OK") ok(command("EXPUNGE"), "Delete")
    }

    /** Saves a copy of a message in a folder, marked read (used for Sent when the provider does not do it itself). */
    fun append(box: String, message: ByteArray) {
        val tag = "A" + (++n).toString().padStart(3, '0')
        out.write((tag + " APPEND " + q(box) + " (\\Seen) {" + message.size + "}\r\n").toByteArray()); out.flush()
        val cont = readLine(); if (!cont.startsWith("+")) throw MailException("Could not save to $box")
        out.write(message); out.write("\r\n".toByteArray()); out.flush()
        while (true) { val it = readItem(); if (it.text.startsWith("$tag ")) { if (!it.text.substring(tag.length + 1).startsWith("OK")) throw MailException("Could not save to $box"); return } }
    }

    override fun close() { try { if (sock != null) command("LOGOUT") } catch (_: Exception) { }; try { sock?.close() } catch (_: Exception) { } }
}
