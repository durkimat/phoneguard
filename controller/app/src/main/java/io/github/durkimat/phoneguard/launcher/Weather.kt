package io.github.durkimat.phoneguard.launcher

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

enum class WeatherIcon { SUN, MOON, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM }

data class WeatherHour(val time: String, val temp: Int, val code: Int, val precip: Double, val prob: Int)
data class WeatherDay(val label: String, val code: Int, val max: Int, val min: Int)

/** What Open-Meteo told us, boiled down to what the phone shows. Pure, so it can be tested. */
data class WeatherData(val temp: Int, val code: Int, val isDay: Boolean, val hours: List<WeatherHour>, val days: List<WeatherDay>, val fetched: Long) {
    val icon: WeatherIcon get() = iconFor(code, isDay)
    val description: String get() = describe(code)

    /** "Raining", "Rain in 2h" and so on when wet weather is near, else null. Hourly data, so no finer than an hour. */
    fun hint(): String? {
        val word = fun(c: Int) = if (c in 71..77 || c == 85 || c == 86) "Snow" else "Rain"
        if (isWet(code)) {
            val dry = hours.drop(1).take(4).indexOfFirst { it.precip < 0.1 && it.prob < 30 && !isWet(it.code) }
            return (if (word(code) == "Snow") "Snowing" else "Raining") + (if (dry >= 0) ", dry in ${dry + 1}h" else "")
        }
        val next = hours.drop(1).take(4)
        val i = next.indexOfFirst { it.precip >= 0.3 || it.prob >= 60 || isWet(it.code) }
        return if (i < 0) null else "${word(next[i].code)} in ${i + 1}h"
    }

    companion object {
        fun isWet(code: Int) = code in 51..67 || code in 71..77 || code in 80..86 || code >= 95

        fun iconFor(code: Int, day: Boolean) = when (code) {
            0, 1 -> if (day) WeatherIcon.SUN else WeatherIcon.MOON
            2 -> WeatherIcon.PARTLY
            3 -> WeatherIcon.CLOUD
            45, 48 -> WeatherIcon.FOG
            in 71..77, 85, 86 -> WeatherIcon.SNOW
            in 95..99 -> WeatherIcon.STORM
            in 51..67, in 80..82 -> WeatherIcon.RAIN
            else -> WeatherIcon.CLOUD
        }

        fun describe(code: Int) = when (code) {
            0 -> "Clear"; 1 -> "Mainly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
            61 -> "Light rain"; 63 -> "Rain"; 65 -> "Heavy rain"; 66, 67 -> "Freezing rain"
            71 -> "Light snow"; 73 -> "Snow"; 75 -> "Heavy snow"; 77 -> "Snow grains"
            80 -> "Light showers"; 81 -> "Showers"; 82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"; 96, 99 -> "Thunder and hail"; 97 -> "Heavy thunder"
            else -> "Cloudy"
        }

        /** The request: current conditions, the next day by the hour and the next three days. */
        fun url(lat: Double, lon: Double) =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code,precipitation,precipitation_probability&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_hours=12&forecast_days=3"

        fun parse(text: String, now: Long): WeatherData? = try {
            val o = JSONObject(text)
            val cur = o.getJSONObject("current")
            val h = o.getJSONObject("hourly"); val d = o.getJSONObject("daily")
            val times = h.getJSONArray("time")
            val hours = (0 until times.length()).map { i ->
                WeatherHour(times.getString(i).substring(11, 16), Math.round(h.getJSONArray("temperature_2m").optDouble(i)).toInt(),
                    h.getJSONArray("weather_code").optInt(i), h.getJSONArray("precipitation").optDouble(i, 0.0), h.getJSONArray("precipitation_probability").optInt(i, 0))
            }
            val dates = d.getJSONArray("time")
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK); val dow = SimpleDateFormat("EEE", Locale.UK)
            val days = (0 until dates.length()).map { i ->
                WeatherDay(dow.format(fmt.parse(dates.getString(i))!!), d.getJSONArray("weather_code").optInt(i),
                    Math.round(d.getJSONArray("temperature_2m_max").optDouble(i)).toInt(), Math.round(d.getJSONArray("temperature_2m_min").optDouble(i)).toInt())
            }
            WeatherData(Math.round(cur.getDouble("temperature_2m")).toInt(), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1, hours, days, now)
        } catch (_: Exception) { null }
    }
}
