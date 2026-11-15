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
        assertEquals(listOf("signal", "wifi", "bt", "battery", "data"), kinds)
        repeat(1) { m.onKey(Key.UP) }; repeat(4) { m.onKey(Key.DOWN) }
        assertEquals("data", m.homeFocusTarget()?.kind)
        assertEquals(Action.DataLoad, m.onKey(Key.OK)); assertEquals(Screen.DATA, m.screen)
    }

    @Test fun theDataPageListsUsedLeftAllowanceAndRenewal() {
        val m = model(); m.dataView = DataView(5_200_000_000L, 10_000_000_000L, 10); m.dataRenews = "12 Dec"; m.dataPlan = DataPlan(allowance = 10_000_000_000L)
        m.onKey(Key.OK); m.onKey(Key.OK); repeat(m.rows.indexOf("Mobile data")) { m.onKey(Key.DOWN) }
        assertEquals(Action.DataLoad, m.onKey(Key.OK))
        assertEquals(listOf("Used: 5.2 GB (52%)", "Left: 4.8 GB", "Allowance: 10 GB", "Renews: 12 Dec", "Change plan"), m.rows)
        repeat(4) { m.onKey(Key.DOWN) }; m.onKey(Key.OK)
        assertEquals(Screen.FORM, m.screen); assertEquals("Mobile data plan", m.title)
        assertTrue(m.rows[0].startsWith("Allowance GB: 10"))
    }

    @Test fun withoutAPlanThePageOffersToSetOneUp() {
        val m = model(); m.onKey(Key.OK); m.onKey(Key.OK); repeat(m.rows.indexOf("Mobile data")) { m.onKey(Key.DOWN) }; m.onKey(Key.OK)
        assertEquals(listOf("No data plan set", "Set up plan"), m.rows)
        m.dataPlan = DataPlan(allowance = 1); assertEquals("Change plan", m.rows[1])
    }

    @Test fun aModeCanHideTheWidget() {
        val on = Config.parse("""{"version":1,"modes":{"a":{"name":"A"}},"base":"a","segments":[],"windowEnd":0}""")
        val off = Config.parse("""{"version":1,"modes":{"a":{"name":"A","dataWidget":false}},"base":"a","segments":[],"windowEnd":0}""")
        assertTrue(on.modes["a"]!!.dataWidget); assertFalse(off.modes["a"]!!.dataWidget)
    }
}
