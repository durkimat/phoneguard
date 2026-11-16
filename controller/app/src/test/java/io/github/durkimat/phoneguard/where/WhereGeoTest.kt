package io.github.durkimat.phoneguard.where

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhereGeoTest {
    // a line running east along latitude 51.0 from lon -0.30 to -0.28 (about 1.4 km)
    private val line = listOf(51.0 to -0.30, 51.0 to -0.28)

    @Test fun aPointOnTheLineIsZeroMetresAway() { assertEquals(0.0, WhereGeo.metresToLine(51.0, -0.29, line)!!, 1.0) }

    @Test fun aPointBesideTheLineIsMeasuredSideways() {
        // 0.0009 degrees of latitude is about 99 m
        assertEquals(99.5, WhereGeo.metresToLine(51.0009, -0.29, line)!!, 2.0)
    }

    @Test fun pastTheEndItIsTheDistanceToTheEnd() {
        val d = WhereGeo.metresToLine(51.0, -0.27, line)!! // 0.01 degrees of longitude beyond the end, about 700 m
        assertTrue(d in 650.0..750.0)
    }

    @Test fun offRouteBeyondAHundredMetres() {
        assertFalse(WhereGeo.offRoute(51.0005, -0.29, line)) // about 55 m
        assertTrue(WhereGeo.offRoute(51.0015, -0.29, line))  // about 166 m
    }

    @Test fun noLineMeansNoDistance() { assertNull(WhereGeo.metresToLine(51.0, -0.3, emptyList())) }
}
