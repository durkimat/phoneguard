package io.github.durkimat.phoneguard.calendar

import android.content.Context
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.mail.Vault
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** One read-only calendar the parent has given the phone: an https .ics link. The link is kept sealed in the phone's key store, and never logged. */
data class Sub(val id: String, val name: String, val sealedUrl: String, val fetched: Long, val events: Int, val error: String)

/** The subscriptions and the last copy of each feed, kept in the app's private storage. */
class SubStore(private val dir: File) {
    init { dir.mkdirs() }
    private val index get() = File(dir, "subs.json")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "x" + id.hashCode().toUInt().toString(16) }.take(48)
    fun feedFile(id: String) = File(dir, "feed-${safe(id)}.ics")

    fun subs(): List<Sub> = try {
        val a = JSONArray(index.takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).map { val j = a.getJSONObject(it); Sub(j.getString("id"), j.getString("name"), j.getString("url"), j.optLong("fetched"), j.optInt("events"), j.optString("error")) }
    } catch (_: Exception) { emptyList() }

    fun save(list: List<Sub>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.sealedUrl).put("fetched", it.fetched).put("events", it.events).put("error", it.error)) }
        val t = File(dir, "subs.json.tmp"); t.writeText(a.toString()); t.renameTo(index)
    }

    fun forgetFeed(id: String) { feedFile(id).delete() }
}

/** Keeps the phone's subscriptions in line with the parent's list and fetches the feeds (about hourly, at the phone's normal check-in). */
object Subscriptions {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val EVERY_MS = 55 * 60_000L
    const val MAX_SUBS = 8
    @Volatile private var cache = HashMap<String, Pair<Long, List<Ics.Ev>>>()

    fun store(c: Context) = SubStore(File(c.filesDir, "ics"))

    /**
     * Makes the list the parent's: [wanted] holds (id, name, link) where the link is null when it could not be opened this time (the copy already
     * kept stays). Calendars that are no longer wanted are removed with their data. A new or changed link is fetched at the next [refresh].
     */
    @Synchronized
    fun apply(c: Context, wanted: List<Triple<String, String, String?>>) {
        val s = store(c); val old = s.subs().associateBy { it.id }
        val next = ArrayList<Sub>()
        for ((id, name, url) in wanted.take(MAX_SUBS)) {
            val prev = old[id]
            val sealed = if (url != null) Vault.seal(url) else prev?.sealedUrl ?: continue
            val changed = url != null && prev?.let { Vault.open(it.sealedUrl) } != url
            next += Sub(id, name, sealed, if (changed || prev == null) 0 else prev.fetched, if (changed || prev == null) 0 else prev.events, if (changed || prev == null) "" else prev.error)
        }
        for (gone in old.keys - next.map { it.id }.toSet()) s.forgetFeed(gone)
        s.save(next)
    }

    /** Removes everything the parent provisioned (the phone was disconnected or the parent cleared the list). */
    @Synchronized
    fun clear(c: Context) { apply(c, emptyList()) }

    /** Fetches each feed that is due (or all, with [force]). Needs a network; call it where the phone's radio is already awake. */
    @Synchronized
    fun refresh(c: Context, force: Boolean = false) {
        val s = store(c); val now = System.currentTimeMillis()
        val subs = s.subs()
        if (subs.none { force || it.fetched == 0L || now - it.fetched >= EVERY_MS || now < it.fetched }) return
        val out = ArrayList<Sub>()
        for (sub in subs) {
            if (!(force || sub.fetched == 0L || now - sub.fetched >= EVERY_MS || now < sub.fetched)) { out += sub; continue }
            val url = Vault.open(sub.sealedUrl)
            if (url == null) { out += sub.copy(error = "Link unreadable"); continue }
            out += try {
                val text = fetch(url)
                s.feedFile(sub.id).writeText(text)
                val n = Ics.parse(text, ZoneId.systemDefault()).size
                sub.copy(fetched = now, events = n, error = "")
            } catch (e: FetchException) {
                Log.log(c, "calendar ${sub.name}: ${e.message}")
                sub.copy(fetched = now, error = e.message ?: "Failed") // a failed try also waits an hour; the last good copy stays
            } catch (e: Exception) {
                Log.log(c, "calendar ${sub.name}: ${e.javaClass.simpleName}")
                sub.copy(fetched = now, error = "Could not reach it")
            }
        }
        s.save(out)
    }

    /** For the sync state: one entry per calendar. */
    fun status(c: Context): JSONArray {
        val a = JSONArray()
        for (s in store(c).subs()) {
            val o = JSONObject().put("id", s.id).put("ok", s.fetched > 0 && s.error.isEmpty()).put("events", s.events)
            if (s.error.isNotEmpty()) o.put("error", s.error)
            a.put(o)
        }
        return a
    }

    class FetchException(m: String) : Exception(m)

    /** Never logs or reports the link itself, and refuses anything that is not https. */
    private fun fetch(url: String): String {
        val u = URL(url)
        if (u.protocol != "https") throw FetchException("Not an https link")
        val h = u.openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 15_000; h.readTimeout = 25_000; h.instanceFollowRedirects = true
            h.setRequestProperty("User-Agent", "PhoneGuard"); h.setRequestProperty("Accept", "text/calendar, */*")
            val code = h.responseCode
            if (code != 200) throw FetchException("Server said $code")
            if (h.contentLengthLong > MAX_BYTES) throw FetchException("Calendar too big")
            val buf = java.io.ByteArrayOutputStream()
            h.inputStream.use { i -> val b = ByteArray(8192); while (true) { val n = i.read(b); if (n < 0) break; buf.write(b, 0, n); if (buf.size() > MAX_BYTES) throw FetchException("Calendar too big") } }
            val text = buf.toString(Charsets.UTF_8.name())
            if (!Ics.looksLikeCalendar(text)) throw FetchException("Not a calendar")
            return text
        } finally { h.disconnect() }
    }

    /** The parsed feed, kept in memory until the file changes. */
    internal fun events(c: Context, sub: Sub): List<Ics.Ev> {
        val f = store(c).feedFile(sub.id)
        if (!f.exists()) return emptyList()
        val stamp = f.lastModified() + f.length()
        cache[sub.id]?.let { if (it.first == stamp) return it.second }
        val parsed = try { Ics.parse(f.readText(), ZoneId.systemDefault()) } catch (_: Exception) { emptyList() }
        cache = HashMap(cache).also { it[sub.id] = stamp to parsed }
        return parsed
    }

    fun calId(id: String): Long = -(1L + (id.hashCode().toLong() and 0x3fffffffL))
}

/** The parent's read-only calendars, as a [CalendarProvider]. */
class IcsCalendar(private val c: Context) : CalendarProvider {
    override fun calendars(): List<CalInfo> = Subscriptions.store(c).subs().map { CalInfo(Subscriptions.calId(it.id), it.name, "Family", false) }
    override fun events(from: Long, to: Long): List<CalEvent> {
        val out = ArrayList<CalEvent>()
        for (s in Subscriptions.store(c).subs()) out += Ics.expand(Subscriptions.events(c, s), from, to, Subscriptions.calId(s.id))
        return out
    }
    override fun save(e: CalEvent): Long = throw UnsupportedOperationException("read only")
    override fun delete(id: Long) { throw UnsupportedOperationException("read only") }
}

/** What the launcher shows: the phone's own calendar (and whatever syncs into it) together with the parent's read-only ones. */
class CompositeCalendar(c: Context) : CalendarProvider {
    private val android = AndroidCalendar(c)
    private val ics = IcsCalendar(c)
    override fun calendars(): List<CalInfo> = android.calendars() + safe { ics.calendars() }
    override fun events(from: Long, to: Long): List<CalEvent> = (android.events(from, to) + safe { ics.events(from, to) }).sortedBy { it.start }
    override fun save(e: CalEvent): Long = if (e.calId < 0) throw UnsupportedOperationException("read only") else android.save(e)
    override fun delete(id: Long) { if (id >= 0) android.delete(id) }
    fun ensureWritable(): Long = android.ensureWritable()
    private inline fun <T> safe(block: () -> List<T>): List<T> = try { block() } catch (_: Exception) { emptyList() }
}
