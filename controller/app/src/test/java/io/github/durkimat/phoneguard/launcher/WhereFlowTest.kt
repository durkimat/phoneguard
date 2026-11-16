package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.where.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Where to? screens, walked by key with the Worker's answers put in by hand. */
class WhereFlowTest {
    private val items = listOf(MenuItem(WHERE_PKG, "Where to?"), MenuItem(SETTINGS_PKG, "Settings"))
    private fun phone() = LauncherModel(items, contactsApp = "c", messagesApp = "m", dialerApp = "d")
    private fun LauncherModel.key(k: Key, d: Int = -1) = onKey(k, d, 0L)

    private val planJson = """{"prefer":"transit","walk":{"minutes":90,"metres":7000},"options":[
      {"minutes":53,"start":"2026-11-17T08:12:00Z","end":"2026-11-17T09:05:00Z","transfers":1,"live":false,"legs":[
        {"mode":"walk","from":{"name":"Home"},"to":{"name":"Stop A"},"minutes":8},
        {"mode":"bus","line":"321","headsign":"Hatfield","from":{"name":"Stop A","time":"2026-11-17T08:25:00Z"},"to":{"name":"Stop B"},"minutes":25}]}]}"""

    @Test fun typeSearchPickJourneyAndLegsAndBackOut() {
        val m = phone()
        m.key(Key.OK)                       // Menu
        m.key(Key.OK)                       // Where to?
        assertEquals(Screen.WRITE, m.screen)
        m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3)          // some letters
        val a = m.key(Key.OK)
        assertEquals(Screen.WHEREPLACES, m.screen)
        assertTrue(a is Action.WhereSearch)
        m.setWherePlaces(Where.parsePlaces("""{"places":[{"name":"Hexham Station","kind":"stop","area":"Northumberland","lat":54.97,"lon":-2.1}]}"""), "No places found")
        assertEquals(listOf("Hexham Station, Northumberland"), m.rows)
        val r = m.key(Key.OK)
        assertEquals(Screen.WHEREJOURNEYS, m.screen)
        assertTrue(r is Action.WhereRoute)
        assertEquals("Finding the way...", m.whereStatus)
        m.setWherePlan(Where.parsePlan(planJson), "home", "")
        assertEquals("From home to Hexham Station", m.title)
        assertEquals(2, m.rows.size)
        m.key(Key.OK)
        assertEquals(Screen.WHERELEGS, m.screen)
        assertEquals(2, m.rows.size)
        assertEquals("TIMETABLE times", m.title)
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WHEREJOURNEYS, m.screen)
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WHEREPLACES, m.screen)
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WRITE, m.screen)    // back to the typing, with the words still there
        m.key(Key.END); assertEquals(Screen.HOME, m.screen)
    }

    @Test fun theWalkAloneShowsOneLeg() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3); m.key(Key.OK)
        m.setWherePlaces(Where.parsePlaces("""{"places":[{"name":"Shop","kind":"place","area":"","lat":51.0,"lon":-0.3}]}"""), "")
        m.key(Key.OK)
        m.setWherePlan(Where.parsePlan("""{"prefer":"walk","walk":{"minutes":12,"metres":900},"options":[]}"""), "my position", "")
        assertEquals(listOf("Walk  12 min  900 m"), m.rows)
        m.key(Key.OK)
        assertEquals(listOf("Walk 12 min to Shop"), m.rows)
    }

    @Test fun noPlacesAndNoWayShowAReason() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3); m.key(Key.OK)
        m.setWherePlaces(emptyList(), "No places found")
        assertEquals("No places found", m.whereStatus)
        assertTrue(m.rows.isEmpty())
    }

    @Test fun aShortSearchIsRefused() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 4)
        m.key(Key.OK)
        assertEquals(Screen.WRITE, m.screen)
    }
}
