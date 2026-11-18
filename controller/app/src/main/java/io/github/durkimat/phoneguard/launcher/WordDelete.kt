package io.github.durkimat.phoneguard.launcher

/** Deleting a word at a time (the delete key held down): spaces before the cursor go, then the run of characters before them. */
object WordDelete {
    /** The text and the cursor after one word was deleted backwards from [cursor]. */
    fun back(text: String, cursor: Int): Pair<String, Int> {
        var i = cursor.coerceIn(0, text.length)
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return text.removeRange(i, cursor.coerceIn(0, text.length)) to i
    }

    /** How long the key is held before the first word goes, and the pause between the ones that follow. */
    const val REPEAT_MS = 170L
}
