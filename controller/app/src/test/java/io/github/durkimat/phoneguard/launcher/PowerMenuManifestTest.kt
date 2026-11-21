package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The image's window manager starts the power menu with an IMPLICIT intent (action only); that only resolves if the filter has the DEFAULT category (0.4.78 to 0.4.82 lacked it: start result -91). */
class PowerMenuManifestTest {
    @Test fun thePowerMenuFilterHasTheActionAndTheDefaultCategory() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        val start = xml.indexOf("android:name=\".launcher.PowerMenuActivity\"")
        assertTrue("PowerMenuActivity is not declared", start > 0)
        val block = xml.substring(start, xml.indexOf("</activity>", start))
        assertTrue(block.contains("io.github.durkimat.phoneguard.POWER_MENU"))
        assertTrue("the filter needs android.intent.category.DEFAULT", block.contains("android.intent.category.DEFAULT"))
        assertTrue("it must stay unexported (the system can still start it)", block.contains("android:exported=\"false\""))
    }
}
