package io.github.durkimat.phoneguard.where

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A place from `/api/geo/search` (docs/where-to-protocol.md). */
data class Place(val name: String, val kind: String, val area: String, val lat: Double, val lon: Double, val km: Double?)

data class Leg(val mode: String, val line: String, val headsign: String, val fromName: String, val toName: String, val departIso: String, val arriveIso: String, val minutes: Int, val live: Boolean, val points: String?)
data class Journey(val minutes: Int, val startIso: String, val endIso: String, val transfers: Int, val live: Boolean, val legs: List<Leg>)
data class Plan(val prefer: String, val walkMinutes: Int?, val walkMetres: Int?, val walkPoints: String?, val options: List<Journey>)

/** One row of the journeys screen: a transit option, or the plain walk (null journey). */
data class JourneyRow(val label: String, val journey: Journey?, val action: RowAction = RowAction.OPEN)

/** What OK does on a journeys row: open it, ask for later times, or look again tomorrow morning. */
enum class RowAction { OPEN, LATER, TOMORROW }

/** Parsing of the Worker's answers and the words shown on the keypad screens. Pure, tested without a phone. */
object Where {
    fun parsePlaces(text: String): List<Place> = try {
        val a = JSONObject(text).optJSONArray("places") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> if (o.has("lat") && o.has("lon") && o.optString("name").isNotBlank())
                Place(o.optString("name").trim(), o.optString("kind"), o.optString("area").trim(), o.getDouble("lat"), o.getDouble("lon"), if (o.has("km")) o.optDouble("km") else null) else null }
        }
    } catch (_: Exception) { emptyList() }

    private fun geometry(o: JSONObject?): String? = o?.optJSONObject("geometry")?.optString("points")?.takeIf { it.isNotEmpty() }

    fun parsePlan(text: String): Plan = try {
        val j = JSONObject(text)
        val walk = j.optJSONObject("walk")
        val opts = j.optJSONArray("options") ?: JSONArray()
        Plan(j.optString("prefer", "none"), walk?.optInt("minutes"), walk?.optInt("metres"), geometry(walk),
            (0 until opts.length()).mapNotNull { i -> opts.optJSONObject(i)?.let { o ->
                val legs = o.optJSONArray("legs") ?: JSONArray()
                Journey(o.optInt("minutes"), o.optString("start"), o.optString("end"), o.optInt("transfers"), o.optBoolean("live"),
                    (0 until legs.length()).mapNotNull { k -> legs.optJSONObject(k)?.let { l ->
                        Leg(l.optString("mode", "other"), l.optString("line"), l.optString("headsign"), l.optJSONObject("from")?.optString("name").orEmpty(), l.optJSONObject("to")?.optString("name").orEmpty(),
                            l.optJSONObject("from")?.optString("time").orEmpty(), l.optJSONObject("to")?.optString("time").orEmpty(), l.optInt("minutes"), l.optBoolean("live"), geometry(l)) } })
            } })
    } catch (_: Exception) { Plan("none", null, null, null, emptyList()) }

    /** "08:12" in the phone's own time zone, or "" when the time cannot be read. */
    fun clock(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = try { OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm")) } catch (_: Exception) { "" }

    /** "1.5 km  Hexham Station, Northumberland": the distance first (when the Worker sent it) so it is never cut off, then the name and area. */
    fun placeRow(p: Place): String = (p.km?.let { km -> (if (km < 1.0) "${(km * 1000).toInt()} m" else if (km < 10) "%.1f km".format(java.util.Locale.UK, km) else "${km.toInt()} km") + "  " } ?: "") +
        p.name + (if (p.area.isNotBlank() && !p.name.contains(p.area, true)) ", " + p.area else "")

    /** "53m" or "1h06": short enough for a row. */
    fun dur(min: Int): String = if (min < 60) "${min}m" else "${min / 60}h" + "%02d".format(min % 60)

    /** The journeys screen's rows, the preferred kind first (walk under about 2 miles or 45 minutes, else public transport). */
    fun journeyRows(plan: Plan, zone: ZoneId = ZoneId.systemDefault()): List<JourneyRow> {
        val walk = plan.walkMinutes?.let { JourneyRow("Walk  ${dur(it)}" + (plan.walkMetres?.let { m -> "  " + distance(m) } ?: ""), null) }
        val transit = plan.options.map { o ->
            JourneyRow(listOf(clock(o.startIso, zone), dur(o.minutes), if (o.transfers == 0) "direct" else "${o.transfers} chg", if (o.live) "LIVE" else "").filter { it.isNotEmpty() }.joinToString("  "), o)
        }
        val rows = if (plan.prefer == "walk") listOfNotNull(walk) + transit else transit + listOfNotNull(walk)
        return when {
            plan.options.isNotEmpty() -> rows + JourneyRow("Later times...", null, RowAction.LATER)
            else -> rows + JourneyRow("No bus or train: try tomorrow", null, RowAction.TOMORROW)
        }
    }

    /** One minute after the last option leaves, as the Worker's UTC time, for "Later times..."; null when no option has a readable time. */
    fun laterTime(plan: Plan): String? = plan.options.mapNotNull { try { OffsetDateTime.parse(it.startIso).toInstant() } catch (_: Exception) { null } }.maxOrNull()?.plusSeconds(60)?.toString()

    /** 07:00 tomorrow in [zone], as a UTC time for the Worker ("Try tomorrow"). */
    fun tomorrowMorning(now: java.time.Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        now.atZone(zone).toLocalDate().plusDays(1).atTime(7, 0).atZone(zone).toInstant().toString()

    fun distance(metres: Int): String = if (metres < 1000) "$metres m" else "%.1f km".format(java.util.Locale.UK, metres / 1000.0)

    private fun modeWord(m: String) = when (m) { "bus" -> "Bus"; "train" -> "Train"; "tram" -> "Tram"; "underground" -> "Tube"; "ferry" -> "Ferry"; "walk" -> "Walk"; else -> "Go" }

    /** One line of a journey: "Walk 4 min to Hatfield Stn" or "08:12 Bus 321 to St Albans (18 min)". */
    fun legLine(l: Leg, zone: ZoneId = ZoneId.systemDefault()): String =
        if (l.mode == "walk") "Walk ${l.minutes} min" + (if (l.toName.isNotBlank()) " to ${l.toName}" else "")
        else listOf(clock(l.departIso, zone), modeWord(l.mode) + (if (l.line.isNotBlank()) " ${l.line}" else "")).filter { it.isNotEmpty() }.joinToString(" ") +
            (if (l.headsign.isNotBlank()) " to ${l.headsign}" else if (l.toName.isNotBlank()) " to ${l.toName}" else "") + " (${l.minutes} min)" + (if (l.live) " LIVE" else "")

    /** The word that says whether the times include delays. */
    fun liveTag(j: Journey) = if (j.live) "LIVE times" else "TIMETABLE times"
}

/** Decodes an encoded polyline (Google's algorithm) at the given precision into latitude/longitude pairs. */
object Polyline {
    fun decode(s: String, precision: Int = 6): List<Pair<Double, Double>> {
        val scale = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0; var lat = 0L; var lon = 0L
        fun next(): Long? {
            var result = 0L; var shift = 0
            while (true) {
                if (i >= s.length) return null
                val b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift); shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < s.length) {
            lat += next() ?: break; lon += next() ?: break
            out += lat / scale to lon / scale
        }
        return out
    }
}
