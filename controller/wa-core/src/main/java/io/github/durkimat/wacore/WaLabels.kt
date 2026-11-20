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

    fun sticker(child: String, path: String, animated: Boolean): List<Step> =
        (if (child.isBlank()) emptyList() else listOf(Step.Text(senderLine(child)))) + Step.Sticker(path, animated)

    fun photo(child: String, path: String, text: String, w: Int, h: Int): List<Step> = listOf(Step.Photo(path, caption(child, text), w, h))
    fun video(child: String, path: String, text: String, seconds: Int, w: Int, h: Int): List<Step> = listOf(Step.Video(path, caption(child, text), seconds, w, h))
}
