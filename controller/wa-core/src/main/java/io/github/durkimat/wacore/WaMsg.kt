package io.github.durkimat.wacore

/** One message as a chat screen shows it: [sender] is who wrote it in a group (empty otherwise); the text does not repeat it. [date] is in milliseconds. */
data class WaMsg(
    val body: String, val date: Long, val mine: Boolean, val sender: String = "",
    // beyond the text (Family Chat shows these; Phone Guard's screens ignore them)
    val mid: String = "", val kind: String = "text", val mediaPath: String = "", val mediaRef: String = "", val mediaSize: Long = 0, val mime: String = "",
    val seconds: Int = 0, val width: Int = 0, val height: Int = 0, val animated: Boolean = false,
    val quoteId: String = "", val quoteText: String = "", val quoteSender: String = "",
    val edited: Boolean = false, val deleted: Boolean = false, val reactions: List<WaReaction> = emptyList(), val senderId: String = "",
)

/** One person's reaction to a message. */
data class WaReaction(val senderId: String, val sender: String, val emoji: String)

/** What a message carries beyond its text, as the store keeps it. */
data class WaExtra(
    val kind: String = "text", val mediaPath: String = "", val mediaRef: String = "", val mediaSize: Long = 0, val mime: String = "",
    val seconds: Int = 0, val width: Int = 0, val height: Int = 0, val animated: Boolean = false,
    val quoteId: String = "", val quoteText: String = "", val quoteSender: String = "",
)

/** One message as the transport delivers it (the bridge's view). */
data class WaMessage(
    val chat: String, val chatName: String, val sender: String, val senderId: String, val id: String, val text: String,
    val ts: Long, val fromMe: Boolean, val history: Boolean,
    val extra: WaExtra = WaExtra(),
)
