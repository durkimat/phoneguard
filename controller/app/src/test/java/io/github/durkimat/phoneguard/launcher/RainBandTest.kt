package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainBandTest {
    private fun hours(vararg prob: Int) = prob.mapIndexed { i, p -> WeatherHour("%02d:00".format(12 + i), 14, 3, 0.0, p) }
    private val days = listOf(WeatherDay("Sun", 3, 16, 8))
    private fun w(code: Int = 3, h: List<WeatherHour>, d: List<WeatherDay> = days) = WeatherData(14, code, true, h, d, 0L)

    @Test fun rainIsDueAtTheFirstHourWithThirtyPercentOrMore() {
        val b = w(h = hours(0, 10, 20, 30, 80, 60, 10)).rainBand()!!
        assertTrue(b.due); assertEquals("15:00", b.line1); assertEquals(WeatherIcon.RAIN, b.icon)
        assertEquals("80% chance", b.line2) // the highest of the three hours from 15:00 (30, 80, 60)
    }

    @Test fun theChanceIsTheMaximumWithinThreeHoursFromTheFirstHour() {
        val b = w(h = hours(0, 0, 40, 10, 10, 90, 0)).rainBand()!!
        assertEquals("14:00", b.line1); assertEquals("40% chance", b.line2) // the 90% hour is outside the three
    }

    @Test fun onlyTheNextSixHoursCount() {
        assertFalse(w(h = hours(0, 0, 0, 0, 0, 0, 0, 90)).rainBand()!!.due) // the 8th entry is the 7th hour ahead
        assertTrue(w(h = hours(0, 0, 0, 0, 0, 0, 30)).rainBand()!!.due)
    }

    @Test fun rainingNowSaysNow() {
        val b = w(code = 61, h = hours(50, 20, 10)).rainBand()!!
        assertTrue(b.due); assertEquals("Now", b.line1); assertEquals("50% chance", b.line2)
        assertEquals("Light rain", w(code = 61, h = hours(0, 0, 0)).rainBand()!!.line2) // no chance given: say what it is
    }

    @Test fun otherwiseDryWithTodaysHighAndLow() {
        val b = w(h = hours(0, 10, 20, 29, 0, 0, 0)).rainBand()!!
        assertFalse(b.due); assertNull(b.icon); assertEquals("Dry", b.line1); assertEquals("", b.line2); assertEquals("H16°" to "L8°", w(h = hours(0, 0)).highLow())
        assertNull(w(h = hours(0, 0), d = emptyList()).highLow()) // no high and low: nothing beside the temperature
    }

    @Test fun noForecastYetMeansNoBand() { assertNull(w(h = emptyList()).rainBand()) }
}
