package io.github.durkimat.phoneguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeLevelTest {
    @Test fun theLowestSoundIsOneBlockAndTheHighestIsAll() {
        for (max in listOf(5, 7, 15, 100)) { assertEquals(1, VolumeLevel.blocks(1, max)); assertEquals(10, VolumeLevel.blocks(max, max)) }
    }

    @Test fun zeroOnlyWhenOffAndNeverBackwards() {
        assertEquals(0, VolumeLevel.blocks(0, 7)); assertEquals(0, VolumeLevel.blocks(3, 7, muted = true))
        for (max in listOf(5, 7, 15)) {
            val steps = (1..max).map { VolumeLevel.blocks(it, max) }
            assertTrue("$max: $steps", steps.zipWithNext().all { it.first <= it.second })
        }
    }

    @Test fun sevenLevelsAreSevenDifferentSteps() {
        assertEquals(listOf(1, 3, 4, 6, 7, 9, 10), (1..7).map { VolumeLevel.blocks(it, 7) })
    }
}
