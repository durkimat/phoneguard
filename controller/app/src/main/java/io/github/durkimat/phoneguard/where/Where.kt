package io.github.durkimat.phoneguard.where

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A place from `/api/geo/search` (docs/where-to-protocol.md). */
data class Place(val name: String, val kind: String, val area: String, val lat: Double, val lon: Double, val km: Double?, val type: String = "", val info: PlaceInfo? = null)

/** What the list answer said about a place found by kind ("cafe", "pub"), kept only where it was sent: opening hours (and whether open now, a hint), takeaway, diet, wheelchair access, phone. */
data class PlaceInfo(val hours: String = "", val open: Boolean? = null, val until: String = "", val opens: String = "", val takeaway: String = "", val diet: List<String> = emptyList(), val wheelchair: String = "", val phone: String = "")

/** The Worker's answer to a search: the places, what kind of place was asked for (null for a name search), and why there are none ("need_position"). */
class PlacesAnswer(val places: List<Place>, val category: String?, val error: String?)

data class Leg(val mode: String, val line: String, val headsign: String, val fromName: String, val toName: String, val departIso: String, val arriveIso: String, val minutes: Int, val live: Boolean, val points: String?)
data class Journey(val minutes: Int, val startIso: String, val endIso: String, val transfers: Int, val live: Boolean, val legs: List<Leg>)
data class Plan(val prefer: String, val walkMinutes: Int?, val walkMetres: Int?, val walkPoints: String?, val options: List<Journey>)

/** One row of the journeys screen: a transit option, or the plain walk (null journey). */
data class JourneyRow(val label: String, val journey: Journey?, val action: RowAction = RowAction.OPEN)

/** What OK does on a journeys row: open it, ask for later times, or look again tomorrow morning. */
enum class RowAction { OPEN, LATER, TOMORROW }

/** Parsing of the Worker's answers and the words shown on the keypad screens. Pure, tested without a phone. */
object Where {
    private fun info(o: JSONObject?): PlaceInfo? = o?.let {
        val d = it.optJSONArray("diet")
        PlaceInfo(it.optString("hours").trim(), if (it.has("open")) it.optBoolean("open") else null, it.optString("until"), it.optString("opens"), it.optString("takeaway"),
            (0 until (d?.length() ?: 0)).map { i -> d!!.optString(i) }.filter { x -> x.isNotBlank() }, it.optString("wheelchair"), it.optString("phone").trim())
    }

    private fun placesOf(a: JSONArray): List<Place> = (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { o ->
            val type = o.optString("type").trim(); val name = o.optString("name").trim().ifEmpty { type } // a place with no name shows its kind as the title
            if (o.has("lat") && o.has("lon") && name.isNotBlank()) Place(name, o.optString("kind"), o.optString("area").trim(), o.getDouble("lat"), o.getDouble("lon"), if (o.has("km")) o.optDouble("km") else null, type, info(o.optJSONObject("info"))) else null
        }
    }

    fun parsePlaces(text: String): List<Place> = try { placesOf(JSONObject(text).optJSONArray("places") ?: JSONArray()) } catch (_: Exception) { emptyList() }

    /** A search answer with its extras; an old Worker sends only the places, so category and error come back null. */
    fun parseAnswer(text: String): PlacesAnswer = try {
        val j = JSONObject(text)
        PlacesAnswer(placesOf(j.optJSONArray("places") ?: JSONArray()), j.optString("category").ifEmpty { null }, j.optString("error").ifEmpty { null })
    } catch (_: Exception) { PlacesAnswer(emptyList(), null, null) }

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

    /** "Hexham Station, Northumberland": the name first so it is never cut off; the distance is shown under the list for the highlighted place ([placeNote]). */
    fun placeRow(p: Place): String = p.name + (if (p.area.isNotBlank() && !p.name.contains(p.area, true)) ", " + p.area else "")

    fun kmText(km: Double): String = if (km < 1.0) "${(km * 1000).toInt()} m" else if (km < 10) "%.1f km".format(java.util.Locale.UK, km) else "${km.toInt()} km"

    /** "9.2 km  Bus stop": what is said under the list about the highlighted place. */
    fun placeNote(p: Place): String = listOfNotNull(p.km?.let { kmText(it) }, when (p.kind) { "stop" -> "Bus or train stop"; "postcode" -> "Postcode"; "address" -> "Address"; else -> null }).joinToString("  ")

    /** The short line under a place's name in the list: its kind ("Italian restaurant") when the Worker sent one. */
    fun placeSub(p: Place): String = p.type.takeIf { it.isNotBlank() && !it.equals(p.name, true) }.orEmpty()

    /** "250 m, about 3 min walk" from the distance the list already gave (walking at 80 m a minute); "" when there is no distance. */
    fun walkLine(p: Place): String = p.km?.let { k -> kmText(k) + ", about " + maxOf(1, Math.round(k * 1000 / 80.0).toInt()) + " min walk" }.orEmpty()

    /** The lines of a place's detail page: only what the data says, never an "unknown" line. */
    fun detailLines(p: Place): List<String> {
        val i = p.info
        val out = ArrayList<String>()
        if (p.type.isNotBlank() && !p.type.equals(p.name, true)) out += p.type
        if (p.area.isNotBlank() && p.kind == "poi") out += p.area
        if (i != null) {
            val open = when { i.open == true -> "Open" + (if (i.until.isNotEmpty()) " until ${i.until}" else " now"); i.open == false -> "Closed" + (if (i.opens.isNotEmpty()) ", opens ${i.opens}" else ""); else -> "" }
            if (open.isNotEmpty()) out += open
            if (i.hours.isNotEmpty()) out += if (open.isEmpty()) "Hours: ${i.hours}" else i.hours
            val extras = listOfNotNull(when (i.takeaway) { "yes" -> "Takeaway"; "only" -> "Takeaway only"; "no" -> "No takeaway"; else -> null }) + i.diet.mapNotNull { when (it) { "vegan" -> "Vegan options"; "vegetarian" -> "Vegetarian options"; else -> null } }
            if (extras.isNotEmpty()) out += extras.joinToString(", ")
            when (i.wheelchair) { "yes" -> out += "Wheelchair: yes"; "limited" -> out += "Wheelchair: limited"; "no" -> out += "Wheelchair: no" }
            if (i.phone.isNotEmpty()) out += i.phone
        }
        walkLine(p).takeIf { it.isNotEmpty() }?.let { out += it }
        if (out.isEmpty()) out += "No more details held for this place"
        return out
    }

    /** Nearest first when the Worker sent distances (a child asking for "station" wants the closest one); otherwise the Worker's own order. */
    fun nearestFirst(list: List<Place>): List<Place> = if (list.isNotEmpty() && list.all { it.km != null }) list.sortedBy { it.km } else list

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
