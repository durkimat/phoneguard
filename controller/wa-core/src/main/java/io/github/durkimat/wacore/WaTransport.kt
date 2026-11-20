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
}
