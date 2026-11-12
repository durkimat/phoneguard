package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.core.Config
import io.github.durkimat.phoneguard.core.ParentLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookTest {
    private val own = Look("green", "mono", "list")

    @Test fun withoutAParentTheOwnChoicesStand() { assertEquals(own, Look.resolve(own, null)) }

    @Test fun aParentOverridesOnlyWhatItSets() {
        val l = Look.resolve(own, ParentLook(theme = "mac", menuStyle = "grid"))
        assertEquals(Look("mac", "mono", "grid"), l)
        assertEquals(setOf("theme", "menu_style"), Look.locked(ParentLook(theme = "mac", menuStyle = "grid")))
    }

    @Test fun unknownNamesAreIgnoredAndNotCountedAsHeld() {
        val p = ParentLook(theme = "neon", font = "comic", menuStyle = "carousel")
        assertEquals(own, Look.resolve(own, p))
        assertEquals(emptySet<String>(), Look.locked(p))
    }

    @Test fun theThemeDecidesTheSkin() {
        assertEquals("nokia", Look("green", "mono", "list").skin)
        assertEquals("mac", Look("macdark", "mono", "list").skin)
        assertEquals("mac", Look.resolve(own, ParentLook(theme = "mac")).skin)
    }

    @Test fun anAccentReplacesTheThemesAndOtherThemesKeepTheirInk() {
        val red = 0xFF112233.toInt()
        assertEquals(red, Look("mac", "mono", "list", red).colours().accent)
        assertEquals(Themes.byId("green").ink, Themes.byId("green").accent)
        assertEquals(red, Look.resolve(own, ParentLook(accent = red)).accent)
    }

    @Test fun theLookArrivesFromHomeAssistantInTheConfig() {
        val c = Config.parse("""{"modes":{"day":{"name":"D"}},"look":{"theme":"macdark","menuStyle":"grid","accent":"#FF5A4A"}}""")
        assertEquals(ParentLook("macdark", null, "grid", 0xFFFF5A4A.toInt()), c.look)
    }

    @Test fun anEmptyOrBadLookIsNoLook() {
        assertNull(Config.parse("""{"modes":{"day":{"name":"D"}}}""").look)
        assertNull(Config.parse("""{"modes":{"day":{"name":"D"}},"look":{"theme":"","accent":"red"}}""").look)
    }
}
