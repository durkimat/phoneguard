package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NameStyleTest {
    private fun s(n: String, st: String) = NameStyle.short(n, st)

    @Test fun firstAndLastInitials() {
        assertEquals("KD", s("Karen Durkin", NameStyle.INITIALS_FL))
        assertEquals("JS", s("John Paul Smith", NameStyle.INITIALS_FL)) // many names: the first and the last
        assertEquals("AB", s("  anna   van der berg ".replace("van der ", ""), NameStyle.INITIALS_FL))
    }

    @Test fun allInitials() { assertEquals("JPS", s("John Paul Smith", NameStyle.INITIALS_ALL)); assertEquals("KD", s("Karen Durkin", NameStyle.INITIALS_ALL)) }

    @Test fun firstNameFiveLetters() {
        assertEquals("Eliza", s("Elizabeth Taylor", NameStyle.FIRST5)); assertEquals("Karen", s("Karen Durkin", NameStyle.FIRST5)); assertEquals("Sam", s("Sam Jones", NameStyle.FIRST5))
    }

    @Test fun fullNameIsLeftAlone() { assertEquals("Karen Durkin", s("  Karen Durkin ", NameStyle.FULL)) }

    @Test fun oneWordNamesStayReadableInEveryStyle() {
        for (st in listOf(NameStyle.INITIALS_FL, NameStyle.INITIALS_ALL, NameStyle.FIRST5)) { assertEquals("Mum", s("Mum", st)); assertEquals("Elena", s("Elenaaaa", st)) }
    }

    @Test fun aBareNumberShowsItsLastFourDigits() {
        assertEquals("…0768", s("447855260768", NameStyle.INITIALS_FL)); assertEquals("…0768", s("+44 7855 260768", NameStyle.FIRST5)); assertEquals("447855260768", s("447855260768", NameStyle.FULL))
    }

    @Test fun emojiAndOddNamesDoNotBreak() {
        assertEquals("🔥Fire", s("🔥Fire Dept", NameStyle.FIRST5).take(6).let { it }) // cut between whole characters
        assertEquals("JL", s("John 🔥 Lee", NameStyle.INITIALS_FL)) // a word with no letters is skipped
        assertEquals("🔥", s("🔥", NameStyle.INITIALS_FL))
        assertEquals("", s("   ", NameStyle.INITIALS_FL))
    }

    @Test fun styleNames() { assertEquals("First+last initials", NameStyle.name(null)); assertEquals("Full name", NameStyle.name("full")) }
}
