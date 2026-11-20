package io.github.durkimat.familychat

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.durkimat.wacore.WaListener
import io.github.durkimat.wacore.WaMessage
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

    fun init(c: Context) { if (this::app.isInitialized) return; app = c.applicationContext; settings = PrefsSettings(app) }

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
                    if (m.fromMe) st.add(m.chat, m.chatName, settings.parentName, "", m.id, m.text, m.ts, mine = false, history = m.history)
                    else st.add(m.chat, m.chatName, m.sender, m.senderId, m.id, m.text, m.ts, mine = false, history = m.history)
                    fire()
                }
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

    /** The child's message. Returns "" when sent, else a short reason. */
    fun send(chat: String, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (!connectAndWait()) return "Not connected. Try again in a moment."
        return try {
            open()?.send(chat, t)
            // WhatsApp does not echo a message back to the device that sent it, so keep our own copy
            store().add(chat, "", "", "", "", t, System.currentTimeMillis() / 1000, true); fire(); ""
        } catch (e: Throwable) { "Could not send. Try again." }
    }

    private fun command(text: String, ts: Long) {
        val b = bridge ?: return
        settings.lastCommand = ts
        val br = brain ?: AdminBrain(
            child = { settings.childName }, adminId = { settings.adminGroupId },
            allowed = { settings.allowedGroups },
            setAllowed = { settings.allowedGroups = it; applyConfig() },
            groups = { b.groups() },
        ).also { brain = it }
        val reply = try { br.handle(text) } catch (e: Throwable) { return }
        try { b.sendAdmin(reply) } catch (_: Throwable) { }
        applyConfig()
    }

    fun sendAdmin(text: String) { try { open()?.sendAdmin(text) } catch (_: Throwable) { } }

    /** Removes the link and everything kept on this phone. */
    fun unlink() {
        wantConnected = false
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
