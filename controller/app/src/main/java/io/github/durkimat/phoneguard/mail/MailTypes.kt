package io.github.durkimat.phoneguard.mail

/** One line of a mailbox. [from] is the sender's name (or address), [addr] the address, [unread] true until it has been opened. */
data class MailHeader(val uid: Long, val from: String, val addr: String, val to: String, val subject: String, val date: Long, val unread: Boolean)

/** Where an account's mail lives. [user] signs in to IMAP, [smtpUser] to SMTP (usually the same). [security] is [Security]. */
data class MailAccount(
    val address: String, val name: String = "", val user: String = address, val smtpUser: String = address,
    val imapHost: String, val imapPort: Int = 993, val imapSec: String = Security.SSL,
    val smtpHost: String, val smtpPort: Int = 465, val smtpSec: String = Security.SSL,
    /** A note shown when sign-in fails for a provider that does not take plain passwords. */
    val hint: String = "",
)

/** Server settings for the common providers, so a person only types an address and a password. */
object Presets {
    fun forAddress(address: String): MailAccount? {
        val a = address.trim().lowercase()
        val at = a.indexOf('@'); if (at < 1 || at == a.length - 1) return null
        val dom = a.substring(at + 1)
        return when {
            dom == "gmail.com" || dom == "googlemail.com" -> MailAccount(a, imapHost = "imap.gmail.com", smtpHost = "smtp.gmail.com", hint = "Gmail needs 2-step verification and an app password")
            dom in setOf("outlook.com", "hotmail.com", "hotmail.co.uk", "live.com", "live.co.uk", "msn.com") ->
                MailAccount(a, imapHost = "outlook.office365.com", smtpHost = "smtp-mail.outlook.com", smtpPort = 587, smtpSec = Security.STARTTLS, hint = "Outlook may refuse passwords; it needs sign-in through Microsoft")
            dom in setOf("icloud.com", "me.com", "mac.com") -> MailAccount(a, user = a.substring(0, at), imapHost = "imap.mail.me.com", smtpHost = "smtp.mail.me.com", smtpPort = 587, smtpSec = Security.STARTTLS, hint = "iCloud needs an app-specific password")
            dom in setOf("yahoo.com", "yahoo.co.uk", "ymail.com", "rocketmail.com") -> MailAccount(a, imapHost = "imap.mail.yahoo.com", smtpHost = "smtp.mail.yahoo.com", hint = "Yahoo needs an app password")
            dom == "aol.com" -> MailAccount(a, imapHost = "imap.aol.com", smtpHost = "smtp.aol.com", hint = "AOL needs an app password")
            dom == "fastmail.com" || dom == "fastmail.fm" -> MailAccount(a, imapHost = "imap.fastmail.com", smtpHost = "smtp.fastmail.com", hint = "Fastmail needs an app password")
            else -> null
        }
    }
    /** A guess for any other address (imap.example.com and smtp.example.com); the person can correct it. */
    fun guess(address: String): MailAccount? {
        val a = address.trim().lowercase(); val at = a.indexOf('@'); if (at < 1 || at == a.length - 1) return null
        val dom = a.substring(at + 1)
        return MailAccount(a, imapHost = "imap.$dom", smtpHost = "smtp.$dom")
    }
}

/** Where mail comes from and goes to. The launcher never knows which service is behind it. */
interface MailProvider {
    /** Signs in and out again, to prove the account works. Throws [MailException] with a short reason when it does not. */
    fun check()
    /** The newest [limit] messages, newest first. */
    fun inbox(limit: Int): List<MailHeader>
    fun sent(limit: Int): List<MailHeader>
    /** The readable text of a message; opening it also marks it read. */
    fun body(sent: Boolean, uid: Long): String
    fun setSeen(uid: Long, seen: Boolean)
    fun delete(sent: Boolean, uid: Long)
    fun send(to: List<String>, subject: String, body: String, inReplyTo: String = "")
}

/** The IMAP and SMTP provider: works with any ordinary mail service. */
class ImapSmtpProvider(private val a: MailAccount, private val password: String) : MailProvider {
    private inline fun <T> imap(block: (ImapClient) -> T): T {
        val c = ImapClient(a.imapHost, a.imapPort, a.imapSec)
        try { c.connect(); c.login(a.user, password); return block(c) }
        catch (e: MailException) { throw e }
        catch (e: javax.net.ssl.SSLException) { throw MailException("Secure connection failed") }
        catch (e: java.net.UnknownHostException) { throw MailException("No connection") }
        catch (e: java.net.SocketTimeoutException) { throw MailException("Server too slow") }
        catch (e: java.io.IOException) { throw MailException("No connection") }
        finally { c.close() }
    }

    private fun folders(c: ImapClient): Pair<String?, String?> {
        val boxes = c.list()
        val sent = boxes.firstOrNull { "\\sent" in it.attrs }?.name ?: boxes.firstOrNull { Regex("(?i)(^|[/.])sent( items| mail| messages)?$").containsMatchIn(it.name) }?.name
        val trash = boxes.firstOrNull { "\\trash" in it.attrs }?.name ?: boxes.firstOrNull { Regex("(?i)(^|[/.])(trash|deleted( items| messages)?|bin)$").containsMatchIn(it.name) }?.name
        return sent to trash
    }

    override fun check() { imap { } }

    private fun list(box: (ImapClient) -> String?, limit: Int): List<MailHeader> = imap { c ->
        val name = box(c) ?: return@imap emptyList()
        c.select(name)
        val newest = c.uids().takeLast(limit)
        c.headers(newest).map { toHeader(it) }.sortedByDescending { it.uid }
    }

    override fun inbox(limit: Int) = list({ "INBOX" }, limit)
    override fun sent(limit: Int) = list({ folders(it).first }, limit)

    override fun body(sent: Boolean, uid: Long): String = imap { c ->
        val name = if (sent) (folders(c).first ?: throw MailException("No Sent folder")) else "INBOX"
        c.select(name)
        val text = Mime.text(c.body(uid))
        if (!sent) try { c.setSeen(uid, true) } catch (_: MailException) { }
        text
    }

    override fun setSeen(uid: Long, seen: Boolean) { imap { c -> c.select("INBOX"); c.setSeen(uid, seen) } }

    override fun delete(sent: Boolean, uid: Long) { imap { c ->
        val (s, t) = folders(c)
        c.select(if (sent) (s ?: throw MailException("No Sent folder")) else "INBOX")
        c.delete(uid, t)
    } }

    override fun send(to: List<String>, subject: String, body: String, inReplyTo: String) {
        val msg = SmtpClient.compose(a.address, a.name, to, subject, body, inReplyTo = inReplyTo)
        try { SmtpClient(a.smtpHost, a.smtpPort, a.smtpSec).send(a.smtpUser, password, a.address, to, msg) }
        catch (e: MailException) { throw e }
        catch (e: javax.net.ssl.SSLException) { throw MailException("Secure connection failed") }
        catch (e: java.net.UnknownHostException) { throw MailException("No connection") }
        catch (e: java.io.IOException) { throw MailException("No connection") }
        // Gmail files what it sends by itself; other services need a copy put in Sent
        if (!a.smtpHost.endsWith("gmail.com")) try { imap { c -> folders(c).first?.let { c.append(it, msg) } } } catch (_: Exception) { }
    }

    companion object {
        fun toHeader(r: ImapClient.RawHeader): MailHeader {
            val h = Mime.entity(r.header + "\r\n\r\n").headers
            val (name, addr) = Mime.address(h["from"] ?: "")
            val date = Mime.date(r.internal).takeIf { it > 0 } ?: Mime.date(h["date"])
            return MailHeader(r.uid, name, addr, Mime.decodeWords(h["to"] ?: ""), Mime.decodeWords(h["subject"] ?: "").ifBlank { "(no subject)" }, date, !r.seen)
        }
    }
}

/** A mailbox with made-up mail, for trying the screens (an account whose address ends in @demo.local) and for tests. */
class DemoMail(private val now: Long = System.currentTimeMillis()) : MailProvider {
    private data class M(val h: MailHeader, val body: String)
    private val inboxList = mutableListOf(
        M(MailHeader(5, "Mum", "mum@example.com", "demo@demo.local", "Tea tonight?", now - 20 * 60_000, true), "Hi love,\n\nAre you coming for tea tonight? Nan is making her pie.\n\nLet me know by five.\n\nMum x"),
        M(MailHeader(4, "School office", "office@school.example", "demo@demo.local", "Parents evening: Wednesday", now - 3 * 3_600_000, true), "Dear parent,\n\nParents evening is on Wednesday 18 November from 4pm to 7pm in the main hall. Appointments are ten minutes long.\n\nPlease bring the reading folder.\n\nKind regards,\nThe school office"),
        M(MailHeader(3, "Sam Patel", "sam@example.com", "demo@demo.local", "Football on Saturday", now - 26 * 3_600_000, false), "Are you in for football on Saturday? 10am at the park. Bring shin pads!"),
        M(MailHeader(2, "Dad", "dad@example.com", "demo@demo.local", "Re: Lift home", now - 3 * 86_400_000L, false), "I can pick you up at half five. Wait by the front gate."),
        M(MailHeader(1, "Café Nero", "news@cafe.example", "demo@demo.local", "Your free drink is waiting", now - 9 * 86_400_000L, false), "Hello!\n\nWe've missed you. Come in this week for a free drink.\n\n[1 attachment not shown]"),
    )
    private val sentList = mutableListOf(M(MailHeader(1, "Mum", "mum@example.com", "mum@example.com", "Re: Tea tonight?", now - 40 * 86_400_000L, false), "Yes please! See you at six."))
    private var next = 6L
    override fun check() {}
    override fun inbox(limit: Int) = inboxList.map { it.h }.sortedByDescending { it.uid }.take(limit)
    override fun sent(limit: Int) = sentList.map { it.h }.sortedByDescending { it.uid }.take(limit)
    override fun body(sent: Boolean, uid: Long): String {
        val list = if (sent) sentList else inboxList
        val i = list.indexOfFirst { it.h.uid == uid }; if (i < 0) throw MailException("Message not found")
        if (!sent) list[i] = list[i].copy(h = list[i].h.copy(unread = false))
        return list[i].body
    }
    override fun setSeen(uid: Long, seen: Boolean) { val i = inboxList.indexOfFirst { it.h.uid == uid }; if (i >= 0) inboxList[i] = inboxList[i].copy(h = inboxList[i].h.copy(unread = !seen)) }
    override fun delete(sent: Boolean, uid: Long) { (if (sent) sentList else inboxList).removeAll { it.h.uid == uid } }
    override fun send(to: List<String>, subject: String, body: String, inReplyTo: String) {
        sentList += M(MailHeader(next++, to.first(), to.first(), to.joinToString(", "), subject, System.currentTimeMillis(), false), body)
    }
}
