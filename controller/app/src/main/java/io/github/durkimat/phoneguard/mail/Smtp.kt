package io.github.durkimat.phoneguard.mail

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Base64

/** Sends one plain-text message over SMTP (implicit TLS on 465 or STARTTLS on 587), signing in with the account's password. */
class SmtpClient(private val host: String, private val port: Int, private val security: String, private val timeoutMs: Int = 20_000) {
    private var sock: Socket? = null
    private lateinit var input: InputStream
    private lateinit var out: OutputStream

    private fun use(s: Socket) { sock = s; input = BufferedInputStream(s.getInputStream()); out = s.getOutputStream() }

    private fun readLine(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read(); if (c < 0) throw MailException("Connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return String(b.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Reads one (possibly multi-line) reply: its code and all its text. */
    private fun reply(): Pair<Int, String> {
        val text = StringBuilder(); var code: Int
        while (true) {
            val l = readLine(); code = l.take(3).toIntOrNull() ?: throw MailException("Bad reply: " + l.take(40))
            text.append(l.drop(4)).append('\n')
            if (l.length < 4 || l[3] != '-') break
        }
        return code to text.toString()
    }

    private fun send(line: String) { out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush() }
    private fun expect(ok: IntRange, what: String): Pair<Int, String> { val r = reply(); if (r.first !in ok) throw MailException(what + ": " + r.second.trim().take(60)); return r }

    fun send(user: String, pass: String, from: String, to: List<String>, message: ByteArray) {
        try {
            use(openSocket(host, port, if (security == Security.SSL) Security.SSL else Security.NONE, timeoutMs))
            expect(220..220, "Server")
            send("EHLO phoneguard"); expect(250..250, "Hello")
            if (security == Security.STARTTLS) {
                send("STARTTLS"); expect(220..220, "Secure connection")
                use(startTls(sock!!, host, port))
                send("EHLO phoneguard"); expect(250..250, "Hello")
            }
            val auth = Base64.getEncoder().encodeToString(("\u0000" + user + "\u0000" + pass).toByteArray(Charsets.UTF_8))
            send("AUTH PLAIN $auth")
            val a = reply(); if (a.first != 235) throw MailException(if (a.first in 530..538) "Wrong password or sign-in refused" else "Sign-in failed: " + a.second.trim().take(50))
            send("MAIL FROM:<$from>"); expect(250..250, "Sender refused")
            for (t in to) { send("RCPT TO:<$t>"); expect(250..251, "Recipient refused") }
            send("DATA"); expect(354..354, "Not accepted")
            // dot-stuff: a line starting with a dot gets another one
            val body = String(message, Charsets.ISO_8859_1).replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
            out.write(body.toByteArray(Charsets.ISO_8859_1)); out.write("\r\n.\r\n".toByteArray()); out.flush()
            expect(250..250, "Not sent")
            try { send("QUIT") } catch (_: Exception) { }
        } finally { try { sock?.close() } catch (_: Exception) { } }
    }

    companion object {
        /** A complete plain-text message: headers, then the text as base64 so any character survives. */
        fun compose(from: String, fromName: String, to: List<String>, subject: String, body: String, date: java.util.Date = java.util.Date(), inReplyTo: String = ""): ByteArray {
            val fmt = java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH)
            val b64 = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8))
            val domain = from.substringAfter('@', "phoneguard")
            val h = StringBuilder()
            h.append("From: ").append(if (fromName.isBlank()) from else Mime.encodeHeader(fromName) + " <" + from + ">").append("\r\n")
            h.append("To: ").append(to.joinToString(", ")).append("\r\n")
            h.append("Subject: ").append(Mime.encodeHeader(subject)).append("\r\n")
            h.append("Date: ").append(fmt.format(date)).append("\r\n")
            h.append("Message-ID: <").append(java.util.UUID.randomUUID()).append('@').append(domain).append(">\r\n")
            if (inReplyTo.isNotBlank()) h.append("In-Reply-To: ").append(inReplyTo).append("\r\n")
            h.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n").append(b64).append("\r\n")
            return h.toString().toByteArray(Charsets.ISO_8859_1)
        }
    }
}
