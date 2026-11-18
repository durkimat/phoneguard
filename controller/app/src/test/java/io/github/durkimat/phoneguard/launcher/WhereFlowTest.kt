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
        assertEquals(3, m.rows.size)
        m.key(Key.OK)
        assertEquals(Screen.WHERELEGS, m.screen)
        assertEquals(2, m.rows.size)
        assertEquals("TIMETABLE times", m.title)
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WHEREJOURNEYS, m.screen)
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WHEREPLACES, m.screen)
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WRITE, m.screen)    // back to the typing, with the words still there
        m.key(Key.END); assertEquals(Screen.HOME, m.screen)
    }

    @Test fun laterTimesAndTryTomorrowAskAgain() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3); m.key(Key.OK)
        m.setWherePlaces(Where.parsePlaces("""{"places":[{"name":"Shop","kind":"place","area":"","lat":51.0,"lon":-0.3}]}"""), "")
        m.key(Key.OK); m.setWherePlan(Where.parsePlan(planJson), "home", "")
        m.key(Key.DOWN) // the walk
        m.key(Key.DOWN) // later times
        val a = m.key(Key.OK) as Action.WhereRoute
        assertEquals("2026-11-17T08:13:00Z", a.time)
        assertEquals("Finding the way...", m.whereStatus)
    }

    @Test fun aSpokenDestinationSearchesAtOnce() {
        val m = phone(); val a = m.searchWhere("Hexham Station")
        assertEquals(Screen.WHEREPLACES, m.screen); assertEquals(Action.WhereSearch("Hexham Station"), a)
    }

    @Test fun theMapStepsThroughTheLegsAndBackReturnsToTheList() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3); m.key(Key.OK)
        m.setWherePlaces(Where.parsePlaces("""{"places":[{"name":"Shop","kind":"place","area":"","lat":51.0,"lon":-0.3}]}"""), "")
        m.key(Key.OK); m.setWherePlan(Where.parsePlan(planJson), "home", ""); m.key(Key.OK)   // the legs of the first journey
        m.key(Key.DOWN); m.key(Key.OK)                                                          // the second leg, then the map
        assertEquals(Screen.WHEREMAP, m.screen)
        assertEquals(1, m.whereMapLeg)
        assertEquals("08:25 Bus 321 to Hatfield (25 min)", m.whereMapCaption)
        m.key(Key.LEFT); assertEquals(0, m.whereMapLeg)
        m.key(Key.LEFT); assertEquals(0, m.whereMapLeg)
        m.key(Key.RIGHT); m.key(Key.RIGHT); assertEquals(1, m.whereMapLeg) // stops at the last leg
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WHERELEGS, m.screen); assertEquals(1, m.selected)
    }

    @Test fun theWalkAloneShowsOneLeg() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3); m.key(Key.OK)
        m.setWherePlaces(Where.parsePlaces("""{"places":[{"name":"Shop","kind":"place","area":"","lat":51.0,"lon":-0.3}]}"""), "")
        m.key(Key.OK)
        m.setWherePlan(Where.parsePlan("""{"prefer":"walk","walk":{"minutes":12,"metres":900},"options":[]}"""), "my position", "")
        assertEquals(listOf("Walk  12m  900 m", "No bus or train: try tomorrow"), m.rows)
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

    @Test fun theMenuListsWhereToAfterGamesSoOtherDigitsStayPut() {
        val all = listOf(MenuItem(GAMES_PKG, "Games"), MenuItem(CALC_PKG, "Calculator"), MenuItem(WHERE_PKG, "Where to?"), MenuItem(SETTINGS_PKG, "Settings"), MenuItem(WEATHER_PKG, "Weather"))
        assertEquals(listOf("Weather", "Games", "Where to?", "Calculator", "Settings"), LauncherData.menu(all, emptyList()).map { it.label })
    }

    @Test fun theDemoJourneyShowsOnTheMapAndTheDotCanBeOffRoute() {
        val m = phone(); m.loadWhereDemo()
        assertEquals(Screen.WHEREMAP, m.screen); assertEquals(3, m.whereLegs.size)
        assertTrue(m.whereMapSegments.all { it.points.size >= 2 })
        m.setWhereFix(51.7525, -0.3340, 90f)              // on the route
        assertTrue(!m.whereOffRoute)
        m.setWhereFix(51.7700, -0.3340, 90f)              // about a kilometre north of it
        assertTrue(m.whereOffRoute)
        assertEquals("Off route: OK to re-plan", m.whereMapCaption)
    }

    @Test fun recentPlacesShowUnderTheSearchRowAndOkPicksOne() {
        val m = phone()
        m.whereRecentsLoader = { Where.parsePlaces("""{"places":[{"name":"Hexham Station","kind":"stop","area":"","lat":54.97,"lon":-2.1},{"name":"The Library","kind":"place","area":"St Albans","lat":51.75,"lon":-0.33}]}""") }
        m.key(Key.OK); m.key(Key.OK)        // Menu, Where to?
        assertEquals(Screen.WHEREPLACES, m.screen)
        assertEquals(listOf("Search for a place...", "Hexham Station", "The Library, St Albans"), m.rows)
        assertEquals(1, m.selected)         // the newest place is already highlighted
        assertTrue(m.key(Key.OK) is Action.WhereRoute)
        assertEquals("Hexham Station", m.wherePlace?.name)
    }

    @Test fun theSearchRowOpensTheSearchBoxAndNoRecentsGoesStraightThere() {
        val m = phone()
        m.whereRecentsLoader = { Where.parsePlaces("""{"places":[{"name":"Hexham Station","kind":"stop","area":"","lat":54.97,"lon":-2.1}]}""") }
        m.key(Key.OK); m.key(Key.OK)
        m.key(Key.UP)                       // up to "Search for a place..."
        assertEquals(0, m.selected)
        m.key(Key.OK)
        assertEquals(Screen.WRITE, m.screen)
        val n = phone()
        n.key(Key.OK); n.key(Key.OK)
        assertEquals(Screen.WRITE, n.screen)
    }

    @Test fun keysThreeAndNineZoomTheMapAndSixShowsItAllAgain() {
        val m = phone()
        m.loadWhereDemo()
        assertEquals(Screen.WHEREMAP, m.screen); assertEquals(0, m.whereZoom)
        m.key(Key.DIGIT, 3); m.key(Key.DIGIT, 3)
        assertEquals(2, m.whereZoom)
        repeat(10) { m.key(Key.DIGIT, 3) }
        assertEquals(io.github.durkimat.phoneguard.where.MapFit.ZOOM_IN, m.whereZoom)
        m.key(Key.DIGIT, 6); assertEquals(0, m.whereZoom)
        m.key(Key.DIGIT, 1); m.key(Key.DIGIT, 5); assertEquals(0, m.whereZoom) // the old keys do nothing now
        repeat(10) { m.key(Key.DIGIT, 9) }
        assertEquals(io.github.durkimat.phoneguard.where.MapFit.ZOOM_OUT, m.whereZoom)
        assertTrue(m.whereMapFit != null)
    }

    private val poiJson = """{"places":[
      {"name":"Marco's","kind":"poi","type":"Italian restaurant","info":{"hours":"Mo-Su 12:00-22:00","open":true,"until":"22:00","takeaway":"yes","diet":["vegetarian"],"wheelchair":"yes","phone":"01727 555 0142"},"lat":51.75,"lon":-0.33,"km":0.25},
      {"name":"","kind":"poi","type":"Cash machine","lat":51.751,"lon":-0.33,"km":0.4}],"category":"restaurant","radius":1000,"near":{"lat":51.75,"lon":-0.33}}"""

    @Test fun aPlaceFoundByKindOpensItsDetailThenTheWaysThereAndBackComesBackToTheDetail() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3); m.key(Key.OK)
        m.setWherePlaces(Where.parseAnswer(poiJson).places, "No places found", "from last known position")
        assertEquals(listOf("Marco's", "Cash machine"), m.rows); assertEquals(listOf("Italian restaurant", ""), m.rowSubs)   // an unnamed place is titled by its kind, with no second line
        assertEquals("250 m  from last known position", m.wherePlaceNote)
        val a = m.key(Key.OK)
        assertEquals(Screen.WHEREDETAIL, m.screen); assertTrue(a is Action.Redraw); assertEquals("Marco's", m.title)
        assertEquals(listOf("Italian restaurant", "Open until 22:00", "Mo-Su 12:00-22:00", "Takeaway, Vegetarian options", "Wheelchair: yes", "01727 555 0142", "250 m, about 3 min walk"), m.rows)
        assertEquals("Go", m.softKeys.first)
        val r = m.key(Key.OK); assertTrue(r is Action.WhereRoute); assertEquals(Screen.WHEREJOURNEYS, m.screen)
        m.setWherePlan(Where.parsePlan(planJson), "my position", "")
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WHEREDETAIL, m.screen)      // Back from the ways returns to the detail
        m.key(Key.SOFT_RIGHT); assertEquals(Screen.WHEREPLACES, m.screen); assertEquals(0, m.selected)
        m.key(Key.DOWN); m.key(Key.OK); assertEquals(Screen.WHEREDETAIL, m.screen); assertEquals("Cash machine", m.title)
        assertEquals(listOf("400 m, about 5 min walk"), m.rows.filter { it.contains("walk") })
        m.key(Key.END); assertEquals(Screen.HOME, m.screen)
    }

    @Test fun placesFromAnOldWorkerAndTheRecentPlacesStillGoStraightToTheWays() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3); m.key(Key.OK)
        m.setWherePlaces(Where.parseAnswer("""{"places":[{"name":"Hexham Station","kind":"stop","area":"Northumberland","lat":54.97,"lon":-2.1}]}""").places, "")
        assertTrue(m.key(Key.OK) is Action.WhereRoute); assertEquals(Screen.WHEREJOURNEYS, m.screen)
        val r = phone(); r.whereRecentsLoader = { Where.parsePlaces(poiJson).take(1) }; r.key(Key.OK); r.key(Key.OK)
        assertEquals(Screen.WHEREPLACES, r.screen); assertEquals(1, r.selected); assertTrue(r.key(Key.OK) is Action.WhereRoute)   // recents: row 0 is "Search for a place...", the rest go straight to the ways
    }

    @Test fun noPositionAsksForATownThenSearchesAroundIt() {
        val m = phone(); m.key(Key.OK); m.key(Key.OK); m.key(Key.DIGIT, 2); m.key(Key.DIGIT, 2); m.key(Key.OK)
        m.askWhereTown("cafe")
        assertEquals(Screen.WRITE, m.screen)
        m.key(Key.DIGIT, 4); m.key(Key.DIGIT, 3)
        val a = m.key(Key.OK)
        assertTrue(a is Action.WhereTown); assertEquals("cafe", (a as Action.WhereTown).query); assertEquals(2, a.town.length)
        assertEquals(Screen.WHEREPLACES, m.screen); assertEquals("Searching...", m.whereStatus)
        m.setWherePlaces(Where.parseAnswer(poiJson).places, "", "near Hatfield"); assertEquals("250 m  near Hatfield", m.wherePlaceNote)
    }
}
