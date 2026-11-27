package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.core.Config
import io.github.durkimat.phoneguard.core.DataPlan
import io.github.durkimat.phoneguard.core.DataView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataScreenTest {
    private fun model() = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")))

    @Test fun theWidgetIsAKeypadTargetOnlyWhenThereIsData() {
        val m = model()
        assertFalse(m.homeTargets().any { it.kind == "data" })
        m.dataView = DataView(5_200_000_000L, 10_000_000_000L, 10)
        val kinds = m.homeTargets().map { it.kind }
        assertEquals(listOf("signal", "wifi", "bt", "battery", "data", "clock"), kinds)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN) // the top zone, then the data zone below it
        assertEquals("data", m.homeFocusTarget()?.kind)
        assertEquals(Action.DataLoad, m.onKey(Key.OK)); assertEquals(Screen.DATA, m.screen)
    }

    @Test fun theDataPageListsUsedLeftAllowanceAndRenewal() {
        val m = model(); m.dataView = DataView(5_200_000_000L, 10_000_000_000L, 10); m.dataRenews = "12 Dec"; m.dataPlan = DataPlan(allowance = 10_000_000_000L)
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 3) // Menu > Settings > Connections
        assertEquals(listOf("Wi-Fi: On", "Bluetooth: On", "Mobile data: 4.8 GB"), m.rows)
        assertEquals(Action.DataLoad, m.onKey(Key.DIGIT, 3))
        assertEquals(listOf("Used: 5.2 GB (52%)", "Left: 4.8 GB", "Allowance: 10 GB", "Renews: 12 Dec", "Set by: this phone", "By app", "Change plan"), m.rows)
        repeat(6) { m.onKey(Key.DOWN) }; m.onKey(Key.OK)
        assertEquals(Screen.FORM, m.screen); assertEquals("Mobile data plan", m.title)
        assertTrue(m.rows[0].startsWith("Allowance GB: 10"))
    }

    @Test fun byAppOpensTheBreakdownAndBackReturnsToThePage() {
        val m = model(); m.dataView = DataView(5_200_000_000L, 10_000_000_000L, 10); m.dataRenews = "12 Dec"; m.dataPlan = DataPlan(allowance = 10_000_000_000L)
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 3); m.onKey(Key.DIGIT, 3)
        repeat(5) { m.onKey(Key.DOWN) }
        assertEquals(Action.DataByApp, m.onKey(Key.OK))
        assertEquals(Screen.DATAAPPS, m.screen); assertEquals("Data by app", m.title); assertEquals("Loading...", m.dataAppsStatus)
        m.setDataApps(io.github.durkimat.phoneguard.core.DataByApp.rows(listOf("Spotify" to 120_000_000L, "Mail" to 3_000_000L)))
        assertEquals(listOf("Spotify  120 MB", "Mail  3 MB"), m.rows)
        m.onKey(Key.SOFT_RIGHT)
        assertEquals(Screen.DATA, m.screen)
    }

    @Test fun withoutAPlanThePageOffersToSetOneUp() {
        val m = model(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 3); assertEquals("Mobile data", m.rows[2]); m.onKey(Key.DIGIT, 3)
        assertEquals(listOf("No data plan set", "Set up plan"), m.rows)
        m.dataPlan = DataPlan(allowance = 1); assertEquals("Change plan", m.rows[1])
    }

    @Test fun thePageSaysWhenAndroidSetsThePlanAndShowsACorrection() {
        val m = model(); m.dataView = DataView(0, 30_000_000_000L, 10); m.dataPlan = DataPlan(allowance = 30_000_000_000L, correctionMb = -500); m.dataFromAndroid = true
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 3); m.onKey(Key.DIGIT, 3)
        assertEquals(listOf("Set by: Android", "Correction: -500 MB"), m.rows.subList(4, 6))
        repeat(7) { m.onKey(Key.DOWN) }; m.onKey(Key.OK); assertEquals(Screen.FORM, m.screen)
    }

    @Test fun aModeCanHideTheWidget() {
        val on = Config.parse("""{"version":1,"modes":{"a":{"name":"A"}},"base":"a","segments":[],"windowEnd":0}""")
        val off = Config.parse("""{"version":1,"modes":{"a":{"name":"A","dataWidget":false}},"base":"a","segments":[],"windowEnd":0}""")
        assertTrue(on.modes["a"]!!.dataWidget); assertFalse(off.modes["a"]!!.dataWidget)
    }
}
