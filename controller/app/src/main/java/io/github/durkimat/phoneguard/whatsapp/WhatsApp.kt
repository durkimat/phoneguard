package io.github.durkimat.phoneguard.whatsapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.launcher.Contact
import io.github.durkimat.phoneguard.launcher.Convo
import wabridge.Bridge
import wabridge.Listener
import wabridge.Wabridge
import java.io.File

/**
 * WhatsApp on the phone itself: the Go bridge (a linked device on the owner's account) plus a small private
 * store of recent messages. Home Assistant is not involved. Only the allowlisted numbers are ever read.
 */
object WhatsApp {
    @Volatile var status = "off"
        private set
    @Volatile var pairCode: String? = null
        private set
    private var bridge: Bridge? = null
    private var store: WaStore? = null
    private val changed = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun onChange(l: () -> Unit) { changed.add(l) }
    fun offChange(l: () -> Unit) { changed.remove(l) }
    private val main = Handler(Looper.getMainLooper())
    private val idleOff = Runnable { bridge?.disconnect(); android.util.Log.i("PhoneGuard", "whatsapp: idle, disconnected") }

    private fun prefs(c: Context) = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** True once an account has been linked in this app (cheap: nothing is opened). */
    fun linked(c: Context): Boolean = prefs(c).getBoolean("wa_linked", false)
    private fun setLinked(c: Context, v: Boolean) { prefs(c).edit().putBoolean("wa_linked", v).apply() }
    private fun fire() { changed.forEach { it() } }

    @Synchronized fun store(c: Context): WaStore = store ?: WaStore(c.applicationContext).also { store = it }

    /** Opens the bridge (does not connect). */
    @Synchronized fun open(c: Context): Bridge? {
        bridge?.let { return it }
        val app = c.applicationContext
        return try {
            val dir = File(app.filesDir, "wa").apply { mkdirs() }
            val st = store(app)
            val b = Wabridge.open(dir.absolutePath, object : Listener {
                override fun onStatus(state: String) {
                    status = state; Log.log(app, "whatsapp: $state")
                    if (state == "paired") setLinked(app, true)
                    if (state == "logged_out") { setLinked(app, false); st.wipe() }
                    fire()
                }
                override fun onPairCode(code: String) { pairCode = code; Log.log(app, "whatsapp: pairing code ready"); fire() }
                override fun onMessage(chat: String, chatName: String, sender: String, text: String, ts: Long, fromMe: Boolean) { st.add(chat, chatName, sender, text, ts, fromMe); fire() }
                override fun onCall(chat: String, chatName: String, ts: Long, rejected: Boolean) {
                    // a call cannot be answered here: it is noted in the chat so nobody wonders who rang
                    st.add(chat, chatName, chatName, if (rejected) "[call declined]" else "[missed call]", ts, false); fire()
                }
            })
            bridge = b
            status = if (b.isPaired) "linked" else "not linked"
            setLinked(app, b.isPaired)
            // who may be read and written to is remembered on the phone (HA will set it later)
            val p = prefs(app)
            b.setAllowed(p.getString("wa_allowed", "") ?: ""); b.setAllowAll(p.getBoolean("wa_all", false))
            b.setRejectCalls(p.getBoolean("wa_reject_calls", false))
            b
        } catch (e: Throwable) {
            Log.log(app, "whatsapp: could not open: ${e.javaClass.simpleName} ${e.message}")
            status = "error"
            null
        }
    }

    /** Closes the bridge so the next use reopens it from the files (used after the session files are replaced). */
    @Synchronized fun close() { bridge?.close(); bridge = null; status = "off" }

    fun isPaired(c: Context): Boolean = open(c)?.isPaired ?: false

    /** [entries]: numbers and "g:Group name" entries, separated by commas or new lines. [all] allows everyone and every group. */
    fun setAllowed(c: Context, entries: String, all: Boolean = false) {
        prefs(c).edit().putString("wa_allowed", entries).putBoolean("wa_all", all).apply()
        val b = open(c)
        b?.setAllowed(entries); b?.setAllowAll(all)
        val parts = entries.split(',', ';', '\n').map { it.trim() }
        val groups = parts.filter { it.startsWith("g:", ignoreCase = true) }.map { it.substring(2).trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val numbers = parts.filterNot { it.startsWith("g:", ignoreCase = true) }.map { n -> n.filter { it.isDigit() } }.filter { it.isNotEmpty() }.toSet()
        store(c).keepOnly(numbers, groups, all)
    }

    /** Decline incoming WhatsApp calls (the mode blocks incoming calls). */
    fun rejectCalls(c: Context, on: Boolean) { prefs(c).edit().putBoolean("wa_reject_calls", on).apply(); open(c)?.setRejectCalls(on) }

    /** The chats as the Nokia screens show them: names from the phone's Contacts where the number matches. */
    fun convos(c: Context, contacts: List<Contact>): List<Convo> {
        val names = HashMap<String, String>()
        for (ct in contacts) for (n in ct.numbers) names.putIfAbsent(n.filter { it.isDigit() }.takeLast(9), ct.name)
        val st = store(c)
        return st.chats().map { row ->
            val msgs = st.messages(row.number)
            val contact = if (row.number.startsWith("g")) null else names[row.number.takeLast(9)]
            Convo(row.number, contact ?: row.name, msgs, row.unread)
        }
    }

    /**
     * Brings the connection up when the owner looks at WhatsApp, and lets it go again a few minutes after the last
     * look: nothing runs in the background, so the battery is left alone. Messages that arrived meanwhile are queued
     * by WhatsApp and delivered at once on reconnect (the spike caught up two hours of messages instantly).
     */
    fun touch(c: Context) {
        main.removeCallbacks(idleOff); main.postDelayed(idleOff, IDLE_MS)
        if (isConnected()) return
        status = "connecting"; fire()
        Thread { connect(c) }.start()
    }

    private const val IDLE_MS = 3 * 60_000L

    /** Sends a message, connecting first if needed. Returns "sent" or the reason it was not. */
    fun sendNow(c: Context, chat: String, text: String): String {
        main.removeCallbacks(idleOff); main.postDelayed(idleOff, IDLE_MS)
        if (!isConnected()) { connect(c); for (i in 1..20) { if (isConnected()) break; Thread.sleep(500) } }
        if (!isConnected()) return "not connected"
        val r = send(c, chat, text)
        // WhatsApp does not echo a message back to the device that sent it, so keep our own copy
        if (r == "sent") { store(c).add(chat, "", "Me", text.trim(), System.currentTimeMillis() / 1000, true); fire() }
        return r
    }

    fun pair(c: Context, number: String): String = try { open(c)?.pairWithPhone(number); "pairing started" } catch (e: Throwable) { "error: ${e.message}" }
    fun connect(c: Context): String = try { open(c)?.connect(); "ok" } catch (e: Throwable) { "error: ${e.message}" }
    fun disconnect() { bridge?.disconnect() }
    fun isConnected(): Boolean = bridge?.isConnected ?: false
    fun send(c: Context, chat: String, text: String): String = try { open(c)?.send(chat, text); "sent" } catch (e: Throwable) { e.message ?: "error" }
    fun logout(c: Context): String = try { open(c)?.logout(); store(c).wipe(); "logged out" } catch (e: Throwable) { "error: ${e.message}" }
}
