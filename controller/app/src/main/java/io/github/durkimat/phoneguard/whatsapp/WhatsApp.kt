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
import io.github.durkimat.wacore.WaListener
import io.github.durkimat.wacore.WaMessage
import io.github.durkimat.wacore.WaStore
import io.github.durkimat.wacore.WaTransport
import io.github.durkimat.wacore.WhatsmeowTransport
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
    private var bridge: WaTransport? = null
    @Volatile private var appCtx: Context? = null
    private var lastTone = 0L
    private var store: WaStore? = null
    private val changed = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun onChange(l: () -> Unit) { changed.add(l) }
    fun offChange(l: () -> Unit) { changed.remove(l) }
    private val main = Handler(Looper.getMainLooper())
    // never drop the connection while an account is being linked: it is the one waiting for the code to be typed in
    /** Set while the bridge is disconnected on purpose (idle timer, between checks), so the screen does not call it a failure. */
    @Volatile private var quiet = false
    private val idleOff = Runnable {
        if (bridge?.isPaired == false) return@Runnable
        quiet = true; bridge?.disconnect(); appCtx?.let { Log.log(it, "whatsapp: disconnected (idle $IDLE_MS ms after the last look)") }
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
    @Synchronized fun open(c: Context): WaTransport? {
        bridge?.let { return it }
        val app = c.applicationContext
        appCtx = app
        // the bridge is built for arm64 only: an x86 emulator cannot run it (its translation layer lacks instructions the Go runtime uses), so WhatsApp is simply absent there
        if (android.os.Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") { Log.log(app, "whatsapp: not available on ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}"); return null }
        return try {
            val dir = File(app.filesDir, "wa").apply { mkdirs() }
            val st = store(app)
            val b = WhatsmeowTransport.open(dir.absolutePath, object : WaListener {
                override fun onStatus(state: String) {
                    if (state == "connecting" || state == "connected") quiet = false
                    status = if (state == "disconnected" && quiet) "idle" else state; Log.log(app, "whatsapp: $state")
                    if (state == "paired") { setLinked(app, true); pairCode = null; Thread { settleHistory(app) }.start() }
                    // names arrive in bundles of their own, often after the messages: look the senders up again
                    if (state == "history" || state == "connected") scheduleRename(app)
                    if (state == "logged_out") {
                        // the link was removed on the main phone: the bridge still holds the deleted device, so drop it and let the next Link start clean
                        setLinked(app, false); st.wipe(); tidyMedia(app); pairCode = null
                        Thread { try { close(); status = "logged_out"; fire() } catch (_: Throwable) {} }.start()
                    }
                    fire()
                }
                override fun onPairCode(code: String) { pairCode = code; Log.log(app, "whatsapp: pairing code ready"); fire() }
                override fun onMessage(m: WaMessage) {
                    st.add(m.chat, m.chatName, nameFor(app, m.senderId, m.sender), m.senderId, m.id, m.text, m.ts, m.fromMe, history = m.history, extra = m.extra); fire(slow = m.history)
                    if (m.extra.mediaPath.isNotEmpty()) tidyMedia(app)
                    // a new message gets the child's WhatsApp tune (not our own, not the backlog); the system's ringer mode and Do Not Disturb apply to it
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!m.history && !m.fromMe && now - lastTone > 3_000L) { lastTone = now; io.github.durkimat.phoneguard.notes.MessageAlert.fire(app, io.github.durkimat.phoneguard.alarm.ToneStore.WA) }
                }
                // read on another device (the main phone): clear the stars for what arrived up to then
                override fun onRead(chat: String, ts: Long) {
                    val changed = st.markReadUpTo(chat, ts)
                    Log.log(app, "whatsapp: read elsewhere, chat ..." + chat.takeLast(3) + (if (changed) ": stars cleared" else ": nothing to clear"))
                    if (changed) fire()
                }
                override fun onLog(line: String) { Log.log(app, "whatsapp: $line") }
                // "delete for everyone" must take the picture with it; an edit changes the words
                override fun onRevoke(chat: String, targetId: String) { revoke(app, chat, targetId) }
                override fun onEdit(chat: String, targetId: String, text: String, ts: Long) { if (st.applyEdit(chat, targetId, text)) fire() }
                override fun onReaction(chat: String, targetId: String, senderId: String, sender: String, emoji: String, ts: Long, fromMe: Boolean) {
                    st.setReaction(chat, targetId, senderId, if (fromMe) "Me" else nameFor(app, senderId, sender), emoji, ts); fire()
                }
                override fun onCommand(text: String, ts: Long, id: String) { WaAdmin.onCommand(app, text, ts) }
                override fun onCall(chat: String, chatName: String, ts: Long, rejected: Boolean) {
                    // a call cannot be answered here: it is noted in the chat so nobody wonders who rang
                    st.add(chat, chatName, chatName, chat, "", if (rejected) "[call declined]" else "[missed call]", ts, false); fire()
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

    private var renamePending: Runnable? = null

    /** Gives stored group messages their senders' names, a few seconds after the last bundle (they come in bursts). */
    private fun scheduleRename(c: Context) {
        renamePending?.let { main.removeCallbacks(it) }
        val r = Runnable {
            Thread {
                try {
                    val b = bridge ?: return@Thread
                    val n = store(c).renameSenders { id -> localName(c, id) ?: b.nameFor(id).ifBlank { null } }
                    if (n > 0) fire()
                } catch (e: Throwable) { Log.log(c, "whatsapp: names refresh failed: ${e.javaClass.simpleName}") }
            }.start()
        }
        renamePending = r
        main.postDelayed(r, 3_000)
    }

    private var nameCache: Map<String, String> = emptyMap()
    private var nameCacheAt = 0L

    /** The name this phone's own Contacts give a number (the owner's nicknames: Mum, Dad, Wife), or null. */
    private fun localName(c: Context, id: String): String? {
        val digits = id.filter { it.isDigit() }
        if (digits.length < 7) return null
        val now = System.currentTimeMillis()
        if (now - nameCacheAt > 60_000L) {
            val m = HashMap<String, String>()
            for (ct in io.github.durkimat.phoneguard.launcher.LauncherData.contacts(c)) for (n in ct.numbers) m.putIfAbsent(n.filter { it.isDigit() }.takeLast(9), ct.name)
            nameCache = m; nameCacheAt = now
        }
        return nameCache[digits.takeLast(9)]
    }

    /**
     * What to call a sender. The owner's own nickname for them (this phone's Contacts) wins, as it does in WhatsApp itself;
     * otherwise what WhatsApp knows ([whatsAppName]: their name in the owner's address book, or the display name they chose);
     * otherwise the number.
     */
    private fun nameFor(c: Context, senderId: String, whatsAppName: String): String = localName(c, senderId) ?: whatsAppName

    fun isPaired(c: Context): Boolean = open(c)?.isPaired ?: false

    /** The config's allow list with the changes made by message (not yet answered by the Worker) on top. */
    fun effectiveAllowed(c: Context, w: io.github.durkimat.phoneguard.core.WaConfig): List<String> = WaPending.overlay(w.allowed, WaPending.pending(c))

    /** Applies the allow list again now (after a change made by message). */
    fun refreshAllowed(c: Context) { io.github.durkimat.phoneguard.Store.config(c)?.whatsapp?.let { setAllowed(c, effectiveAllowed(c, it).joinToString("\n"), it.all) } }

    /** [entries]: numbers and "g:Group name" entries, separated by commas or new lines. [all] allows everyone and every group. */
    fun setAllowed(c: Context, entries: String, all: Boolean = false) {
        if (prefs(c).getString("wa_allowed", null) == entries && prefs(c).getBoolean("wa_all", false) == all && bridge != null) return
        prefs(c).edit().putString("wa_allowed", entries).putBoolean("wa_all", all).apply()
        val b = open(c)
        b?.setAllowed(entries); b?.setAllowAll(all)
        val parts = entries.split(',', ';', '\n').map { it.trim() }
        val groups = parts.filter { it.startsWith("g:", ignoreCase = true) }.map { it.substring(2).trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val ids = parts.filter { it.startsWith("gid:", ignoreCase = true) }.map { it.substring(4).trim().lowercase().removeSuffix("@g.us") }.filter { it.isNotEmpty() }.toSet()
        val numbers = parts.filterNot { it.startsWith("g:", ignoreCase = true) || it.startsWith("gid:", ignoreCase = true) }.map { n -> n.filter { it.isDigit() } }.filter { it.isNotEmpty() }.toSet()
        store(c).keepOnly(numbers, groups, all, ids)
        tidyMedia(c) // pictures from chats that are no longer allowed go too
    }

    /**
     * The bridge keeps the 30-day history in memory from the moment of pairing (it never reaches the disk) so that the allow list can be made
     * current first: bring the companion's choice in (at most about a minute if it is unreachable), then let the bridge read what it kept.
     */
    private fun settleHistory(app: Context) {
        try {
            if (io.github.durkimat.phoneguard.companion.WorkerLink.isPaired(app)) {
                io.github.durkimat.phoneguard.companion.WorkerLink.sync(app, "whatsapp linked")
                freshAllowList(app)
            }
        } catch (e: Throwable) { Log.log(app, "whatsapp: settings check before history failed: ${e.javaClass.simpleName}") }
        try { bridge?.releaseHistory() } catch (e: Throwable) { Log.log(app, "whatsapp: history release failed: ${e.javaClass.simpleName}") }
    }

    /** Makes the bridge use the allow list the config on the phone says, right now (a sync only stores the config; applying follows soon after). */
    private fun freshAllowList(app: Context) {
        io.github.durkimat.phoneguard.Store.config(app)?.whatsapp?.let { setAllowed(app, effectiveAllowed(app, it).joinToString("\n"), it.all) }
    }

    /** Link screen opened: have the companion's latest choice before a code is shown. Returns quickly when there is no companion. */
    fun refreshBeforeLinking(c: Context) {
        val app = c.applicationContext
        if (!io.github.durkimat.phoneguard.companion.WorkerLink.isPaired(app)) return
        try { io.github.durkimat.phoneguard.companion.WorkerLink.sync(app, "before whatsapp link"); freshAllowList(app) } catch (e: Throwable) { Log.log(app, "whatsapp: settings check failed: ${e.javaClass.simpleName}") }
    }

    /** What the phone has applied, for the sync state: everyone allowed, and how many entries (numbers and groups) are. */
    fun allowSummary(c: Context): Pair<Boolean, Int> {
        val p = prefs(c)
        val n = (p.getString("wa_allowed", "") ?: "").split(',', ';', '\n').count { it.isNotBlank() }
        return p.getBoolean("wa_all", false) to n
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
        if (!linked(c) || io.github.durkimat.phoneguard.launcher.LauncherData.useRealWhatsApp(c)) return // nothing to connect to until an account is linked
        if (policy(c) == "off") { status = "off"; fire(); return } // the mode says no WhatsApp at all
        main.removeCallbacks(idleOff); main.postDelayed(idleOff, IDLE_MS)
        if (isConnected()) return
        status = "connecting"; fire()
        Log.log(c, "whatsapp: connecting (WhatsApp screen opened)")
        Thread { connect(c) }.start()
    }

    private const val IDLE_MS = 3 * 60_000L

    // ---- pictures, stickers, GIFs and videos

    private val tidier = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Removes media files no message points at any more (and the oldest beyond the size limit). */
    fun tidyMedia(c: Context) {
        val app = c.applicationContext
        tidier.execute { try { io.github.durkimat.media.Media.cleanup(app, store(app).mediaPathsInUse()) } catch (_: Exception) { } }
    }

    /**
     * Debug builds only, for the emulator (the bridge cannot run there): a made-up linked account and family chat with a photo, a
     * sticker, a video, one photo not yet fetched and one message to delete. Files named demo-sticker.webp, demo-anim.webp or
     * demo-video.mp4 already in the media folder are used; the photo and a still sticker are drawn here.
     */
    fun loadDemo(c: Context) {
        if (!io.github.durkimat.phoneguard.BuildConfig.DEBUG) return
        val dir = java.io.File(c.filesDir, "wa/media").apply { mkdirs() }
        fun draw(name: String, w: Int, h: Int, format: android.graphics.Bitmap.CompressFormat, paint: (android.graphics.Canvas, android.graphics.Paint) -> Unit): String {
            val f = java.io.File(dir, name)
            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            paint(android.graphics.Canvas(b), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
            f.outputStream().use { b.compress(format, 90, it) }
            return f.absolutePath
        }
        val photo = draw("demo-photo.jpg", 1200, 900, android.graphics.Bitmap.CompressFormat.JPEG) { cv, p ->
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, 900f, 0xFF86B6F0.toInt(), 0xFFE8F2FF.toInt(), android.graphics.Shader.TileMode.CLAMP); cv.drawPaint(p); p.shader = null
            p.color = 0xFFFFD84A.toInt(); cv.drawCircle(950f, 200f, 120f, p)
            p.color = 0xFF4F8F3A.toInt(); cv.drawOval(-300f, 560f, 900f, 1300f, p); p.color = 0xFF2F6A2A.toInt(); cv.drawOval(400f, 640f, 1600f, 1300f, p)
            p.color = 0xFF8A3B22.toInt(); cv.drawRect(330f, 430f, 610f, 640f, p); p.color = 0xFF3A2418.toInt(); cv.drawRect(440f, 520f, 510f, 640f, p)
        }
        val still = java.io.File(dir, "demo-sticker.webp").takeIf { it.isFile }?.absolutePath ?: draw("demo-sticker.webp", 512, 512, android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS) { cv, p ->
            p.color = 0xFFFFCC33.toInt(); cv.drawCircle(256f, 256f, 220f, p); p.color = 0xFF402000.toInt(); cv.drawCircle(180f, 200f, 34f, p); cv.drawCircle(332f, 200f, 34f, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 28f; cv.drawArc(140f, 150f, 372f, 390f, 20f, 140f, false, p)
        }
        setLinked(c, true)
        val st = store(c); st.wipe()
        val now = System.currentTimeMillis() / 1000
        fun add(n: Int, sender: String, text: String, extra: io.github.durkimat.wacore.WaExtra = io.github.durkimat.wacore.WaExtra()) =
            st.add("gdemo", "Family", sender, "4470000000$n", "D$n", text, now - 600 + n * 60, false, extra = extra)
        fun media(kind: String, path: String, size: Long = 0, seconds: Int = 0, animated: Boolean = false, mime: String = "") =
            io.github.durkimat.wacore.WaExtra(kind, path, "demo-ref", size, mime, seconds, animated = animated)
        add(1, "Mum", "Dinner at 6?")
        add(2, "Dad", "[photo]", media("image", java.io.File(dir, "not-fetched.jpg").absolutePath, size = 240_000))
        add(3, "Nan", "[video] The garden", media("video", java.io.File(dir, "demo-video.mp4").absolutePath, size = 1_400_000, seconds = 4))
        java.io.File(dir, "demo-anim.webp").takeIf { it.isFile }?.let { add(4, "Dad", "[sticker]", media("sticker", it.absolutePath, animated = true, mime = "image/webp")) }
        add(5, "Mum", "[sticker]", media("sticker", still, mime = "image/webp"))
        fun e(vararg cp: Int) = cp.joinToString("") { String(Character.toChars(it)) }
        add(6, "Dad", "Emoji: " + e(0x1F600, 0x1F60D, 0x1F44D, 0x1F3FD) + " " + e(0x2764, 0xFE0F) + " family " + e(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467) + " flag " + e(0x1F1EC, 0x1F1E7) +
            " and a long row " + e(0x1F389).repeat(24))
        st.setReaction("gdemo", "D6", "1", "Mum", e(0x1F44D), now); st.setReaction("gdemo", "D6", "2", "Nan", e(0x1F44D), now); st.setReaction("gdemo", "D6", "3", "Me", e(0x2764, 0xFE0F), now)
        add(7, "Mum", "[photo] Look at our house on the hill, taken this morning before the rain came in", media("image", photo, mime = "image/jpeg"))
        io.github.durkimat.phoneguard.gallery.ChatPictures.clear()
        fire()
    }

    /** Someone deleted a message for everyone: its words and its picture go from this phone. */
    fun revoke(c: Context, chat: String, mid: String) {
        val path = store(c).applyRevoke(chat, mid)
        if (!path.isNullOrEmpty()) { java.io.File(path).delete(); io.github.durkimat.phoneguard.gallery.ChatPictures.clear() }
        fire()
    }

    /**
     * Fetches one message's media because the child asked for it, connecting first if needed. Videos need Wi-Fi (or another
     * unmetered network). Returns "" when the file is on the phone, otherwise the reason in plain words. Blocks: call off the main thread.
     */
    fun fetchMedia(c: Context, chat: String, mid: String): String {
        val st = store(c)
        val m = st.find(chat, mid) ?: return "That message is gone"
        if (m.deleted || m.mediaRef.isEmpty()) return "Not available"
        if (m.kind == "video" && !io.github.durkimat.media.Media.unmetered(c)) return "Videos need Wi-Fi"
        if (m.mediaSize > io.github.durkimat.media.Rich.MAX_VIDEO_BYTES) return "Too big for this phone"
        if (policy(c) == "off") return "WhatsApp is off now"
        return try {
            main.removeCallbacks(idleOff); main.postDelayed(idleOff, IDLE_MS)
            if (!isConnected()) { connect(c); for (i in 1..20) { if (isConnected()) break; Thread.sleep(500) } }
            val b = open(c)
            if (b == null || !isConnected()) return "Not connected. Try again"
            st.setMediaPath(chat, mid, b.downloadMedia(m.mediaRef, mid, m.mime))
            tidyMedia(c); fire(); ""
        } catch (e: Throwable) {
            Log.log(c, "whatsapp: could not get media: ${e.javaClass.simpleName}")
            "Could not get it. It may have expired"
        }
    }

    /** The WhatsApp check setting of the mode in force: "off", "open", "30" or "60". */
    fun policy(c: Context): String = Engine.effective(Store.config(c), Store.boost(c), Clock.now()).wa

    /**
     * Called whenever the controller re-evaluates: applies who may be read, the send limits, whether WhatsApp calls are
     * declined (when the mode blocks incoming calls) and arms or cancels the background check.
     */
    fun apply(c: Context, cfg: Config?, eff: Effective) {
        if (!linked(c)) { cancelAlarm(c); return }
        // the real WhatsApp is in use: only one client should be on the phone, so ours stands down completely
        if (Engine.useRealWhatsApp(eff.waApp, io.github.durkimat.phoneguard.Enforcer.launcherPackages(c).contains(io.github.durkimat.phoneguard.launcher.LauncherData.WHATSAPP), true)) {
            cancelAlarm(c); main.removeCallbacks(idleOff); quiet = true; bridge?.disconnect(); return
        }
        cfg?.whatsapp?.let { w ->
            setAllowed(c, effectiveAllowed(c, w).joinToString("\n"), w.all)
            open(c)?.setTag(if (w.tagSent) w.tagName else "") // the bridge puts the name in front itself
            open(c)?.setAdmin(w.adminGroup, WaPending.lastCommand(c))
            open(c)?.setLimits(w.perMinute.toLong(), w.perDay.toLong())
        }
        rejectCalls(c, !eff.inbound)
        Thread { try { store(c).pruneChats() } catch (_: Exception) { } }.start()
        when (eff.wa) {
            "off" -> { cancelAlarm(c); main.removeCallbacks(idleOff); quiet = true; bridge?.disconnect() }
            "30" -> schedule(c, 30)
            "60" -> schedule(c, 60)
            else -> cancelAlarm(c) // "open": only when WhatsApp is opened
        }
    }

    private fun pending(c: Context) = android.app.PendingIntent.getBroadcast(
        c, 21, android.content.Intent(c, WaAlarmReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Arms the next background check. The due time is KEPT while the interval is unchanged: the controller re-evaluates often (every sync, call, update), and
     * pushing the alarm to "now + interval" each time meant a check never came due. [fresh] = a check just ran, so a new interval starts now.
     * A due time that passed while the phone was off or asleep means the check is late: it runs in a few seconds.
     */
    private fun schedule(c: Context, minutes: Int, fresh: Boolean = false) {
        val now = System.currentTimeMillis()
        val p = prefs(c)
        val every = minutes * 60_000L
        var due = p.getLong("wa_due", 0L)
        val keep = !fresh && due > 0 && p.getInt("wa_every", 0) == minutes && due - now <= every
        if (!keep) { due = now + every; p.edit().putLong("wa_due", due).putInt("wa_every", minutes).apply(); Log.log(c, "whatsapp: next check scheduled for ${hhmm(due)} (every $minutes min)") }
        else if (due <= now) { due = now + 10_000L; Log.log(c, "whatsapp: check was due, running now") }
        val am = c.getSystemService(android.app.AlarmManager::class.java)
        try { am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, due, pending(c)) }
        catch (e: SecurityException) { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, due, pending(c)) }
    }

    private fun hhmm(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(ms))

    private fun cancelAlarm(c: Context) { c.getSystemService(android.app.AlarmManager::class.java).cancel(pending(c)); prefs(c).edit().remove("wa_due").remove("wa_every").apply() }

    private val polling = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One background check: connect, let WhatsApp deliver what it has queued, disconnect, and arm the next one. */
    fun poll(c: Context) {
        val app = c.applicationContext
        val p = policy(app)
        if (!linked(app) || (p != "30" && p != "60") || io.github.durkimat.phoneguard.launcher.LauncherData.useRealWhatsApp(app)) return
        if (!polling.compareAndSet(false, true)) return
        io.github.durkimat.phoneguard.net.RadioManager.begin(app, "whatsapp check")
        try {
            io.github.durkimat.phoneguard.net.RadioManager.awaitNetwork(app, 20_000)
            val wasOn = isConnected()
            Log.log(app, "whatsapp: check due (scheduled ${hhmm(prefs(app).getLong("wa_due", 0L))}), starting (${if (wasOn) "already connected" else "connecting"}, every $p min)")
            if (!wasOn) { connect(app); for (i in 1..40) { if (isConnected()) break; Thread.sleep(500) } }
            if (isConnected()) Thread.sleep(if (wasOn) 0 else SETTLE_MS) // queued messages arrive straight after connecting
            if (!wasOn) { quiet = true; bridge?.disconnect(); Log.log(app, "whatsapp: disconnected (check finished)") }
            val bat = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val pct = bat?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) } ?: -1
            val plugged = (bat?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            Log.log(app, "whatsapp: checked (${if (wasOn) "already connected" else "connected briefly"}) battery=$pct% ${if (plugged) "charging" else "on battery"}")
        } finally {
            io.github.durkimat.phoneguard.net.RadioManager.end(app, "whatsapp check")
            polling.set(false)
            schedule(app, if (p == "60") 60 else 30, fresh = true)
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
        if (r == "sent") { store(c).add(chat, "", "Me", "", "", text.trim(), System.currentTimeMillis() / 1000, true); fire() }
        return r
    }

    /** True when the library is complaining about a device that was removed from the account: the bridge must be rebuilt, not reused. */
    private fun staleDevice(e: Throwable) = (e.message ?: "").let { it.contains("deleted device", true) || it.contains("logged out", true) || it.contains("not logged in", true) }

    /** Plain words for the screen, never the library's own text. */
    fun plain(e: Throwable): String = when {
        staleDevice(e) -> "The link was removed. Try again"
        (e.message ?: "").let { it.contains("timeout", true) || it.contains("network", true) || it.contains("resolve", true) || it.contains("connect", true) } -> "No connection. Try again"
        else -> "Something went wrong. Try again"
    }

    fun pair(c: Context, number: String): String {
        pairCode = null // never show the code from an earlier attempt
        refreshBeforeLinking(c)
        main.removeCallbacks(idleOff)
        fire()
        for (attempt in 1..2) {
            try { open(c)?.pairWithPhone(number); return "pairing started" } catch (e: Throwable) {
                Log.log(c, "whatsapp: pairing failed: ${e.message}")
                if (attempt == 1 && staleDevice(e)) { setLinked(c, false); close(); continue }
                return "error: " + plain(e)
            }
        }
        return "error: Something went wrong. Try again"
    }
    fun connect(c: Context): String = try {
        // pictures, stickers and GIFs arrive by themselves only on Wi-Fi (the child's mobile data is limited); otherwise OK on the message asks
        open(c)?.let { it.setMedia(io.github.durkimat.media.Media.unmetered(c)); it.connect() }; "ok"
    } catch (e: Throwable) {
        Log.log(c, "whatsapp: connect failed (${plain(e)})")
        if (staleDevice(e)) { setLinked(c, false); close() }
        "error: " + plain(e)
    }
    fun disconnect() { quiet = true; bridge?.disconnect() }
    fun isConnected(): Boolean = bridge?.isConnected ?: false
    /**
     * The chat was opened on this phone: tell WhatsApp, so the main phone shows it read too. Must be called before the unread marks are
     * cleared (it reads them). Runs on its own thread because it may have to connect first; a failure only leaves the main phone as it was.
     */
    fun readChat(c: Context, chat: String) {
        val app = c.applicationContext
        val info = store(app).readInfo(chat) ?: return
        if (info.ids.isEmpty() && info.lastId.isEmpty()) return
        Thread {
            try {
                main.removeCallbacks(idleOff); main.postDelayed(idleOff, IDLE_MS)
                if (!isConnected()) { connect(app); for (i in 1..20) { if (isConnected()) break; Thread.sleep(500) } }
                if (!isConnected()) { Log.log(app, "whatsapp: could not tell WhatsApp a chat was read (not connected)"); return@Thread }
                open(app)?.markRead(chat, info.ids, info.senders, info.lastId, info.lastSender, info.lastFromMe, info.lastTs)
                Log.log(app, "whatsapp: told WhatsApp chat ..." + chat.takeLast(3) + " was read")
            } catch (e: Throwable) { Log.log(app, "whatsapp: could not tell WhatsApp a chat was read: ${e.message}") }
        }.start()
    }

    /** What is actually sent: with the tag on, the child's name goes in front so a group can tell who wrote it. Our own copy keeps the plain text. */
    fun tagged(c: Context, text: String): String = text // the bridge puts the child's name in front itself (setTag), so no screen can skip it
    /** Sends one of the app's stickers, connecting first if needed. Returns "sent" or the reason it was not. Blocks: call off the main thread. */
    fun sendSticker(c: Context, chat: String, stickerId: String): String {
        val sticker = io.github.durkimat.media.StickerLibrary.all(c).firstOrNull { it.id == stickerId } ?: return "that sticker is missing"
        main.removeCallbacks(idleOff); main.postDelayed(idleOff, IDLE_MS)
        if (!isConnected()) { connect(c); for (i in 1..20) { if (isConnected()) break; Thread.sleep(500) } }
        val b = open(c)
        if (b == null || !isConnected()) return "not connected"
        // our own copy, in the media folder, so that the message page can show what was sent
        val own = java.io.File(io.github.durkimat.media.Media.dir(c, "wa/media"), "own-${System.nanoTime()}.webp")
        return try {
            c.assets.open(sticker.asset).use { i -> own.outputStream().use { i.copyTo(it) } }
            val st = store(c)
            // the group is told who sent it ("Sent by <child>", when the name tag is on) unless a sticker of theirs has only just gone
            val label = io.github.durkimat.wacore.WaLabels.needsLabel(st.messages(chat).lastOrNull(), st.lastReactionMs(chat), System.currentTimeMillis())
            val id = b.sendSticker(chat, own.absolutePath, sticker.animated, label)
            st.add(chat, "", "Me", "", id, "[sticker]", System.currentTimeMillis() / 1000, true,
                extra = io.github.durkimat.wacore.WaExtra(kind = "sticker", mediaPath = own.absolutePath, mime = "image/webp", width = 512, height = 512, animated = sticker.animated))
            fire(); "sent"
        } catch (e: Throwable) {
            own.delete()
            Log.log(c, "whatsapp: sticker not sent: ${e.javaClass.simpleName}")
            e.message ?: "error"
        }
    }
    fun send(c: Context, chat: String, text: String): String = try { open(c)?.send(chat, tagged(c, text)); "sent" } catch (e: Throwable) { e.message ?: "error" }
    /**
     * Pulls names and gaps back in without unlinking: asks WhatsApp again for the address-book names and the groups, gives
     * stored messages their senders' names, then asks the owner's main phone for older messages in the recently active chats
     * (they arrive afterwards as history). Returns a short result for the screen.
     */
    fun resync(c: Context): String {
        val b = open(c) ?: return "not available"
        if (!linked(c)) return "not linked"
        main.removeCallbacks(idleOff); main.postDelayed(idleOff, IDLE_MS)
        var problem = ""
        try { b.resync() } catch (e: Throwable) { problem = plain(e); Log.log(c, "whatsapp: resync names failed: $problem") }
        val st = store(c)
        nameCacheAt = 0L // pick up Contacts changed since the last look
        val renamed = st.renameSenders { id -> localName(c, id) ?: b.nameFor(id).ifBlank { null } }
        var asked = 0
        for (a in st.anchors()) {
            try { b.requestHistory(a.chat, a.mid, a.ts, a.mine, 20); asked++ } catch (e: Throwable) { if (problem.isEmpty()) problem = e.message ?: "error" }
            Thread.sleep(400)
        }
        Log.log(c, "whatsapp: re-sync done: $renamed names refreshed, older messages asked for in $asked chats${if (problem.isNotEmpty()) " (problem: $problem)" else ""}")
        fire()
        return if (problem.isNotEmpty() && asked == 0) "error: $problem" else "ok"
    }

    /** Unlinks the account: the bridge connects if it has to, then everything stored is wiped and the bridge is reset so a new link starts clean. */
    @Synchronized fun logout(c: Context): String = try {
        open(c)?.logout()
        store(c).wipe(); tidyMedia(c)
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
