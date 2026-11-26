package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.*
import org.junit.Test

class AboutPhoneTest {
    private fun about(info: AboutInfo?): LauncherModel {
        val m = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")))
        m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.DIGIT, 4) // Menu, Settings, This phone
        assertEquals("About phone", m.rows.last())
        repeat(m.rows.size - 1) { m.onKey(Key.DOWN) }
        assertEquals(Action.AboutLoad, m.onKey(Key.OK)); assertEquals(Screen.ABOUT, m.screen)
        m.showAbout(info)
        return m
    }

    @Test fun itShowsTheVersionTheLastCheckAndTheUpdate() {
        val m = about(AboutInfo("0.5.1", true, "14:02, worked", "Waiting to get 0.5.2: needs Wi-Fi"))
        assertEquals("About phone", m.title); assertEquals("Phone Guard 0.5.1", m.pageHead)
        assertEquals("Last check-in: 14:02, worked\n\nUpdate: Waiting to get 0.5.2: needs Wi-Fi", m.pageBody)
        assertEquals("Check now" to "Back", m.softKeys)
        assertTrue(AboutInfo("0.5.1", true).body.endsWith("Update: none waiting"))
    }

    @Test fun checkNowAsksOnceAndSaysItIsChecking() {
        val m = about(AboutInfo("0.5.1", true, "14:02, worked"))
        assertEquals(Action.CheckNow, m.onKey(Key.OK))
        assertTrue(m.pageBody.startsWith("Last check-in: checking now...")); assertEquals("" to "Back", m.softKeys)
        assertEquals(Action.None, m.onKey(Key.OK)); assertEquals(Action.None, m.onKey(Key.SOFT_LEFT)) // not twice over
        m.showAbout(AboutInfo("0.5.1", true, "14:09, worked", "Downloading 0.5.2: 40%"))
        assertEquals("Check now" to "Back", m.softKeys); assertEquals(Action.CheckNow, m.onKey(Key.SOFT_LEFT))
    }

    @Test fun aPhoneWithNoParentShowsItsVersionAndOffersNoCheck() {
        val m = about(AboutInfo("0.5.1", canCheck = false))
        assertEquals("" to "Back", m.softKeys); assertEquals(Action.None, m.onKey(Key.OK)); assertTrue(m.pageBody.contains("not managed"))
    }

    @Test fun backReturnsToThisPhoneOnTheSameRow() {
        val m = about(AboutInfo("0.5.1", true))
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.THISPHONE, m.screen); assertEquals("About phone", m.rows[m.selected])
    }

    @Test fun lateFactsDoNotDisturbAnotherScreen() {
        val m = about(AboutInfo("0.5.1", true)); m.onKey(Key.SOFT_RIGHT); val head = m.pageHead; val body = m.pageBody
        m.showAbout(AboutInfo("0.5.2", true, "15:00, worked")); assertEquals(head, m.pageHead); assertEquals(body, m.pageBody)
    }
}
