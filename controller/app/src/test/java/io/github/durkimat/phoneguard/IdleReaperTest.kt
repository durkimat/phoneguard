package io.github.durkimat.phoneguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleReaperTest {
    private val min = 60_000L

    @Test fun anAppIsIdleOnlyAfterTheWholeIdleTime() {
        val now = 100 * min
        assertFalse(IdleReaper.idle(now, now - 9 * min, 0, 10))
        assertTrue(IdleReaper.idle(now, now - 10 * min, 0, 10))
        assertFalse(IdleReaper.idle(now, 0, now - 2 * min, 10)) // music played a moment ago counts as use
        assertFalse(IdleReaper.idle(now, 0, 0, 0)) // 0 means never close anything
    }
}
