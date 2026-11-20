package io.github.durkimat.wacore

/** One message as a chat screen shows it: [sender] is who wrote it in a group (empty otherwise); the text does not repeat it. [date] is in milliseconds. */
data class WaMsg(val body: String, val date: Long, val mine: Boolean, val sender: String = "")

/** One message as the transport delivers it (the bridge's view). */
data class WaMessage(
    val chat: String, val chatName: String, val sender: String, val senderId: String, val id: String, val text: String,
    val ts: Long, val fromMe: Boolean, val history: Boolean,
)
