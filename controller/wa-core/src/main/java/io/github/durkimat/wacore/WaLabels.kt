package io.github.durkimat.wacore

/**
 * The labelling rule, in one place so it cannot be skipped: anything that cannot carry the child's name in its own text is preceded, in the
 * same burst and in order, by a short "Sent by <child>" text message. A photo or video carries "<child>: caption" in its caption (with an empty
 * caption, "<child>:") and needs no extra line. Text messages and reactions get the name from the bridge's tag. Every step counts as one send.
 * With no child name set (Phone Guard without a tag) nothing is added.
 */
object WaLabels {
    sealed class Step {
        data class Text(val text: String) : Step()
        data class Sticker(val path: String, val animated: Boolean) : Step()
        data class Photo(val path: String, val caption: String, val width: Int, val height: Int) : Step()
        data class Video(val path: String, val caption: String, val seconds: Int, val width: Int, val height: Int) : Step()
    }

    fun senderLine(child: String) = "Sent by ${child.trim()}"

    /** The caption a photo or video really gets: "<child>: text", or "<child>:" when the text is empty. */
    fun caption(child: String, text: String): String {
        val c = child.trim(); val t = text.trim()
        return if (c.isEmpty()) t else if (t.isEmpty()) "$c:" else "$c: $t"
    }

    /** A sticker is preceded by the "Sent by" line, unless [label] is false (see [needsLabel]). */
    fun sticker(child: String, path: String, animated: Boolean, label: Boolean = true): List<Step> =
        (if (child.isBlank() || !label) emptyList() else listOf(Step.Text(senderLine(child)))) + Step.Sticker(path, animated)

    /** Within this long of the child's last sticker, in the same chat, a following sticker needs no new "Sent by" line. */
    const val SAME_BURST_MS = 2 * 60 * 1000L

    /**
     * Whether a sticker needs its "Sent by <child>" line: yes, unless the very last thing in this chat is a sticker the child sent less than two
     * minutes ago, and no reaction has arrived since either. [last] is the newest message of the chat (any sender; null for an empty chat),
     * [lastReactionMs] the time of the newest reaction in it (0 for none). Anything else, or another chat, gets the label.
     */
    fun needsLabel(last: WaMsg?, lastReactionMs: Long, nowMs: Long): Boolean {
        if (last == null || !last.mine || last.kind != "sticker" || last.deleted) return true
        if (nowMs - last.date >= SAME_BURST_MS || nowMs < last.date) return true
        return lastReactionMs > last.date
    }

    fun photo(child: String, path: String, text: String, w: Int, h: Int): List<Step> = listOf(Step.Photo(path, caption(child, text), w, h))
    fun video(child: String, path: String, text: String, seconds: Int, w: Int, h: Int): List<Step> = listOf(Step.Video(path, caption(child, text), seconds, w, h))
}
