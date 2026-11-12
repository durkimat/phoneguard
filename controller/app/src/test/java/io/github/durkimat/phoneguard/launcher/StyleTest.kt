package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.core.Config
import io.github.durkimat.phoneguard.core.ParentLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StyleTest {
    private fun phone(theme: String = "mac") = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings"))).also { it.theme = theme }
    private fun appearance(m: LauncherModel) { m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 4) } // Menu, Settings, Appearance

    @Test fun everyPresetUsesOnlyChoicesThatExist() {
        for (p in Presets.all) {
            assertTrue("${p.id}: theme", Themes.all.any { it.id == p.theme })
            assertTrue("${p.id}: menu", MenuStyles.all.any { it.id == p.menuStyle })
            for (k in Choices.keys) p.style[k]?.let { assertTrue("${p.id}: $k=$it", Choices.valid(k, it)) }
        }
    }

    @Test fun everyTypefaceHasItsFileInTheApp() {
        val dir = File("src/main/assets/fonts")
        val missing = Fonts.all.mapNotNull { it.asset }.filter { !File(dir, it).isFile }
        assertEquals("font files missing from assets/fonts", emptyList<String>(), missing)
        assertTrue(File(dir, "OFL-NOTICE.txt").isFile)
    }

    @Test fun theAppearanceListOffersTheMacSettingsOnlyToTheMacLook() {
        val mac = phone(); appearance(mac)
        assertEquals(Screen.APPEARANCE, mac.screen)
        assertEquals(listOf("Presets", "Windows: Stripes (System 7)", "Colour: Mono", "Wallpaper: Dither", "Shadow: Hard", "Spectrum: Red peaks", "Clock font: Same as Font", "Text font: Same as Font", "Title font: Same as Font"), mac.rows)
        val nokia = phone("green"); appearance(nokia)
        assertEquals(listOf("Presets", "Spectrum: Red peaks", "Clock font: Same as Font", "Text font: Same as Font", "Title font: Same as Font"), nokia.rows)
    }

    @Test fun choosingASettingSavesItAndBackReturnsToTheList() {
        val m = phone(); appearance(m)
        m.onKey(Key.DIGIT, 2) // Windows
        assertEquals(Screen.CHOICE, m.screen); assertEquals("Windows", m.title)
        assertEquals(listOf("Stripes (System 7) *", "Platinum (OS 8)", "Flat"), m.rows)
        assertEquals(Action.SaveOption("windows", "flat"), m.onKey(Key.DIGIT, 3))
        assertEquals(Screen.APPEARANCE, m.screen); assertEquals(1, m.selected); assertEquals("flat", m.style.windows)
        assertEquals("Windows: Flat", m.rows[1])
    }

    @Test fun aFontRoleCanFollowTheFontOrTakeItsOwn() {
        val m = phone(); appearance(m)
        repeat(6) { m.onKey(Key.DOWN) } // Clock font
        m.onKey(Key.OK)
        assertEquals("Same as Font *", m.rows[0]); assertEquals(Fonts.all.size + 1, m.rows.size)
        val j10 = 1 + Fonts.all.indexOfFirst { it.id == "j10" }
                repeat(j10) { m.onKey(Key.DOWN) }
        assertEquals(Action.SaveOption("font_display", "j10"), m.onKey(Key.OK)); assertEquals("j10", m.style.fontDisplay)
        m.onKey(Key.OK);         repeat(m.selected) { m.onKey(Key.UP) }
        assertEquals(Action.SaveOption("font_display", ""), m.onKey(Key.OK)); assertEquals(null, m.style.fontDisplay)
    }

    @Test fun aPresetSetsTheThemeMenuAndEverythingElse() {
        val m = phone(); appearance(m)
        m.onKey(Key.OK) // Presets
        assertEquals(Presets.all.map { it.name }, m.rows)
        assertEquals(Action.SavePreset("terminal"), m.onKey(Key.DIGIT, 5))
        assertEquals("macdark", m.theme); assertEquals("flat", m.style.windows); assertEquals("green", m.style.wall); assertEquals("vt323", m.style.fontLabel)
    }

    @Test fun aParentCanHoldAnySettingAndTheRestStaysTheirs() {
        val own = Look("mac", "mono", "list")
        val parent = ParentLook(opts = mapOf("windows" to "platinum", "font_label" to "silk", "wall" to "lava"))
        val l = Look.resolve(own, parent)
        assertEquals("platinum", l.style.windows); assertEquals("silk", l.style.fontLabel); assertEquals("dither", l.style.wall) // "lava" is not a wallpaper
        assertEquals(setOf("windows", "font_label"), Look.locked(parent))
    }

    @Test fun theAppearanceSettingsArriveFromHomeAssistantInTheConfig() {
        val c = Config.parse("""{"modes":{"day":{"name":"D"}},"look":{"windows":"flat","fontLabel":"ps2p","bars":"grad"}}""")
        assertEquals(mapOf("windows" to "flat", "bars" to "grad", "font_label" to "ps2p"), c.look?.opts)
    }

    @Test fun theClimbingColourRunsFromTealThroughAmberToRed() {
        assertEquals(Palette.of(false).teal, Palette.climb(false, 0f)); assertEquals(Palette.of(false).amber, Palette.climb(false, .5f)); assertEquals(Palette.of(true).red, Palette.climb(true, 1f))
        assertFalse(Palette.climb(false, .25f) == Palette.climb(false, .75f))
    }
}
