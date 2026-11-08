package io.github.durkimat.phoneguard.launcher

/**
 * How a sender's name is shortened in the one-line previews of a chat, where a full name can take the whole width.
 * Pure Kotlin, unit tested. The full name is still shown when a message is opened.
 */
object NameStyle {
    const val INITIALS_FL = "fl"   // first and last name: Karen Durkin -> KD
    const val INITIALS_ALL = "all" // every name: John Paul Smith -> JPS
    const val FIRST5 = "first5"    // the first name, up to five letters: Elizabeth -> Eliza
    const val FULL = "full"
    const val DEFAULT = INITIALS_FL

    val all = listOf(
        INITIALS_FL to "First+last initials",
        INITIALS_ALL to "All initials",
        FIRST5 to "First name, 5 letters",
        FULL to "Full name",
    )

    fun name(id: String?): String = all.firstOrNull { it.first == id }?.second ?: all.first().second

    fun short(name: String, style: String): String {
        val n = name.trim()
        if (n.isEmpty() || style == FULL) return n
        // a bare phone number: its last four digits are enough to tell people apart
        if (n.count { it.isDigit() } >= 7 && n.all { it.isDigit() || it in "+ -()" }) return "…" + n.filter { it.isDigit() }.takeLast(4)
        val words = n.split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }
        if (words.isEmpty()) return n.take(5)
        if (words.size == 1 || style == FIRST5) return firstLetters(words[0], 5)
        fun initial(w: String): String = w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: ""
        return when (style) {
            INITIALS_ALL -> words.joinToString("") { initial(it) }
            else -> initial(words.first()) + initial(words.last()) // INITIALS_FL and anything unknown
        }
    }

    /** The first [n] letters of a name, cutting between whole characters (names can start with an emoji). */
    private fun firstLetters(w: String, n: Int): String {
        val cps = w.codePoints().toArray()
        return String(cps, 0, minOf(n, cps.size))
    }
}
