package io.github.durkimat.phoneguard.whatsapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.core.Config
import io.github.durkimat.phoneguard.core.Effective
import io.github.durkimat.phoneguard.core.Engine
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
    // never drop the connection while an account is being linked: it is the one waiting for the code to be typed in
    private val idleOff = Runnable {
        if (bridge?.isPaired == false) return@Runnable
        bridge?.disconnect(); android.util.Log.i("PhoneGuard", "whatsapp: idle, disconnected")
    }

    private fun prefs(c: Context) = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** True once an account has been linked in this app (cheap: nothing is opened). */
    fun linked(c: Context): Boolean = prefs(c).getBoolean("wa_linked", false)
    private fun setLinked(c: Context, v: Boolean) { prefs(c).edit().putBoolean("wa_linked", v).apply() }
    // WhatsApp events can come in hundreds (the history sent when linking): merge them so the screen redraws at most
    // a few times a second instead of once per message
    // live events redraw within 300 ms; the one-off history batch within 2 s (it does not need to be dynamic), and a
    // redraw is never held back for more than 5 s however long the batch runs
    private var pendingSince = 0L
    private val fireNow = Runnable { pendingSince = 0L; changed.forEach { it() } }
    private fun fire(slow: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (pendingSince == 0L) pendingSince = now
        main.removeCallbacks(fireNow)
        val wait = if (slow) 2_000L else 300L
        main.postDelayed(fireNow, minOf(wait, maxOf(0L, pendingSince + 5_000L - now)))
    }

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
                    if (state == "paired") { setLinked(app, true); pairCode = null }
                    if (state == "logged_out") { setLinked(app, false); st.wipe() }
                    fire()
                }
                override fun onPairCode(code: String) { pairCode = code; Log.log(app, "whatsapp: pairing code ready"); fire() }
                override fun onMessage(chat: String, chatName: String, sender: String, text: String, ts: Long, fromMe: Boolean, history: Boolean) { st.add(chat, chatName, nameFor(app, sender), text, ts, fromMe, history = history); fire(slow = history) }
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

    private var nameCache: Map<String, String> = emptyMap()
    private var nameCacheAt = 0L

    /** A sender shown as a bare number becomes the contact's name from the phone's Contacts when there is one. */
    private fun nameFor(c: Context, sender: String): String {
        if (sender.length < 7 || !sender.all { it.isDigit() }) return sender
        val now = System.currentTimeMillis()
        if (now - nameCacheAt > 5 * 60_000L) {
            val m = HashMap<String, String>()
            for (ct in io.github.durkimat.phoneguard.launcher.LauncherData.contacts(c)) for (n in ct.numbers) m.putIfAbsent(n.filter { it.isDigit() }.takeLast(9), ct.name)
            nameCache = m; nameCacheAt = now
        }
        return nameCache[sender.takeLast(9)] ?: sender
    }

    fun isPaired(c: Context): Boolean = open(c)?.isPaired ?: false

    /** [entries]: numbers and "g:Group name" entries, separated by commas or new lines. [all] allows everyone and every group. */
    fun setAllowed(c: Context, entries: String, all: Boolean = false) {
        if (prefs(c).getString("wa_allowed", null) == entries && prefs(c).getBoolean("wa_all", false) == all && bridge != null) return
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
        if (!linked(c)) return // nothing to connect to until an account is linked
        if (policy(c) == "off") { status = "off"; fire(); return } // the mode says no WhatsApp at all
        main.removeCallbacks(idleOff); main.postDelayed(idleOff, IDLE_MS)
        if (isConnected()) return
        status = "connecting"; fire()
        Thread { connect(c) }.start()
    }

    private const val IDLE_MS = 3 * 60_000L

    /** The WhatsApp check setting of the mode in force: "off", "open", "30" or "60". */
    fun policy(c: Context): String = Engine.effective(Store.config(c), Store.boost(c), Clock.now()).wa

    /**
     * Called whenever the controller re-evaluates: applies who may be read, the send limits, whether WhatsApp calls are
     * declined (when the mode blocks incoming calls) and arms or cancels the background check.
     */
    fun apply(c: Context, cfg: Config?, eff: Effective) {
        if (!linked(c)) { cancelAlarm(c); return }
        cfg?.whatsapp?.let { w ->
            setAllowed(c, w.allowed.joinToString("\n"), w.all)
            open(c)?.setLimits(w.perMinute.toLong(), w.perDay.toLong())
        }
        rejectCalls(c, !eff.inbound)
        Thread { try { store(c).pruneChats() } catch (_: Exception) { } }.start()
        when (eff.wa) {
            "off" -> { cancelAlarm(c); main.removeCallbacks(idleOff); bridge?.disconnect() }
            "30" -> schedule(c, 30)
            "60" -> schedule(c, 60)
            else -> cancelAlarm(c) // "open": only when WhatsApp is opened
        }
    }

    private fun pending(c: Context) = android.app.PendingIntent.getBroadcast(
        c, 21, android.content.Intent(c, WaAlarmReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    private fun schedule(c: Context, minutes: Int) {
        val am = c.getSystemService(android.app.AlarmManager::class.java)
        am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pending(c))
    }

    private fun cancelAlarm(c: Context) { c.getSystemService(android.app.AlarmManager::class.java).cancel(pending(c)) }

    private val polling = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One background check: connect, let WhatsApp deliver what it has queued, disconnect, and arm the next one. */
    fun poll(c: Context) {
        val app = c.applicationContext
        val p = policy(app)
        if (!linked(app) || (p != "30" && p != "60")) return
        if (!polling.compareAndSet(false, true)) return
        try {
            val wasOn = isConnected()
            if (!wasOn) { connect(app); for (i in 1..40) { if (isConnected()) break; Thread.sleep(500) } }
            if (isConnected()) Thread.sleep(if (wasOn) 0 else SETTLE_MS) // queued messages arrive straight after connecting
            if (!wasOn) bridge?.disconnect()
            val bat = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val pct = bat?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) } ?: -1
            val plugged = (bat?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            Log.log(app, "whatsapp: checked (${if (wasOn) "already connected" else "connected briefly"}) battery=$pct% ${if (plugged) "charging" else "on battery"}")
        } finally {
            polling.set(false)
            schedule(app, if (p == "60") 60 else 30)
        }
    }

    private const val SETTLE_MS = 10_000L

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

    fun pair(c: Context, number: String): String = try {
        pairCode = null // never show the code from an earlier attempt
        main.removeCallbacks(idleOff)
        fire()
        open(c)?.pairWithPhone(number); "pairing started"
    } catch (e: Throwable) { "error: ${e.message}" }
    fun connect(c: Context): String = try { open(c)?.connect(); "ok" } catch (e: Throwable) { "error: ${e.message}" }
    fun disconnect() { bridge?.disconnect() }
    fun isConnected(): Boolean = bridge?.isConnected ?: false
    fun send(c: Context, chat: String, text: String): String = try { open(c)?.send(chat, text); "sent" } catch (e: Throwable) { e.message ?: "error" }
    /** Unlinks the account: the bridge connects if it has to, then everything stored is wiped and the bridge is reset so a new link starts clean. */
    @Synchronized fun logout(c: Context): String = try {
        open(c)?.logout()
        store(c).wipe()
        setLinked(c, false)
        pairCode = null
        close() // the old client is finished; the next open() starts a fresh, unlinked one
        status = "not linked"; fire()
        "logged out"
    } catch (e: Throwable) {
        Log.log(c, "whatsapp: unlink failed: ${e.message}")
        "error: ${e.message}"
    }
}
