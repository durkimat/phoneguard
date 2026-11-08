package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {
    private fun json(code: Int = 3, precip: String = "0,0,0,0,0.6,0.2", prob: String = "0,0,10,20,70,70", codes: String = "3,3,3,3,61,61") = """
        {"current":{"time":"2026-11-08T13:15","temperature_2m":11.6,"weather_code":$code,"is_day":1},
         "hourly":{"time":["2026-11-08T13:00","2026-11-08T14:00","2026-11-08T15:00","2026-11-08T16:00","2026-11-08T17:00","2026-11-08T18:00"],
           "temperature_2m":[11.6,12,12,11,10,9],"weather_code":[$codes],"precipitation":[$precip],"precipitation_probability":[$prob]},
         "daily":{"time":["2026-11-08","2026-11-09","2026-11-10"],"weather_code":[3,61,2],"temperature_2m_max":[13.2,12,10],"temperature_2m_min":[7,8,4.4]}}
    """.trimIndent()

    @Test fun parsesCurrentHoursAndDays() {
        val w = WeatherData.parse(json(), 1000)!!
        assertEquals(12, w.temp); assertEquals(WeatherIcon.CLOUD, w.icon); assertEquals("Overcast", w.description)
        assertEquals("14:00", w.hours[1].time); assertEquals(3, w.days.size); assertEquals("Sun", w.days[0].label); assertEquals(13, w.days[0].max)
    }

    @Test fun rainComingIsFlaggedWithHowFarAway() {
        assertEquals("Rain in 4h", WeatherData.parse(json(), 0)!!.hint())
    }

    @Test fun dryHoursGiveNoHint() {
        assertNull(WeatherData.parse(json(precip = "0,0,0,0,0,0", prob = "0,0,5,5,10,10", codes = "3,3,3,3,3,3"), 0)!!.hint())
    }

    @Test fun rainingNowSaysWhenItStops() {
        assertEquals("Raining, dry in 1h", WeatherData.parse(json(code = 63, precip = "1,0,0,0,0,0", prob = "90,0,0,0,0,0", codes = "63,3,3,3,3,3"), 0)!!.hint())
    }

    @Test fun iconsFollowTheCodeAndTheTimeOfDay() {
        assertEquals(WeatherIcon.SUN, WeatherData.iconFor(0, true)); assertEquals(WeatherIcon.MOON, WeatherData.iconFor(1, false))
        assertEquals(WeatherIcon.STORM, WeatherData.iconFor(95, true)); assertEquals(WeatherIcon.SNOW, WeatherData.iconFor(73, true)); assertEquals(WeatherIcon.RAIN, WeatherData.iconFor(81, true))
    }

    @Test fun garbageIsIgnored() { assertNull(WeatherData.parse("{}", 0)); assertNotNull(WeatherData.url(51.75, -0.34)) }
}
