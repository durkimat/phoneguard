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
data class JourneyRow(val label: String, val journey: Journey?)

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

    fun placeRow(p: Place): String = p.name + (if (p.area.isNotBlank() && !p.name.contains(p.area, true)) ", " + p.area else "")

    /** The journeys screen's rows, the preferred kind first (walk under about 2 miles or 45 minutes, else public transport). */
    fun journeyRows(plan: Plan, zone: ZoneId = ZoneId.systemDefault()): List<JourneyRow> {
        val walk = plan.walkMinutes?.let { JourneyRow("Walk  $it min" + (plan.walkMetres?.let { m -> "  " + distance(m) } ?: ""), null) }
        val transit = plan.options.map { o ->
            JourneyRow(listOf(clock(o.startIso, zone), "${o.minutes} min", if (o.transfers == 0) "direct" else "${o.transfers} change" + (if (o.transfers > 1) "s" else ""), if (o.live) "LIVE" else "").filter { it.isNotEmpty() }.joinToString("  "), o)
        }
        return if (plan.prefer == "walk") listOfNotNull(walk) + transit else transit + listOfNotNull(walk)
    }

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
