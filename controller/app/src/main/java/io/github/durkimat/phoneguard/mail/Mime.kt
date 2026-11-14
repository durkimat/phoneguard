package io.github.durkimat.phoneguard.mail

import java.nio.charset.Charset
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Reads an e-mail message down to the plain text a small screen can show: headers unfolded and decoded, multipart bodies walked, quoted-printable
 * and base64 undone, HTML turned into text, attachments skipped (counted, not shown). No images are ever fetched.
 */
object Mime {
    /** A message or one part of one: lowercase header names, and the body as raw text (one character per byte). */
    class Entity(val headers: Map<String, String>, val body: String)

    private fun latin(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    fun entity(raw: String): Entity {
        val m = Regex("\r?\n\r?\n").find(raw)
        val head = if (m == null) raw else raw.substring(0, m.range.first)
        val body = if (m == null) "" else raw.substring(m.range.last + 1)
        val headers = LinkedHashMap<String, String>()
        var key: String? = null
        for (line in head.split(Regex("\r?\n"))) {
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && key != null) headers[key] = headers[key] + " " + line.trim()
            else { val i = line.indexOf(':'); if (i > 0) { key = line.substring(0, i).trim().lowercase(); if (key !in headers) headers[key] = line.substring(i + 1).trim() else key = "\u0000" } }
        }
        headers.remove("\u0000")
        return Entity(headers, body)
    }

    // ---- headers

    /** RFC 2047 encoded words ("=?UTF-8?B?...?=") turned into text. */
    fun decodeWords(s: String): String {
        val re = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
        val joined = s.replace(Regex("(=\\?[^?]+\\?[bBqQ]\\?[^?]*\\?=)\\s+(?==\\?)"), "$1")
        return re.replace(joined) { m ->
            val cs = charset(m.groupValues[1])
            try {
                if (m.groupValues[2].equals("b", true)) String(Base64.getMimeDecoder().decode(m.groupValues[3]), cs)
                else String(qp(m.groupValues[3].replace('_', ' '), header = true), cs)
            } catch (_: Exception) { m.value }
        }
    }

    private fun charset(name: String?): Charset = try { Charset.forName((name ?: "utf-8").trim().trim('"')) } catch (_: Exception) { Charsets.UTF_8 }

    /** "Name <a@b.c>" into (Name, a@b.c); with no name the name is the address. */
    fun address(field: String): Pair<String, String> {
        val f = decodeWords(field).trim()
        val m = Regex("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>").find(f)
        if (m != null) { val n = m.groupValues[1].trim(); return (if (n.isEmpty()) m.groupValues[2] else n) to m.groupValues[2].trim() }
        val plain = f.trim().trim('<', '>')
        return plain to plain
    }

    private val internalFmt = java.time.format.DateTimeFormatter.ofPattern("d-MMM-uuuu HH:mm:ss Z", Locale.ENGLISH)
    /** An IMAP INTERNALDATE ("13-Nov-2026 14:23:11 +0000") or a Date header, as epoch milliseconds (0 if unreadable). */
    fun date(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val t = s.trim().replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
        return try { OffsetDateTime.parse(t, internalFmt).toInstant().toEpochMilli() } catch (_: Exception) {
            try { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) { 0 }
        }
    }

    // ---- bodies

    fun qp(s: String, header: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 1 < s.length && (s[i + 1] == '\r' || s[i + 1] == '\n')) { i += if (s[i + 1] == '\r' && i + 2 < s.length && s[i + 2] == '\n') 3 else 2; continue }
            if (c == '=' && i + 2 < s.length && s[i + 1].isHex() && s[i + 2].isHex()) { out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3; continue }
            out.write(c.code and 0xFF); i++
        }
        return out.toByteArray()
    }
    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun param(value: String, name: String): String? =
        Regex("(?i)(?:^|[;\\s])" + name + "\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))").find(value)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    private class Found { val plain = StringBuilder(); val html = StringBuilder(); var attachments = 0 }

    private fun decode(e: Entity): String {
        val enc = (e.headers["content-transfer-encoding"] ?: "7bit").trim().lowercase()
        val cs = charset(param(e.headers["content-type"] ?: "", "charset"))
        val bytes = when (enc) {
            "base64" -> try { Base64.getMimeDecoder().decode(e.body.trim()) } catch (_: Exception) { e.body.toByteArray(Charsets.ISO_8859_1) }
            "quoted-printable" -> qp(e.body)
            else -> e.body.toByteArray(Charsets.ISO_8859_1)
        }
        return String(bytes, cs)
    }

    private fun walk(e: Entity, f: Found, depth: Int = 0) {
        if (depth > 8) return
        val ct = e.headers["content-type"] ?: "text/plain"
        val mime = ct.substringBefore(';').trim().lowercase()
        val disp = (e.headers["content-disposition"] ?: "").lowercase()
        if (mime.startsWith("multipart/")) {
            val boundary = param(ct, "boundary") ?: return
            val marker = "--$boundary"
            val parts = e.body.split(marker).drop(1).filter { !it.startsWith("--") }
            for (p in parts) walk(entity(p.removePrefix("\r\n").removePrefix("\n")), f, depth + 1)
        } else if (disp.startsWith("attachment")) f.attachments++
        else when (mime) {
            "text/plain" -> { if (f.plain.isNotEmpty()) f.plain.append("\n\n"); f.plain.append(decode(e).trim('\r', '\n')) }
            "text/html" -> { if (f.html.isNotEmpty()) f.html.append("\n\n"); f.html.append(decode(e)) }
            "message/rfc822", "text/calendar" -> {}
            else -> f.attachments++
        }
    }

    /** The readable text of a whole message (raw bytes as the server sent them). */
    fun text(raw: ByteArray): String {
        val f = Found()
        walk(entity(latin(raw)), f)
        var t = f.plain.toString().ifBlank { htmlToText(f.html.toString()) }.replace("\r\n", "\n").replace('\r', '\n').trim()
        t = t.replace(Regex("\n{3,}"), "\n\n")
        if (f.attachments > 0) t += (if (t.isEmpty()) "" else "\n\n") + "[" + f.attachments + (if (f.attachments == 1) " attachment" else " attachments") + " not shown]"
        return t
    }

    fun htmlToText(h: String): String {
        if (h.isBlank()) return ""
        var s = h.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6]|table|blockquote)>"), "\n").replace(Regex("(?i)<li[^>]*>"), "- ")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        return s.lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]+"), " ") }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** A header value safe to send: plain text as it is, anything else as one RFC 2047 word. */
    fun encodeHeader(s: String): String =
        if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="
}
