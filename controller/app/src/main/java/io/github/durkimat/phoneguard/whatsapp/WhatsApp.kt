package io.github.durkimat.phoneguard.whatsapp

import android.content.Context
import io.github.durkimat.phoneguard.Log
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
                override fun onStatus(state: String) { status = state; Log.log(app, "whatsapp: $state"); fire() }
                override fun onPairCode(code: String) { pairCode = code; Log.log(app, "whatsapp: pairing code ready"); fire() }
                override fun onMessage(chat: String, name: String, text: String, ts: Long, fromMe: Boolean) { st.add(chat, name, text, ts, fromMe); fire() }
            })
            bridge = b
            status = if (b.isPaired) "linked" else "not linked"
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

    fun setAllowed(c: Context, numbers: String) {
        open(c)?.setAllowed(numbers)
        store(c).keepOnly(numbers.split(',', ';', '\n').map { n -> n.filter { it.isDigit() } }.filter { it.isNotEmpty() }.toSet())
    }

    fun pair(c: Context, number: String): String = try { open(c)?.pairWithPhone(number); "pairing started" } catch (e: Throwable) { "error: ${e.message}" }
    fun connect(c: Context): String = try { open(c)?.connect(); "ok" } catch (e: Throwable) { "error: ${e.message}" }
    fun disconnect() { bridge?.disconnect() }
    fun isConnected(): Boolean = bridge?.isConnected ?: false
    fun send(c: Context, chat: String, text: String): String = try { open(c)?.send(chat, text); "sent" } catch (e: Throwable) { e.message ?: "error" }
    fun logout(c: Context): String = try { open(c)?.logout(); store(c).wipe(); "logged out" } catch (e: Throwable) { "error: ${e.message}" }
}
