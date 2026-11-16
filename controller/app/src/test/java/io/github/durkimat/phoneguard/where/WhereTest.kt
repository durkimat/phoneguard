package io.github.durkimat.phoneguard.where

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class WhereTest {
    private val utc = ZoneId.of("UTC")

    @Test fun placesParseAndShowTheAreaOnce() {
        val ps = Where.parsePlaces("""{"places":[{"name":"Hexham Station","kind":"stop","area":"Northumberland","lat":54.97,"lon":-2.1,"km":1.5},{"name":"bad"},{"name":"St Albans","kind":"place","area":"St Albans","lat":51.75,"lon":-0.34}]}""")
        assertEquals(2, ps.size)
        assertEquals("Hexham Station, Northumberland", Where.placeRow(ps[0]))
        assertEquals("St Albans", Where.placeRow(ps[1]))
        assertEquals(emptyList<Place>(), Where.parsePlaces("garbage"))
    }

    private val plan = """{"prefer":"transit","walk":{"minutes":120,"metres":9000,"geometry":{"points":"_p~iF~ps|U","precision":6}},"options":[
        {"minutes":53,"start":"2026-11-17T08:12:00Z","end":"2026-11-17T09:05:00Z","transfers":1,"live":true,"legs":[
          {"mode":"walk","from":{"name":"Home","time":"2026-11-17T08:12:00Z"},"to":{"name":"Stop A","time":"2026-11-17T08:20:00Z"},"minutes":8,"live":false},
          {"mode":"bus","line":"321","headsign":"Hatfield","from":{"name":"Stop A","time":"2026-11-17T08:25:00Z"},"to":{"name":"Stop B","time":"2026-11-17T08:50:00Z"},"minutes":25,"live":true}]},
        {"minutes":70,"start":"2026-11-17T08:40:00Z","end":"2026-11-17T09:50:00Z","transfers":0,"live":false,"legs":[]}]}"""

    @Test fun journeyRowsPutThePreferredKindFirst() {
        val p = Where.parsePlan(plan)
        val rows = Where.journeyRows(p, utc)
        assertEquals(listOf("08:12  53 min  1 change  LIVE", "08:40  70 min  direct", "Walk  120 min  9.0 km"), rows.map { it.label })
        assertTrue(rows.last().journey == null)
        val walkFirst = Where.journeyRows(p.copy(prefer = "walk", walkMinutes = 12, walkMetres = 900), utc)
        assertEquals("Walk  12 min  900 m", walkFirst.first().label)
        assertEquals(emptyList<JourneyRow>(), Where.journeyRows(Where.parsePlan("""{"prefer":"none","walk":null,"options":[]}"""), utc))
    }

    @Test fun legLinesAreShort() {
        val legs = Where.parsePlan(plan).options[0].legs
        assertEquals("Walk 8 min to Stop A", Where.legLine(legs[0], utc))
        assertEquals("08:25 Bus 321 to Hatfield (25 min) LIVE", Where.legLine(legs[1], utc))
        assertEquals("TIMETABLE times", Where.liveTag(Where.parsePlan(plan).options[1]))
    }

    @Test fun clockUsesTheGivenZone() {
        assertEquals("09:12", Where.clock("2026-11-17T08:12:00Z", ZoneId.of("Europe/Paris")))
        assertEquals("", Where.clock("soon"))
    }

    @Test fun polylineDecodes() {
        // Google's documented example at precision 5: (38.5,-120.2), (40.7,-120.95), (43.252,-126.453)
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].first, 1e-6); assertEquals(-120.2, pts[0].second, 1e-6)
        assertEquals(43.252, pts[2].first, 1e-6); assertEquals(-126.453, pts[2].second, 1e-6)
    }
}
