package io.github.durkimat.wacore

import wabridge.Bridge
import wabridge.Listener
import wabridge.Message
import wabridge.Wabridge

/** The [WaTransport] over the Go bridge (whatsmeow, MPL-2.0): a linked device on the parent's account. */
class WhatsmeowTransport private constructor(private val b: Bridge) : WaTransport {
    override val isPaired: Boolean get() = b.isPaired
    override val isConnected: Boolean get() = b.isConnected
    override fun pairWithPhone(number: String) = b.pairWithPhone(number)
    override fun connect() = b.connect()
    override fun disconnect() = b.disconnect()
    override fun logout() = b.logout()
    override fun close() = b.close()
    override fun setAllowed(entries: String) = b.setAllowed(entries)
    override fun setAllowAll(all: Boolean) = b.setAllowAll(all)
    override fun setRejectCalls(on: Boolean) = b.setRejectCalls(on)
    override fun setLimits(perMinute: Long, perDay: Long) = b.setLimits(perMinute, perDay)
    override fun send(chat: String, text: String) = b.send(chat, text)
    override fun markRead(chat: String, ids: String, senders: String, lastId: String, lastSender: String, lastFromMe: Boolean, lastTs: Long) =
        b.markRead(chat, ids, senders, lastId, lastSender, lastFromMe, lastTs)
    override fun resync() = b.resync()
    override fun requestHistory(chat: String, msgId: String, ts: Long, fromMe: Boolean, count: Long) = b.requestHistory(chat, msgId, ts, fromMe, count)
    override fun nameFor(id: String): String = b.nameFor(id)
    override fun releaseHistory() = b.releaseHistory()
    override fun setTag(name: String) = b.setTag(name)
    override fun setOwnName(name: String) = b.setOwnName(name)
    override fun setAdmin(groupId: String, lastApplied: Long) = b.setAdmin(groupId, lastApplied)
    override fun sendAdmin(text: String) = b.sendAdmin(text)
    override fun groups(): String = b.groups()

    companion object {
        /** Opens the bridge in [dir] (does not connect). */
        fun open(dir: String, l: WaListener): WaTransport = WhatsmeowTransport(Wabridge.open(dir, object : Listener {
            override fun onStatus(state: String) = l.onStatus(state)
            override fun onPairCode(code: String) = l.onPairCode(code)
            override fun onMessage(m: Message) = l.onMessage(WaMessage(m.chat, m.chatName, m.sender, m.senderID, m.id, m.text, m.ts, m.fromMe, m.history))
            override fun onCall(chat: String, chatName: String, ts: Long, rejected: Boolean) = l.onCall(chat, chatName, ts, rejected)
            override fun onRead(chat: String, ts: Long) = l.onRead(chat, ts)
            override fun onCommand(text: String, ts: Long, id: String) = l.onCommand(text, ts, id)
            override fun onLog(line: String) = l.onLog(line)
        }))
    }
}
