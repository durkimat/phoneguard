package io.github.durkimat.media

/**
 * Where one thing the reader sees as a character ends, so that text with emoji is never cut, deleted or stepped through half way:
 * a surrogate pair, a flag (two regional letters), and anything joined on afterwards (skin tone, variation selector, accent,
 * keycap, tag letters, or a zero-width joiner and what follows it, as in a family). Our own rule rather than the platform's, so
 * that the unit tests see what the phone does.
 */
object Graphemes {
    private const val ZWJ = 0x200D

    private fun joinsOn(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || cp in 0xE0020..0xE007F ||
        Character.getType(cp).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }

    private fun regional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** The end of the character that starts at [i] (which must be a boundary). */
    fun next(s: String, i: Int): Int {
        if (i >= s.length) return s.length
        val first = s.codePointAt(i)
        var j = i + Character.charCount(first)
        if (first == '\n'.code) return j
        if (regional(first) && j < s.length && regional(s.codePointAt(j))) j += 2
        while (j < s.length) {
            val cp = s.codePointAt(j)
            if (cp == ZWJ) { j++; if (j < s.length) j += Character.charCount(s.codePointAt(j)) }
            else if (joinsOn(cp)) j += Character.charCount(cp)
            else break
        }
        return j
    }

    /** Every boundary of [s], from 0 to its length. */
    fun boundaries(s: String): List<Int> {
        val out = ArrayList<Int>(); out.add(0)
        var i = 0
        while (i < s.length) { i = next(s, i); out.add(i) }
        return out
    }

    /** The boundary before [i]: where Left or Delete goes from there. */
    fun previous(s: String, i: Int): Int = boundaries(s).lastOrNull { it < i } ?: 0

    /** The boundary after [i]: where Right goes from there. */
    fun after(s: String, i: Int): Int = boundaries(s).firstOrNull { it > i } ?: s.length

    /** [s] without its last character. */
    fun dropLast(s: String): String = s.substring(0, previous(s, s.length))

    /** At most [max] of the string's own length, cut between characters. */
    fun take(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, boundaries(s).last { it <= max })
}
