package io.github.durkimat.phoneguard.where

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneAndRecentTest {
    @Test fun whiteIsPaperAndDarkIsInkyButNeverBlack() {
        val ink = Tone.ink(intArrayOf(0xFFFFFFFF.toInt(), 0xFFF2EFE9.toInt(), 0xFFAAD3DF.toInt(), 0xFF000000.toInt()))
        assertEquals(0, ink[0].toInt()); assertTrue(ink[1] > ink[0]); assertTrue(ink[2] > ink[1]); assertTrue(ink[3] > ink[2])
        assertTrue((ink[3].toInt() and 255) < 128) // the route in full ink stays darker than any tile tone
    }

    private fun p(n: String, lat: Double = 51.0, lon: Double = -0.3, area: String = "") = Place(n, "place", area, lat, lon, 1.0)

    @Test fun newestFirstNoDuplicatesAtMostTen() {
        var l = emptyList<Place>()
        for (i in 1..12) l = RecentPlaces.add(l, p("Place $i", lat = 51.0 + i * 0.01))
        assertEquals(10, l.size); assertEquals("Place 12", l[0].name); assertEquals("Place 3", l.last().name)
        l = RecentPlaces.add(l, p("place 5", lat = 51.05)) // the same place again moves to the front
        assertEquals(10, l.size); assertEquals("place 5", l[0].name); assertEquals(1, l.count { it.name.equals("Place 5", true) })
        l = RecentPlaces.add(l, p("Another name", lat = 51.12)) // the same spot under another name counts as the same place
        assertEquals("Another name", l[0].name); assertEquals(1, l.count { Math.abs(it.lat - 51.12) < 1e-4 })
    }

    @Test fun savedAndReadBack() {
        val l = RecentPlaces.add(emptyList(), p("Hexham Station", 54.97, -2.1, "Northumberland"))
        val back = RecentPlaces.decode(RecentPlaces.encode(l))
        assertEquals("Hexham Station", back[0].name); assertEquals("Northumberland", back[0].area); assertEquals(54.97, back[0].lat, 1e-9)
        assertEquals(emptyList<Place>(), RecentPlaces.decode("not json"))
    }

    @Test fun aRecentKeepsItsTypeAndOldSavedOnesStillRead() {
        val l = RecentPlaces.add(emptyList(), Place("Marco's", "poi", "", 51.75, -0.33, 0.2, "Italian restaurant", PlaceInfo(hours = "x")))
        val back = RecentPlaces.decode(RecentPlaces.encode(l))
        assertEquals("Italian restaurant", back[0].type); assertEquals(null, back[0].info)
        assertEquals("", RecentPlaces.decode("""[{"name":"Old","kind":"stop","area":"","lat":1.0,"lon":2.0}]""")[0].type)
    }
}
