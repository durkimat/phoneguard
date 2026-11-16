package io.github.durkimat.phoneguard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DataByAppTest {
    @Test fun biggestFirstAndTheRestIsOther() {
        val u = listOf("A" to 5_000_000L, "B" to 90_000_000L, "C" to 1_000_000L, "A" to 20_000_000L, "D" to 0L, "E" to 2_000_000L)
        val r = DataByApp.rows(u, top = 3)
        assertEquals(3 + 1, r.size)
        assertEquals("B  " + DataView.amount(90_000_000L), r[0])
        assertEquals("A  " + DataView.amount(25_000_000L), r[1])
        assertEquals("E  " + DataView.amount(2_000_000L), r[2])
        assertEquals("Other  " + DataView.amount(1_000_000L), r[3])
    }

    @Test fun nothingUsedIsAnEmptyList() { assertEquals(emptyList<String>(), DataByApp.rows(listOf("A" to 0L))) }
}
