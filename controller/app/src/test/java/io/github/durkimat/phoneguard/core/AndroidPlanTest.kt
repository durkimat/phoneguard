package io.github.durkimat.phoneguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidPlanTest {
    @Test fun warningAndCycleDay() {
        val p = AndroidPlan.parse("warn=5000000000 limit=-1 day=14")!!
        assertEquals(5_000_000_000L, p.allowance); assertEquals(14, p.day)
    }
    @Test fun aLimitWinsOverTheWarning() = assertEquals(3_000_000_000L, AndroidPlan.parse("warn=5000000000 limit=3000000000 day=1")!!.allowance)
    @Test fun nothingSetOrUnreadableMeansNoAndroidPlan() {
        assertNull(AndroidPlan.parse(null)); assertNull(AndroidPlan.parse("none")); assertNull(AndroidPlan.parse("")); assertNull(AndroidPlan.parse("warn=-1 limit=-1 day=14")); assertNull(AndroidPlan.parse("rubbish"))
    }
    @Test fun dayIsKeptInRange() = assertEquals(31, AndroidPlan.parse("warn=1 limit=-1 day=45")!!.day)
}
