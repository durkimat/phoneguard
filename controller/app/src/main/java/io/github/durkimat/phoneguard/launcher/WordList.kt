package io.github.durkimat.phoneguard.launcher

/** What predictive text needs from a dictionary. Words are given most likely first. */
interface Predictor {
    /** Words whose letters spell exactly this digit sequence (2 to 9), most likely first. */
    fun exact(seq: String): List<String>
    /** Longer words that begin with this digit sequence, most likely first, at most [limit]. */
    fun longer(seq: String, limit: Int): List<String>
}

/**
 * A word list ordered most common first, looked up by the keypad digits that spell each word. The words
 * are kept sorted by digit sequence, so both lookups are a binary search plus a short scan.
 */
class WordList(private val words: List<String>) : Predictor {
    private val keys: List<String>
    private val index: IntArray

    init {
        val k = words.map { keyOf(it) }
        val order = words.indices.filter { k[it].isNotEmpty() }.sortedWith(compareBy({ k[it] }, { it }))
        keys = order.map { k[it] }
        index = order.toIntArray()
    }

    private fun lowerBound(seq: String): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (keys[mid] < seq) lo = mid + 1 else hi = mid }
        return lo
    }

    override fun exact(seq: String): List<String> {
        val out = ArrayList<String>()
        var i = lowerBound(seq)
        while (i < keys.size && keys[i] == seq) { out.add(words[index[i]]); i++ }
        return out
    }

    override fun longer(seq: String, limit: Int): List<String> {
        val found = ArrayList<Int>()
        var i = lowerBound(seq)
        var scanned = 0
        while (i < keys.size && keys[i].startsWith(seq) && scanned < 5000) {
            if (keys[i].length > seq.length) found.add(index[i])
            i++; scanned++
        }
        return found.sorted().take(limit).map { words[it] }
    }

    companion object {
        private const val PAD = "22233344455566677778889999"

        /** The digits that spell a word; apostrophes are skipped, and a word with anything else is not usable. */
        fun keyOf(word: String): String {
            val sb = StringBuilder()
            for (ch in word) {
                val c = ch.lowercaseChar()
                when {
                    c in 'a'..'z' -> sb.append(PAD[c - 'a'])
                    c == '\'' -> {}
                    else -> return ""
                }
            }
            return sb.toString()
        }

        fun parse(text: String): WordList = WordList(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
    }
}
