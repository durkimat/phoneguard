package io.github.durkimat.phoneguard.voice

/** Puts dictated text into a field at the cursor: one space between it and what is already there, nothing at the start of the field. Pure. */
object VoiceText {
    /** The new field text and the new cursor position. */
    fun insert(existing: String, cursor: Int, said: String): Pair<String, Int> {
        val add = said.trim().replace(Regex("\\s+"), " ")
        if (add.isEmpty()) return existing to cursor
        val at = cursor.coerceIn(0, existing.length)
        val before = existing.substring(0, at); val after = existing.substring(at)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val tail = if (after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,!?;:") " " else ""
        val text = before + lead + add + tail + after
        return text to (before.length + lead.length + add.length + tail.length).coerceAtMost(text.length)
    }
}
