package io.github.durkimat.familychat

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.durkimat.wacore.WaListener
import io.github.durkimat.wacore.WaMessage
import io.github.durkimat.wacore.WaExtra
import io.github.durkimat.wacore.WaMsg
import io.github.durkimat.wacore.WaStore
import io.github.durkimat.wacore.WaTransport
import io.github.durkimat.wacore.WhatsmeowTransport
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class FcApp : Application() { override fun onCreate() { super.onCreate(); Fc.init(this) } }

/** The app's one connection to WhatsApp and the things around it. Screens listen with [listeners] and redraw; nothing here draws. */
object Fc {
    private lateinit var app: Context
    lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())

    @Volatile var status = "off"
    @Volatile var pairCode: String? = null
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var bridge: WaTransport? = null
    private var store: WaStore? = null
    private var brain: AdminBrain? = null
    @Volatile private var wantConnected = false
    /** The conversation on screen, if any: it gets no notification, and what arrives in it is read at once. */
    @Volatile var openChat: String? = null

    fun init(c: Context) { if (this::app.isInitialized) return; app = c.applicationContext; settings = PrefsSettings(app) }

    const val KEEP = 100

    private fun fire() { main.post { listeners.forEach { it() } } }

    @Synchronized fun store(): WaStore = store ?: WaStore(app).also { store = it }

    @Synchronized fun open(): WaTransport? {
        bridge?.let { return it }
        if (android.os.Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") { status = "unsupported"; return null }
        return try {
            val dir = File(app.filesDir, "wa").apply { mkdirs() }
            val st = store()
            val b = WhatsmeowTransport.open(dir.absolutePath, object : WaListener {
                override fun onStatus(state: String) {
                    status = state
                    if (state == "paired") { settings.linked = true; pairCode = null }
                    if (state == "connected") applyConfig()
                    if (state == "logged_out") { settings.linked = false; pairCode = null; wantConnected = false }
                    if (state == "disconnected" && wantConnected) main.postDelayed({ if (wantConnected && bridge?.isConnected == false) try { bridge?.connect() } catch (_: Throwable) { } }, 30_000)
                    fire()
                }
                override fun onPairCode(code: String) { pairCode = code; fire() }
                override fun onMessage(m: WaMessage) {
                    // this phone's own sends are kept by send(); a message from our own account here comes from the parent's phone, so it shows under the parent's name
                    if (m.fromMe) st.add(m.chat, m.chatName, settings.parentName, "", m.id, m.text, m.ts, mine = false, keep = KEEP, history = m.history, read = true, extra = m.extra)
                    else st.add(m.chat, m.chatName, m.sender, m.senderId, m.id, m.text, m.ts, mine = false, keep = KEEP, history = m.history, extra = m.extra)
                    if (m.extra.mediaPath.isNotEmpty()) main.post { Thread { Media.cleanup(app) }.start() }
                    // a notification only for a live message from someone else, in an allowed group (never the child's own, never the history sync, never the admin group)
                    if (!m.fromMe && !m.history && openChat != m.chat && settings.allowedGroups.any { "g${it.id}" == m.chat }) Notifier.message(app, m.chat, m.chatName.ifBlank { settings.allowedGroups.firstOrNull { "g${it.id}" == m.chat }?.name ?: "" }, m.sender, m.text)
                    if (openChat == m.chat && !m.fromMe) readChat(m.chat)
                    fire()
                }
                override fun onReaction(chat: String, targetId: String, senderId: String, sender: String, emoji: String, ts: Long, fromMe: Boolean) {
                    st.setReaction(chat, targetId, senderId, if (fromMe) settings.parentName else sender, emoji, ts); fire()
                }
                override fun onEdit(chat: String, targetId: String, text: String, ts: Long) { st.applyEdit(chat, targetId, text); fire() }
                override fun onRevoke(chat: String, targetId: String) { st.applyRevoke(chat, targetId)?.let { if (it.isNotEmpty()) File(it).delete() }; fire() }
                override fun onCall(chat: String, chatName: String, ts: Long, rejected: Boolean) { }
                override fun onRead(chat: String, ts: Long) { }
                override fun onCommand(text: String, ts: Long, id: String) { Thread { command(text, ts) }.start() }
                override fun onLog(line: String) { }
            })
            bridge = b
            status = if (b.isPaired) "linked" else "not linked"
            settings.linked = b.isPaired
            applyConfig()
            b
        } catch (e: Throwable) { status = "error"; null }
    }

    /** Gives the transport what it must enforce: the allowed groups (by id), the child's name in front of what is sent, the parent's name, the admin group. */
    fun applyConfig() {
        val b = bridge ?: return
        val s = settings
        try {
            b.setAllowAll(false)
            b.setMedia(s.showMedia)
            b.setAllowed(s.allowedGroups.joinToString("\n") { "gid:${it.id}" })
            b.setTag(s.childName)
            b.setOwnName(s.parentName)
            b.setAdmin(s.adminGroupId, s.lastCommand)
            b.setRejectCalls(true)
            store().keepOnly(emptySet(), emptySet(), false, s.allowedGroups.map { it.id.lowercase() }.toSet())
        } catch (_: Throwable) { }
        fire()
    }

    fun connect() { wantConnected = true; try { open()?.connect() } catch (_: Throwable) { status = "error" } }

    fun connectAndWait(): Boolean {
        connect()
        for (i in 1..30) { if (bridge?.isConnected == true) return true; Thread.sleep(500) }
        return bridge?.isConnected == true
    }

    fun isConnected() = bridge?.isConnected == true
    fun isPaired() = open()?.isPaired == true

    fun pair(number: String): String = try { pairCode = null; open()?.pairWithPhone(number.filter { it.isDigit() }); "" } catch (e: Throwable) { e.message ?: "error" }

    /** The account's groups, fresh when connected (and remembered), else the last list. */
    fun groups(): List<Group> {
        try { if (connectAndWait()) { val l = Group.parse(open()?.groups() ?: ""); if (l.isNotEmpty()) { settings.knownGroups = l; return l } } } catch (_: Throwable) { }
        return settings.knownGroups
    }

    private fun now() = System.currentTimeMillis() / 1000

    /** Runs a send: connects first, turns an error into plain words. Returns "" when it worked. */
    private fun sending(block: (WaTransport) -> Unit): String {
        if (!connectAndWait()) return "Not connected. Try again in a moment."
        return try { block(open() ?: return "Not connected. Try again in a moment."); "" } catch (e: Throwable) { Rich.sendError(e) }
    }

    /** The child's text message, optionally a reply to [quote]. The name goes in front inside the bridge. Returns "" when sent, else a short reason. */
    fun send(chat: String, text: String, quote: WaMsg? = null): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        var id = ""
        val err = sending { b -> id = if (quote == null) b.sendReply(chat, t, "", "", "") else b.sendReply(chat, t, quote.mid, quote.senderId, Rich.snippet(quote)) }
        if (err.isNotEmpty()) return err
        // WhatsApp does not echo a message back to the device that sent it, so keep our own copy (with its id: only these can be edited or deleted)
        store().add(chat, "", "", "", id, t, now(), true, keep = KEEP, extra = if (quote == null) WaExtra() else WaExtra(quoteId = quote.mid, quoteText = Rich.snippet(quote), quoteSender = quote.sender.ifBlank { settings.childName }))
        fire(); return ""
    }

    /** The child's reaction: not a real reaction (it would look like the parent's) but a short reply quoting the message, "<child>: <emoji>". */
    fun react(chat: String, target: WaMsg, emoji: String): String = send(chat, emoji, target)

    fun edit(chat: String, m: WaMsg, text: String): String {
        val t = text.trim()
        if (t.isEmpty() || !Rich.canEdit(m, System.currentTimeMillis())) return "That message can no longer be changed."
        val err = sending { it.editMessage(chat, m.mid, t) }
        if (err.isEmpty()) { store().applyEdit(chat, m.mid, t); fire() }
        return err
    }

    fun delete(chat: String, m: WaMsg): String {
        if (!Rich.canDelete(m)) return "Only your own messages can be deleted."
        val err = sending { it.deleteMessage(chat, m.mid) }
        if (err.isEmpty()) { store().applyRevoke(chat, m.mid)?.let { if (it.isNotEmpty()) File(it).delete() }; fire() }
        return err
    }

    /** A bundled sticker (an asset path). The bridge sends "Sent by <child>" first, then the sticker. */
    fun sendSticker(chat: String, asset: String): String {
        val own = File(Media.dir(app, "wa/media"), "own-${System.nanoTime()}.webp")
        app.assets.open(asset).use { i -> own.outputStream().use { i.copyTo(it) } }
        var id = ""
        val err = sending { id = it.sendSticker(chat, own.absolutePath, false) }
        if (err.isNotEmpty()) { own.delete(); return err }
        store().add(chat, "", "", "", id, "", now(), true, keep = KEEP, extra = WaExtra(kind = "sticker", mediaPath = own.absolutePath, mime = "image/webp", width = 512, height = 512))
        fire(); return ""
    }

    /** A prepared photo (JPEG, already scaled and clean) with a caption; the name is put in front of the caption inside the bridge. */
    fun sendPhoto(chat: String, jpeg: File, text: String, w: Int, h: Int): String {
        val own = File(Media.dir(app, "wa/media"), "own-${System.nanoTime()}.jpg"); jpeg.copyTo(own, true)
        var id = ""
        val err = sending { id = it.sendPhoto(chat, own.absolutePath, text, w, h) }
        if (err.isNotEmpty()) { own.delete(); return err }
        store().add(chat, "", "", "", id, if (text.isBlank()) "[photo]" else "[photo] ${text.trim()}", now(), true, keep = KEEP, extra = WaExtra(kind = "image", mediaPath = own.absolutePath, mime = "image/jpeg", width = w, height = h))
        jpeg.delete(); fire(); return ""
    }

    fun sendVideo(chat: String, mp4: File, text: String, seconds: Int, w: Int, h: Int): String {
        val own = File(Media.dir(app, "wa/media"), "own-${System.nanoTime()}.mp4"); mp4.copyTo(own, true)
        var id = ""
        val err = sending { id = it.sendVideo(chat, own.absolutePath, text, seconds, w, h) }
        if (err.isNotEmpty()) { own.delete(); return err }
        store().add(chat, "", "", "", id, if (text.isBlank()) "[video]" else "[video] ${text.trim()}", now(), true, keep = KEEP, extra = WaExtra(kind = "video", mediaPath = own.absolutePath, mime = "video/mp4", seconds = seconds, width = w, height = h, mediaSize = own.length()))
        mp4.delete(); fire(); return ""
    }

    /**
     * The file of a message's picture or video: the one we hold, else fetched now (a tapped video; a picture the cache cleaner removed).
     * Returns (path, "") or ("", reason). Videos wait for Wi-Fi when the parent's switch says so.
     */
    fun fetchMedia(chat: String, m: WaMsg): Pair<String, String> {
        if (m.mediaPath.isNotEmpty() && File(m.mediaPath).exists()) return m.mediaPath to ""
        if (m.mediaRef.isEmpty()) return "" to "This file is not available."
        if (m.kind == "video" && m.mediaSize > Rich.MAX_VIDEO_BYTES) return "" to "This video is too big to download."
        if (m.kind == "video" && settings.wifiOnlyVideos && !Media.unmetered(app)) return "" to "Connect to Wi-Fi to download this video."
        if (!connectAndWait()) return "" to "Not connected. Try again in a moment."
        return try {
            val p = open()!!.downloadMedia(m.mediaRef, m.mid.ifEmpty { "m${m.date}" }, m.mime)
            store().setMediaPath(chat, m.mid, p); fire(); p to ""
        } catch (e: Throwable) { "" to "Could not download. Try again." }
    }

    private fun command(text: String, ts: Long) {
        val b = bridge ?: return
        settings.lastCommand = ts
        val br = brain ?: AdminBrain(
            child = { settings.childName }, adminId = { settings.adminGroupId },
            allowed = { settings.allowedGroups },
            setAllowed = { settings.allowedGroups = it; applyConfig() },
            groups = { b.groups() },
            setPhotos = { settings.photosAllowed = it; fire() },
        ).also { brain = it }
        val reply = try { br.handle(text) } catch (e: Throwable) { return }
        try { b.sendAdmin(reply) } catch (_: Throwable) { }
        applyConfig()
    }

    /** Tells WhatsApp the chat was read here, so the parent's phone shows it read (the switch in the admin menu; on unless turned off). */
    fun readChat(chat: String) {
        val st = store()
        val info = st.readInfo(chat)
        st.markRead(chat)
        if (!settings.sendReadReceipts || info == null || (info.ids.isEmpty() && info.lastId.isEmpty())) return
        Thread { try { if (connectAndWait()) open()?.markRead(chat, info.ids, info.senders, info.lastId, info.lastSender, info.lastFromMe, info.lastTs) } catch (_: Throwable) { } }.start()
    }

    fun sendAdmin(text: String) { try { open()?.sendAdmin(text) } catch (_: Throwable) { } }

    /** Removes the link and everything kept on this phone. */
    fun unlink() {
        wantConnected = false; Notifier.cancelAll(app)
        try { bridge?.logout() } catch (_: Throwable) { }
        try { bridge?.close() } catch (_: Throwable) { }
        bridge = null; status = "off"; pairCode = null
        try { store().wipe() } catch (_: Throwable) { }
        try { File(app.filesDir, "wa").deleteRecursively() } catch (_: Throwable) { }
        settings.clear()
        brain = null
        fire()
    }
}
