package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NameStyleTest {
    private fun s(n: String, st: String) = NameStyle.short(n, st)

    @Test fun firstAndLastInitials() {
        assertEquals("KD", s("Kevin Dunne", NameStyle.INITIALS_FL))
        assertEquals("JS", s("John Paul Smith", NameStyle.INITIALS_FL)) // many names: the first and the last
        assertEquals("AB", s("  anna   van der berg ".replace("van der ", ""), NameStyle.INITIALS_FL))
    }

    @Test fun allInitials() { assertEquals("JPS", s("John Paul Smith", NameStyle.INITIALS_ALL)); assertEquals("KD", s("Kevin Dunne", NameStyle.INITIALS_ALL)) }

    @Test fun firstNameFiveLetters() {
        assertEquals("Eliza", s("Elizabeth Taylor", NameStyle.FIRST5)); assertEquals("Kevin", s("Kevin Dunne", NameStyle.FIRST5)); assertEquals("Sam", s("Sam Jones", NameStyle.FIRST5))
    }

    @Test fun fullNameIsLeftAlone() { assertEquals("Kevin Dunne", s("  Kevin Dunne ", NameStyle.FULL)) }

    @Test fun oneWordNamesStayReadableInEveryStyle() {
        for (st in listOf(NameStyle.INITIALS_FL, NameStyle.INITIALS_ALL, NameStyle.FIRST5)) { assertEquals("Mum", s("Mum", st)); assertEquals("Elena", s("Elenaaaa", st)) }
    }

    @Test fun aBareNumberShowsItsLastFourDigits() {
        assertEquals("…0123", s("447700900123", NameStyle.INITIALS_FL)); assertEquals("…0123", s("447700900123", NameStyle.FIRST5)); assertEquals("447700900123", s("447700900123", NameStyle.FULL))
    }

    @Test fun emojiAndOddNamesDoNotBreak() {
        assertEquals("🔥Fire", s("🔥Fire Dept", NameStyle.FIRST5).take(6).let { it }) // cut between whole characters
        assertEquals("JL", s("John 🔥 Lee", NameStyle.INITIALS_FL)) // a word with no letters is skipped
        assertEquals("🔥", s("🔥", NameStyle.INITIALS_FL))
        assertEquals("", s("   ", NameStyle.INITIALS_FL))
    }

    @Test fun styleNames() { assertEquals("First+last initials", NameStyle.name(null)); assertEquals("Full name", NameStyle.name("full")) }
}
