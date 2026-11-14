package io.github.durkimat.phoneguard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The no-pop-ups baseline (heads-up off) runs just before LockdownEnforcer.apply: apply() must never touch the heads-up setting, or it undoes the baseline in the same evaluation (0.3.12 bug). */
class LockdownSourceGuardTest {
    private val src = File(System.getProperty("user.dir"), "src/main/java/io/github/durkimat/phoneguard/Lockdown.kt").readText()

    private fun body(startMarker: String): String {
        val i = src.indexOf(startMarker); assertTrue("$startMarker not found", i >= 0)
        val j = src.indexOf("\n    }\n", i)
        return src.substring(i, j)
    }

    @Test fun applyDoesNotTouchHeadsUp() { assertFalse(body("fun apply(c: Context, ld: Lockdown").contains("HeadsUp.set")) }
    @Test fun baselineTurnsHeadsUpOffAndNeverBackOn() {
        val b = body("fun baseline(c: Context)")
        assertTrue(b.contains("HeadsUp.set(c, off = true)")); assertFalse(b.contains("off = false"))
    }
}
