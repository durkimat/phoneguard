package io.github.durkimat.wacore

/** What the app hears from the connection. Calls come from background threads. */
interface WaListener {
    fun onStatus(state: String)
    fun onPairCode(code: String)
    fun onMessage(m: WaMessage)
    fun onCall(chat: String, chatName: String, ts: Long, rejected: Boolean)
    fun onRead(chat: String, ts: Long)
    /** A command the parent typed in the admin group (only from another device of the account, live, newer than the last applied). */
    fun onCommand(text: String, ts: Long, id: String)
    fun onLog(line: String)
    // richer messages: only Family Chat uses these (Phone Guard keeps the empty defaults)
    /** [emoji] empty = the reaction was taken back. */
    fun onReaction(chat: String, targetId: String, senderId: String, sender: String, emoji: String, ts: Long, fromMe: Boolean) {}
    fun onEdit(chat: String, targetId: String, text: String, ts: Long) {}
    fun onRevoke(chat: String, targetId: String) {}
}

/**
 * The connection to WhatsApp, as both apps use it. Today the only implementation is [WhatsmeowTransport] (the unofficial linked-device
 * library, built as a Go bridge); another way in (the official Cloud API, say) would be one more implementation of this interface and
 * touch neither app. Allow lists, the name tag and the admin group are enforced inside the transport, so no screen can skip them.
 */
interface WaTransport {
    val isPaired: Boolean
    val isConnected: Boolean
    fun pairWithPhone(number: String)
    fun connect()
    fun disconnect()
    fun logout()
    fun close()
    fun setAllowed(entries: String)
    fun setAllowAll(all: Boolean)
    fun setRejectCalls(on: Boolean)
    fun setLimits(perMinute: Long, perDay: Long)
    fun send(chat: String, text: String)
    fun markRead(chat: String, ids: String, senders: String, lastId: String, lastSender: String, lastFromMe: Boolean, lastTs: Long)
    fun resync()
    fun requestHistory(chat: String, msgId: String, ts: Long, fromMe: Boolean, count: Long)
    fun nameFor(id: String): String
    fun releaseHistory()
    /** Put in front of every message sent ("Tom: "); empty for none. */
    fun setTag(name: String)
    /** What the account's own messages in a group are called, instead of "Me". */
    fun setOwnName(name: String)
    /** The admin group (by id) that commands are read from, and the newest command already applied. Empty id switches commands off. */
    fun setAdmin(groupId: String, lastApplied: Long)
    fun sendAdmin(text: String)
    /** The joined groups, one "id|name" per line. */
    fun groups(): String

    // ---- richer messages (Family Chat). Every send below counts as one against the send limits, and a burst is refused whole.
    /** Download images, stickers and GIFs as they arrive (small ones only). */
    fun setMedia(on: Boolean)
    /** Fetches a video the child tapped (up to 100 MB) into the private media folder; returns the file path. */
    fun downloadMedia(ref: String, id: String, mime: String): String
    fun mediaDir(): String
    /** A text message with the child's name in front, optionally quoting another message. Returns its id. A reaction by the child is this, with the emoji as the text. */
    fun sendReply(chat: String, text: String, quoteId: String, quoteSender: String, quoteText: String): String
    /** Sticker, photo and video: each goes with the labelling [WaLabels] says (a "Sent by <child>" line before a sticker, "<child>: caption" on a photo or video). Returns the id of the media message. */
    fun sendSticker(chat: String, path: String, animated: Boolean): String
    fun sendPhoto(chat: String, path: String, caption: String, width: Int, height: Int): String
    fun sendVideo(chat: String, path: String, caption: String, seconds: Int, width: Int, height: Int): String
    /** Only messages this account sent can be edited or deleted for everyone (the bridge sends the keys as "from me"). */
    fun editMessage(chat: String, id: String, text: String)
    fun deleteMessage(chat: String, id: String)
}
