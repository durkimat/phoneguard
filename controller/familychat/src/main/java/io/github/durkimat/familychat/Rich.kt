package io.github.durkimat.familychat

import io.github.durkimat.wacore.WaMsg
import io.github.durkimat.wacore.WaReaction

/** The small rules of the richer chat, with no screen in them (so they are tested). */
object Rich {
    /** WhatsApp lets a message be edited for fifteen minutes. */
    const val EDIT_WINDOW_MS = 15 * 60 * 1000L
    const val MAX_VIDEO_SECONDS = 300
    const val MAX_VIDEO_BYTES = 100L * 1024 * 1024
    const val PHOTO_LONG_EDGE = 1600

    private val kinds = setOf("image", "sticker", "gif", "video")

    /** A bubble is at most this share of the width of the chat. */
    const val BUBBLE_MAX = 0.78f
    fun bubbleCap(offered: Int): Int = (offered * BUBBLE_MAX).toInt()

    /** Calls and the like that WhatsApp notes in a chat: shown as a small centred line, not a bubble. */
    fun isSystem(m: WaMsg) = m.kind == "text" && !m.deleted && (m.body.startsWith("[call") || m.body.startsWith("[missed call"))

    /** Only a text message this phone sent, recently, and not already deleted. */
    fun canEdit(m: WaMsg, now: Long) = m.mine && m.mid.isNotEmpty() && !m.deleted && m.kind == "text" && now - m.date <= EDIT_WINDOW_MS
    /** Only a message this phone sent (it has WhatsApp's id because we sent it), never anyone else's. */
    fun canDelete(m: WaMsg) = m.mine && m.mid.isNotEmpty() && !m.deleted

    /** The words of a message without the "[photo]" style marker the bridge puts in front of a caption. */
    fun caption(m: WaMsg): String {
        if (m.kind !in kinds) return m.body
        val t = m.body
        return if (t.startsWith("[")) t.substringAfter("] ", "").let { if (t.contains("]")) it else t } else t
    }

    /** A plain marker for a picture, sticker, GIF or video when pictures are switched off or the file is not there. */
    fun marker(m: WaMsg) = when (m.kind) { "image" -> "[photo]"; "sticker" -> "[sticker]"; "gif" -> "[GIF]"; "video" -> "[video]"; else -> "" }

    /** A short text of a message, for quoting it and for the chat list. */
    fun snippet(m: WaMsg): String {
        if (m.deleted) return "This message was deleted"
        val c = caption(m).trim()
        return when {
            m.kind in kinds && c.isNotEmpty() -> "${marker(m)} $c"
            m.kind in kinds -> marker(m)
            else -> c
        }.take(200)
    }

    /** Reactions grouped as emoji with a count, most used first (ties in the order first seen). */
    fun groupReactions(r: List<WaReaction>): List<Pair<String, Int>> {
        val order = LinkedHashMap<String, Int>()
        for (x in r) order[x.emoji] = (order[x.emoji] ?: 0) + 1
        return order.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    fun duration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** The size a picture is scaled to: the long edge becomes [max] (never enlarged), the ratio kept. */
    fun fit(w: Int, h: Int, max: Int = PHOTO_LONG_EDGE): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= max || w <= 0 || h <= 0) return w to h
        val f = max.toDouble() / long
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }

    /** What to tell the child when a video cannot be sent, or null when it can. */
    fun videoRefusal(seconds: Int, bytes: Long): String? = when {
        seconds > MAX_VIDEO_SECONDS -> "That video is longer than 5 minutes."
        bytes > MAX_VIDEO_BYTES -> "That video is too big to send."
        else -> null
    }

    /** What WhatsApp's own limits say about a send error, in words for a child. */
    fun sendError(e: Throwable): String = when {
        e.message?.contains("too fast", true) == true -> "Sending too fast. Try again in a minute."
        e.message?.contains("not allowed", true) == true -> "This chat is not available."
        else -> "Could not send. Try again."
    }

    /** How a video is scaled for sending: by its short side to 720 px (never enlarged). Returns null for "leave it", else (true = set the width, size). [rotation] is the stored turn (90/270 swap the sides). */
    fun videoScale(w: Int, h: Int, rotation: Int): Pair<Boolean, Int>? {
        val dw = if (rotation % 180 != 0) h else w; val dh = if (rotation % 180 != 0) w else h
        if (dw <= 0 || dh <= 0 || minOf(dw, dh) <= 720) return null
        return (dh >= dw) to 720 // portrait: fix the width; landscape: fix the height
    }
}
