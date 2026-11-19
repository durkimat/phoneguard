package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollClockTest {
    @Test fun nothingScrollsWithoutAnOverflow() { assertEquals(0f, ScrollClock.offset(0f, 12345L), 0f) }

    @Test fun restsTravelsRestsAndComesBack() {
        val travel = 180f // 2 s of travel at 90 units a second
        assertEquals(0f, ScrollClock.offset(travel, 0L), 0f)                       // rest at the start
        assertEquals(0f, ScrollClock.offset(travel, 1_499L), 0f)
        assertEquals(90f, ScrollClock.offset(travel, 1_500L + 1_000L), 0.5f)       // half way
        assertEquals(travel, ScrollClock.offset(travel, 1_500L + 2_000L + 500L), 0f) // rest at the end
        assertEquals(90f, ScrollClock.offset(travel, 1_500L + 2_000L + 1_500L + 1_000L), 0.5f) // coming back
        assertEquals(0f, ScrollClock.offset(travel, 2 * (1_500L + 2_000L) + 100L), 0f) // and round again
    }

    @Test fun twoLinesOnTheSameClockMoveTogether() {
        // the shared travel is the longer overflow: both lines get the same offset at every moment
        val travel = 250f
        for (t in 0L..10_000L step 250L) assertTrue(ScrollClock.offset(travel, t) in 0f..travel)
        assertEquals(ScrollClock.offset(travel, 4_321L), ScrollClock.offset(travel, 4_321L), 0f)
    }
}
