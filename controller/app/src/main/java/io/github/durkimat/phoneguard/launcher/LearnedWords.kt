package io.github.durkimat.phoneguard.launcher

/** Strong swear words that are never learned or suggested (they can still be typed letter by letter). */
object Blocklist {
    private val words = setOf(
        "fuck", "fucking", "fucked", "fucker", "fuckin", "motherfucker", "fucks", "shit", "shitty", "bullshit", "shite", "bitch", "bitches",
        "bastard", "cunt", "cock", "cocks", "dick", "dicks", "pussy", "whore", "slut", "wanker", "twat", "prick", "piss", "pissed", "arse",
        "arsehole", "asshole", "ass", "nigger", "nigga", "fag", "faggot", "retard", "rape", "raped", "rapist", "sex", "sexy", "porn", "tits",
        "boobs", "horny", "cum", "wtf", "fml", "stfu", "lmfao", "jfc", "omfg", "ffs",
    )
    fun contains(w: String) = w.lowercase() in words
}

/**
 * The words the owner types, with how often. Messages sent are read for words; the ones typed often come first in
 * suggestions, and words the list does not know (names, nicknames, family words) can then be suggested too. Pure Kotlin;
 * [LearnedStore] keeps it on the phone. Nothing leaves the phone.
 */
class LearnedWords(initial: Map<String, Int> = emptyMap()) {
    private val counts = HashMap<String, Int>(initial)
    /** Words suggested from the start (for example first names from Contacts), never saved. */
    private val seeds = HashMap<String, Int>()
    var changed = false
        private set

    /** Reads a sent message and counts its words. A capital in the middle of a sentence means a name, kept capitalised. */
    fun learnText(text: String, isKnown: (String) -> Boolean = { true }): Int {
        var n = 0
        val re = Regex("[A-Za-z][A-Za-z']*[A-Za-z]|[A-Za-z]")
        for (m in re.findAll(text)) {
            val raw = m.value
            if (raw.length < 2 || raw.length > 20 || Blocklist.contains(raw)) continue
            val start = m.range.first
            val before = text.substring(0, start).trimEnd(' ', '\t')
            val sentenceStart = before.isEmpty() || before.last() in ".!?\n"
            val titleCase = raw.first().isUpperCase() && raw.drop(1).all { it.isLowerCase() || it == '\'' }
            // a capital mid-sentence means a name; at the start of a sentence it does too if the dictionary does not know the word
            var shown = if (titleCase && (!sentenceStart || !isKnown(raw.lowercase()))) raw else raw.lowercase()
            // a name already learned with its capital stays one entry when it starts a sentence
            val capitalised = shown.replaceFirstChar { it.uppercase() }
            if (shown != capitalised && counts.containsKey(capitalised)) shown = capitalised
            counts[shown] = (counts[shown] ?: 0) + 1
            n++
        }
        if (n > 0) { changed = true; prune() }
        return n
    }

    /** Suggests a word from the start (a contact's first name), as if it had been typed twice. */
    fun seed(word: String) { if (word.length in 2..20 && !Blocklist.contains(word) && word.all { it.isLetter() || it == '\'' }) seeds[word] = 2 }

    /** Learned words whose keypad digits are [key], most used first (a seed counts as two uses). */
    fun forKey(key: String): List<Pair<String, Int>> {
        val all = HashMap<String, Int>()
        for ((w, c) in counts) if (WordList.keyOf(w) == key) all[w] = c
        for ((w, c) in seeds) if (WordList.keyOf(w) == key) all[w] = maxOf(all[w] ?: 0, c)
        return all.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
    }

    fun forget() { counts.clear(); changed = true }
    fun snapshot(): Map<String, Int> = HashMap(counts)
    val size: Int get() = counts.size
    fun markSaved() { changed = false }

    /** Keeps the most used few thousand words so the file stays small. */
    private fun prune(max: Int = 3000) {
        if (counts.size <= max) return
        counts.entries.sortedBy { it.value }.take(counts.size - max).forEach { counts.remove(it.key) }
    }
}

/** The word list plus the owner's own words. */
class LearningPredictor(private val base: Predictor, private val learned: LearnedWords) : Predictor {
    override fun exact(seq: String): List<String> {
        val mine = learned.forKey(seq)
        val b = base.exact(seq)
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        fun add(w: String) { if (seen.add(w.lowercase())) out.add(w) }
        for ((w, c) in mine) if (c >= 2) add(w)        // words used a few times lead
        b.forEach { add(it) }                            // then the common words
        for ((w, c) in mine) if (c < 2) add(w)           // a word typed once is offered, but does not push out the usual ones
        return out
    }

    override fun longer(seq: String, limit: Int): List<String> = base.longer(seq, limit)
}
