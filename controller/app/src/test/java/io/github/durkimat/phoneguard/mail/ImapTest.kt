package io.github.durkimat.phoneguard.mail

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A tiny IMAP server in this process, speaking the parts of the protocol the client uses, the way Gmail words it. */
class FakeImap(val user: String, val pass: String) {
    class Msg(val uid: Long, var flags: MutableSet<String>, val internal: String, val raw: String)
    val boxes = linkedMapOf(
        "INBOX" to mutableListOf<Msg>(), "[Gmail]/Sent Mail" to mutableListOf<Msg>(), "[Gmail]/Trash" to mutableListOf<Msg>(),
    )
    val server = ServerSocket(0)
    val port get() = server.localPort
    val log = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val t = Thread {
        try { while (true) { val s = server.accept(); Thread { serve(s) }.start() } } catch (_: Exception) { }
    }.also { it.isDaemon = true; it.start() }

    fun close() { try { server.close() } catch (_: Exception) { } }

    private fun readLine(i: BufferedInputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) { val c = i.read(); if (c < 0) return null; if (c == '\n'.code) break; if (c != '\r'.code) b.write(c) }
        return String(b.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun serve(s: Socket) {
        val i = BufferedInputStream(s.getInputStream()); val o = s.getOutputStream()
        fun w(x: String) { o.write((x + "\r\n").toByteArray(Charsets.ISO_8859_1)); o.flush() }
        w("* OK Fake IMAP ready")
        var authed = false; var sel: String? = null
        while (true) {
            val line = readLine(i) ?: break
            log += line
            val tag = line.substringBefore(' '); val cmd = line.substringAfter(' ')
            val up = cmd.uppercase()
            when {
                up.startsWith("LOGIN") -> {
                    val m = Regex("LOGIN \"((?:[^\"\\\\]|\\\\.)*)\" \"((?:[^\"\\\\]|\\\\.)*)\"").find(cmd)
                    val u = m?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\"); val p = m?.groupValues?.get(2)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                    if (u == user && p == pass) { authed = true; w("$tag OK LOGIN completed") } else w("$tag NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)")
                }
                !authed -> w("$tag BAD login first")
                up.startsWith("LIST") -> {
                    w("* LIST (\\HasNoChildren) \"/\" \"INBOX\""); w("* LIST (\\HasNoChildren \\Sent) \"/\" \"[Gmail]/Sent Mail\""); w("* LIST (\\HasNoChildren \\Trash) \"/\" \"[Gmail]/Trash\"")
                    w("$tag OK Success")
                }
                up.startsWith("SELECT") -> { sel = Regex("SELECT \"([^\"]*)\"").find(cmd)?.groupValues?.get(1); w("* ${boxes[sel]?.size ?: 0} EXISTS"); w("$tag OK [READ-WRITE] selected") }
                up.startsWith("UID SEARCH ALL") -> w("* SEARCH " + boxes[sel]!!.joinToString(" ") { it.uid.toString() }).also { w("$tag OK SEARCH done") }
                up.startsWith("UID FETCH") -> {
                    val m = Regex("UID FETCH ([\\d,]+) \\((.*)\\)$").find(cmd)!!
                    val want = m.groupValues[1].split(',').map { it.toLong() }; val what = m.groupValues[2]
                    for (msg in boxes[sel]!!.filter { it.uid in want }) {
                        val flags = msg.flags.joinToString(" ")
                        val data: String = if (what.contains("HEADER.FIELDS")) msg.raw.substringBefore("\r\n\r\n") + "\r\n\r\n" else msg.raw
                        val name = if (what.contains("HEADER.FIELDS")) "BODY[HEADER.FIELDS (FROM TO SUBJECT DATE)]" else "BODY[]"
                        val pre = if (what.contains("HEADER.FIELDS")) "* 1 FETCH (UID ${msg.uid} FLAGS ($flags) INTERNALDATE \"${msg.internal}\" $name {${data.length}}" else "* 1 FETCH (UID ${msg.uid} $name {${data.length}}"
                        o.write((pre + "\r\n").toByteArray(Charsets.ISO_8859_1)); o.write(data.toByteArray(Charsets.ISO_8859_1)); o.write(")\r\n".toByteArray()); o.flush()
                    }
                    w("$tag OK Success")
                }
                up.startsWith("UID STORE") -> {
                    val m = Regex("UID STORE (\\d+) ([+-])FLAGS\\.SILENT \\((.*)\\)").find(cmd)!!
                    boxes[sel]!!.firstOrNull { it.uid == m.groupValues[1].toLong() }?.let { if (m.groupValues[2] == "+") it.flags += m.groupValues[3] else it.flags -= m.groupValues[3] }
                    w("$tag OK Success")
                }
                up.startsWith("UID COPY") -> {
                    val m = Regex("UID COPY (\\d+) \"([^\"]*)\"").find(cmd)!!
                    val src = boxes[sel]!!.first { it.uid == m.groupValues[1].toLong() }
                    boxes[m.groupValues[2]]!!.add(Msg(src.uid + 1000, mutableSetOf(), src.internal, src.raw)); w("$tag OK [COPYUID] copied")
                }
                up.startsWith("UID EXPUNGE") -> { boxes[sel]!!.removeAll { "\\Deleted" in it.flags }; w("$tag OK expunged") }
                up.startsWith("APPEND") -> {
                    val m = Regex("APPEND \"([^\"]*)\" \\(([^)]*)\\) \\{(\\d+)\\}").find(cmd)!!
                    w("+ go ahead")
                    val n = m.groupValues[3].toInt(); val buf = ByteArray(n); var got = 0
                    while (got < n) got += i.read(buf, got, n - got)
                    readLine(i)
                    boxes[m.groupValues[1]]!!.add(Msg(boxes[m.groupValues[1]]!!.size + 1L, mutableSetOf("\\Seen"), "13-Nov-2026 13:03:11 +0000", String(buf, Charsets.ISO_8859_1)))
                    w("$tag OK APPEND completed")
                }
                up.startsWith("LOGOUT") -> { w("* BYE"); w("$tag OK bye"); break }
                else -> w("$tag BAD unknown")
            }
        }
        s.close()
    }
}

/** A tiny SMTP server that accepts one login and keeps what it was sent. */
class FakeSmtp(val user: String, val pass: String) {
    val server = ServerSocket(0); val port get() = server.localPort
    @Volatile var from = ""; val rcpt = mutableListOf<String>(); @Volatile var data = ""; @Volatile var authOk = false
    private val t = Thread { try { while (true) { val s = server.accept(); Thread { serve(s) }.start() } } catch (_: Exception) { } }.also { it.isDaemon = true; it.start() }
    fun close() { try { server.close() } catch (_: Exception) { } }
    private fun serve(s: Socket) {
        val i = s.getInputStream().bufferedReader(Charsets.ISO_8859_1); val o = s.getOutputStream()
        fun w(x: String) { o.write((x + "\r\n").toByteArray()); o.flush() }
        w("220 fake smtp")
        while (true) {
            val l = i.readLine() ?: break
            when {
                l.startsWith("EHLO") -> { w("250-fake"); w("250 AUTH PLAIN") }
                l.startsWith("AUTH PLAIN") -> {
                    val d = String(java.util.Base64.getDecoder().decode(l.removePrefix("AUTH PLAIN ")), Charsets.UTF_8).split('\u0000')
                    if (d[1] == user && d[2] == pass) { authOk = true; w("235 ok") } else w("535 5.7.8 Username and Password not accepted")
                }
                l.startsWith("MAIL FROM:") -> { from = l.substringAfter('<').substringBefore('>'); w("250 ok") }
                l.startsWith("RCPT TO:") -> { rcpt += l.substringAfter('<').substringBefore('>'); w("250 ok") }
                l == "DATA" -> { w("354 go"); val sb = StringBuilder(); while (true) { val x = i.readLine() ?: break; if (x == ".") break; sb.append(if (x.startsWith("..")) x.drop(1) else x).append("\r\n") }; data = sb.toString(); w("250 queued") }
                l == "QUIT" -> { w("221 bye"); break }
                else -> w("500 what")
            }
        }
        s.close()
    }
}

class ImapTest {
    private val servers = mutableListOf<() -> Unit>()
    @After fun stop() { servers.forEach { it() } }

    private fun raw(from: String, subject: String, body: String, date: String = "Fri, 13 Nov 2026 13:03:11 +0000") =
        "From: $from\r\nTo: me@example.com\r\nSubject: $subject\r\nDate: $date\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n${String(body.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)}\r\n"

    private fun server(): FakeImap {
        val s = FakeImap("me@example.com", "p\"w\\d")
        servers += { s.close() }
        s.boxes["INBOX"]!!.add(FakeImap.Msg(11, mutableSetOf("\\Seen"), "10-Nov-2026 09:00:00 +0000", raw("\"Dad\" <dad@x.org>", "Lift home", "Half five.")))
        s.boxes["INBOX"]!!.add(FakeImap.Msg(12, mutableSetOf(), "13-Nov-2026 13:03:11 +0000", raw("=?UTF-8?Q?Mum?= <mum@x.org>", "=?UTF-8?B?Q2Fmw6kgdG9uaWdodA==?=", "Café at six?")))
        return s
    }
    private fun account(s: FakeImap) = MailAccount("me@example.com", "Me", imapHost = "127.0.0.1", imapPort = s.port, imapSec = Security.NONE, smtpHost = "127.0.0.1", smtpPort = 1, smtpSec = Security.NONE)

    @Test fun listsNewestFirstWithDecodedHeadersAndUnreadFlag() {
        val s = server()
        val h = ImapSmtpProvider(account(s), "p\"w\\d").inbox(20)
        assertEquals(listOf(12L, 11L), h.map { it.uid })
        assertEquals("Mum", h[0].from); assertEquals("mum@x.org", h[0].addr)
        assertEquals("Café tonight", h[0].subject)
        assertTrue(h[0].unread); assertTrue(!h[1].unread)
        assertEquals(1794574991000L, h[0].date)
    }

    @Test fun bodyIsTextAndMarksRead() {
        val s = server()
        val p = ImapSmtpProvider(account(s), "p\"w\\d")
        assertEquals("Café at six?", p.body(false, 12))
        assertTrue("\\Seen" in s.boxes["INBOX"]!!.first { it.uid == 12L }.flags)
    }

    @Test fun wrongPasswordSaysSo() {
        val s = server()
        try { ImapSmtpProvider(account(s), "nope").check(); fail("should not sign in") } catch (e: MailException) { assertTrue(e.message, e.message!!.contains("Wrong password")) }
    }

    @Test fun deleteMovesToTrash() {
        val s = server()
        ImapSmtpProvider(account(s), "p\"w\\d").delete(false, 11)
        assertEquals(listOf(12L), s.boxes["INBOX"]!!.map { it.uid })
        assertEquals(1, s.boxes["[Gmail]/Trash"]!!.size)
    }

    @Test fun markUnread() {
        val s = server()
        ImapSmtpProvider(account(s), "p\"w\\d").setSeen(11, false)
        assertTrue("\\Seen" !in s.boxes["INBOX"]!!.first { it.uid == 11L }.flags)
    }

    @Test fun sentFolderIsFoundByItsFlag() {
        val s = server()
        s.boxes["[Gmail]/Sent Mail"]!!.add(FakeImap.Msg(1, mutableSetOf("\\Seen"), "12-Nov-2026 10:00:00 +0000", raw("me@example.com", "Re: Lift", "ok")))
        assertEquals(listOf("Re: Lift"), ImapSmtpProvider(account(s), "p\"w\\d").sent(10).map { it.subject })
    }

    @Test fun sendsOverSmtpAndKeepsACopyWhenTheServiceDoesNot() {
        val imap = server(); val smtp = FakeSmtp("me@example.com", "p\"w\\d"); servers += { smtp.close() }
        val a = account(imap).copy(smtpPort = smtp.port)
        ImapSmtpProvider(a, "p\"w\\d").send(listOf("mum@x.org"), "Tea é", "See you at six.\n.dot line")
        assertTrue(smtp.authOk)
        assertEquals("me@example.com", smtp.from); assertEquals(listOf("mum@x.org"), smtp.rcpt)
        val msg = Mime.entity(smtp.data)
        assertEquals("Tea é", Mime.decodeWords(msg.headers["subject"]!!))
        assertEquals("See you at six.\n.dot line", Mime.text(smtp.data.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(1, imap.boxes["[Gmail]/Sent Mail"]!!.size) // copied, because this is not a gmail.com server
    }

    @Test fun smtpWrongPassword() {
        val smtp = FakeSmtp("me@example.com", "right"); servers += { smtp.close() }
        val imap = server()
        try { ImapSmtpProvider(account(imap).copy(smtpPort = smtp.port), "wrong").send(listOf("a@b.c"), "x", "y"); fail() } catch (e: MailException) { assertTrue(e.message, e.message!!.contains("Wrong password")) }
    }

    @Test fun presets() {
        assertEquals("imap.gmail.com", Presets.forAddress("Kid@gmail.com")!!.imapHost)
        assertEquals("kid", Presets.forAddress("kid@icloud.com")!!.user)
        assertEquals(587, Presets.forAddress("a@outlook.com")!!.smtpPort)
        assertEquals(null, Presets.forAddress("nonsense"))
        assertEquals("imap.example.org", Presets.guess("a@example.org")!!.imapHost)
    }

    @Test fun demoMailworks() {
        val d = DemoMail(1_000_000_000_000L)
        assertEquals(2, d.inbox(10).count { it.unread })
        d.body(false, 5); assertEquals(1, d.inbox(10).count { it.unread })
        d.send(listOf("a@b.c"), "Hi", "there"); assertEquals(2, d.sent(10).size)
        d.delete(false, 5); assertEquals(4, d.inbox(10).size)
    }
}
