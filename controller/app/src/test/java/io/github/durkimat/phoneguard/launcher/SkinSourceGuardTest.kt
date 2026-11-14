package io.github.durkimat.phoneguard.launcher

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skins only draw what the model holds. A preview override once stayed in the production path (the Now playing screen showed a fixed
 * demo song instead of the real track), so this reads the skins' source and fails if one of them ever rebuilds the music state with
 * hard-coded values again.
 */
class SkinSourceGuardTest {
    private fun skinFiles(): List<File> {
        val dir = File(System.getProperty("user.dir"), "src/main/java/io/github/durkimat/phoneguard/launcher")
        return dir.listFiles { f -> f.name.endsWith("Skin.kt") || f.name == "Chrome.kt" }?.toList().orEmpty()
    }

    @Test fun skinsAreFound() { assertTrue(skinFiles().size >= 2) }

    @Test fun noDemoMusicInTheSkins() {
        for (f in skinFiles()) {
            val t = f.readText()
            for (demo in listOf("Some Artist", "A Long Song Title", "The Album", "positionMs = 83000", "durationMs = 240000"))
                assertFalse("${f.name} contains the demo value '$demo'", t.contains(demo))
            assertFalse("${f.name} overrides the music state with copy(...)", Regex("""m\.music\.copy\(""").containsMatchIn(t))
        }
    }
}
