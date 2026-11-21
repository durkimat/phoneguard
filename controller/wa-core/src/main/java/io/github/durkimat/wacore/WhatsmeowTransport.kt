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
    override fun setTag(name: String) { child = name.trim(); b.setTag(name) }
    override fun setOwnName(name: String) = b.setOwnName(name)
    override fun setAdmin(groupId: String, lastApplied: Long) = b.setAdmin(groupId, lastApplied)
    override fun sendAdmin(text: String) = b.sendAdmin(text)
    override fun setAdminAnyMember(on: Boolean) = b.setAdminAnyMember(on)
    override fun groups(): String = b.groups()

    @Volatile private var child = ""

    override fun setMedia(on: Boolean) = b.setMedia(on)
    override fun downloadMedia(ref: String, id: String, mime: String): String = b.downloadMedia(ref, id, mime)
    override fun mediaDir(): String = b.mediaDir()
    override fun sendReply(chat: String, text: String, quoteId: String, quoteSender: String, quoteText: String): String = b.sendTagged(chat, text, quoteId, quoteSender, quoteText)
    override fun sendSticker(chat: String, path: String, animated: Boolean): String = run(chat, WaLabels.sticker(child, path, animated))
    override fun sendPhoto(chat: String, path: String, caption: String, width: Int, height: Int): String = run(chat, WaLabels.photo(child, path, caption, width, height))
    override fun sendVideo(chat: String, path: String, caption: String, seconds: Int, width: Int, height: Int): String = run(chat, WaLabels.video(child, path, caption, seconds, width, height))
    override fun editMessage(chat: String, id: String, text: String) = b.editMessage(chat, id, text)
    override fun deleteMessage(chat: String, id: String) = b.deleteMessage(chat, id)

    /** The only way anything is sent that cannot carry the name itself: the steps come from [WaLabels], all fit the send limits or none is sent, and they go in order. */
    private fun run(chat: String, steps: List<WaLabels.Step>): String {
        b.reserve(steps.size.toLong())
        var id = ""
        for (s in steps) id = when (s) {
            is WaLabels.Step.Text -> b.sendText(chat, s.text)
            is WaLabels.Step.Sticker -> b.sendSticker(chat, s.path, s.animated)
            is WaLabels.Step.Photo -> b.sendImage(chat, s.path, s.caption, s.width.toLong(), s.height.toLong())
            is WaLabels.Step.Video -> b.sendVideo(chat, s.path, s.caption, s.seconds.toLong(), s.width.toLong(), s.height.toLong())
        }
        return id
    }

    companion object {
        /** Opens the bridge in [dir] (does not connect). */
        fun open(dir: String, l: WaListener): WaTransport = WhatsmeowTransport(Wabridge.open(dir, object : Listener {
            override fun onStatus(state: String) = l.onStatus(state)
            override fun onPairCode(code: String) = l.onPairCode(code)
            override fun onMessage(m: Message) = l.onMessage(WaMessage(m.chat, m.chatName, m.sender, m.senderID, m.id, m.text, m.ts, m.fromMe, m.history,
                WaExtra(m.kind, m.mediaPath, m.mediaRef, m.mediaSize, m.mime, m.seconds.toInt(), m.width.toInt(), m.height.toInt(), m.animated, m.quoteID, m.quoteText, m.quoteSender)))
            override fun onReaction(chat: String, targetID: String, senderID: String, sender: String, emoji: String, ts: Long, fromMe: Boolean) = l.onReaction(chat, targetID, senderID, sender, emoji, ts, fromMe)
            override fun onEdit(chat: String, targetID: String, text: String, ts: Long) = l.onEdit(chat, targetID, text, ts)
            override fun onRevoke(chat: String, targetID: String) = l.onRevoke(chat, targetID)
            override fun onCall(chat: String, chatName: String, ts: Long, rejected: Boolean) = l.onCall(chat, chatName, ts, rejected)
            override fun onRead(chat: String, ts: Long) = l.onRead(chat, ts)
            override fun onCommand(text: String, ts: Long, id: String) = l.onCommand(text, ts, id)
            override fun onLog(line: String) = l.onLog(line)
        }))
    }
}
