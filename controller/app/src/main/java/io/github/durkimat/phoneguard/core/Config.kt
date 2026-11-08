package io.github.durkimat.phoneguard.core

import org.json.JSONArray
import org.json.JSONObject

/** One mode: which apps are visible and whether the screen is grey. Hiding is the only mechanism. */
data class ModeDef(
    val id: String,
    val name: String,
    val allowAll: Boolean,
    val allow: Set<String>,
    val gray: Boolean,
    /** "default" (the normal launcher) or "nokia" (our keypad launcher). */
    val launcher: String = "default",
    /** Whether the touchscreen works. Off swallows every touch. */
    val touch: Boolean = true,
    /** Whether the pull-down shade works. */
    val shade: Boolean = true,
    /** Lock the phone to the allowed apps: no recents, no power menu, no leaving the task. */
    val kiosk: Boolean = false,
    /** "normal", "vibrate" or "silent". Silent still lets starred contacts ring through. */
    val sound: String = "normal",
    /** Whether incoming calls are allowed. Off hangs up on every incoming call. */
    val inbound: Boolean = true,
    /** WhatsApp checks: "off", "open" (only when the owner opens WhatsApp), "30" or "60" (minutes between checks). */
    val wa: String = "open",
    /** Which WhatsApp the menus use: "auto" (the real app when it is installed and allowed), "simple" (our screens) or "full" (the real app). */
    val waApp: String = "auto",
    /** Whether our themed camera is offered in the menu in this mode. */
    val camera: Boolean = true,
    /** Position reporting: "off", "network" (low accuracy) or "gps". Off also switches the phone's location off. */
    val location: String = "off",
    /** Minutes between position reports; 0 means live (continuous). */
    val locEvery: Int = 30,
    /** Notifications: "show" lists them on the Nokia screens, "off" clears them as they arrive. */
    val notes: String = "show",
)

/** Who WhatsApp may read and write to, and how fast it may send. Set per device in HA. */
data class WaConfig(
    val all: Boolean = false,
    /** Phone numbers and "g:Group name" entries. */
    val allowed: List<String> = emptyList(),
    val perMinute: Int = 5,
    val perDay: Int = 200,
)

/** An absolute span of time with one mode. HA resolves holidays and timezones into these. */
data class Segment(val from: Long, val to: Long, val mode: String)

/** One slice of a repeating weekday pattern, in minutes after local midnight. */
data class PatSeg(val startMin: Int, val endMin: Int, val mode: String)

/** The same-weekday pattern used after the pushed window runs out. Days are ISO 1 (Mon) to 7 (Sun). */
data class Pattern(val tz: String, val days: Map<Int, List<PatSeg>>)

data class Config(
    val version: Long,
    val modes: Map<String, ModeDef>,
    val base: String,
    val segments: List<Segment>,
    val windowEnd: Long,
    val pattern: Pattern?,
    /** Packages a parent adds to the always-visible set. */
    val protectExtra: Set<String>,
    /** Default-protected-but-editable packages (clock, sync helper) the parent chooses to manage. */
    val unprotect: Set<String>,
    /** User restriction keys to enforce, or null for the built-in default set. */
    val restrictions: Set<String>?,
    /** A parent forcing one mode regardless of the timetable, until HA clears it. */
    val override: String? = null,
    val whatsapp: WaConfig? = null,
    /** Apps the parent put in the Nokia main menu itself; the other allowed apps go under "Android Apps". */
    val pinned: List<String> = emptyList(),
    /** Where the weather is for (set from HA, the phone never asks for its own location). */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Apps whose notifications are cleared as they arrive, besides the built-in list. */
    val notesHide: Set<String> = emptySet(),
) {
    companion object {
        private fun strings(a: JSONArray?): Set<String> {
            if (a == null) return emptySet()
            return (0 until a.length()).map { a.getString(it) }.toSet()
        }

        fun parse(text: String): Config {
            val o = JSONObject(text)
            val modes = mutableMapOf<String, ModeDef>()
            val mo = o.getJSONObject("modes")
            for (id in mo.keys()) {
                val m = mo.getJSONObject(id)
                modes[id] = ModeDef(
                    id = id,
                    name = m.optString("name", id),
                    allowAll = m.optBoolean("allowAll", false),
                    allow = strings(m.optJSONArray("allow")),
                    gray = m.optBoolean("gray", false),
                    launcher = m.optString("launcher", "default"),
                    touch = m.optBoolean("touch", true),
                    shade = m.optBoolean("shade", true),
                    kiosk = m.optBoolean("kiosk", false),
                    sound = m.optString("sound", "normal"),
                    inbound = m.optBoolean("inbound", true),
                    wa = m.optString("wa", "open"),
                    waApp = m.optString("waApp", "auto"),
                    camera = m.optBoolean("camera", true),
                    location = m.optString("location", "off"),
                    locEvery = m.optInt("locEvery", 30),
                    notes = m.optString("notes", "show"),
                )
            }
            val segs = o.optJSONArray("segments")?.let { a ->
                (0 until a.length()).map {
                    val s = a.getJSONObject(it)
                    Segment(s.getLong("from"), s.getLong("to"), s.getString("mode"))
                }
            } ?: emptyList()
            val pattern = o.optJSONObject("pattern")?.let { p ->
                val days = mutableMapOf<Int, List<PatSeg>>()
                val d = p.getJSONObject("days")
                for (k in d.keys()) {
                    val arr = d.getJSONArray(k)
                    days[k.toInt()] = (0 until arr.length()).map {
                        val s = arr.getJSONArray(it)
                        PatSeg(s.getInt(0), s.getInt(1), s.getString(2))
                    }
                }
                Pattern(p.getString("tz"), days)
            }
            return Config(
                version = o.optLong("version", 0),
                modes = modes,
                base = o.optString("base", "day"),
                segments = segs.sortedBy { it.from },
                windowEnd = o.optLong("windowEnd", segs.maxOfOrNull { it.to } ?: 0L),
                pattern = pattern,
                protectExtra = strings(o.optJSONArray("protectExtra")),
                unprotect = strings(o.optJSONArray("unprotect")),
                restrictions = if (o.has("restrictions")) strings(o.getJSONArray("restrictions")) else null,
                override = o.optString("override", "").ifEmpty { null },
                lat = o.optJSONObject("location")?.takeIf { it.has("lat") && it.has("lon") }?.optDouble("lat"),
                lon = o.optJSONObject("location")?.takeIf { it.has("lat") && it.has("lon") }?.optDouble("lon"),
                notesHide = strings(o.optJSONArray("notesHide")),
                pinned = (o.optJSONArray("pinned") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
                whatsapp = o.optJSONObject("whatsapp")?.let { w ->
                    WaConfig(
                        all = w.optBoolean("all", false),
                        allowed = (w.optJSONArray("allowed") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
                        perMinute = w.optInt("perMinute", 5),
                        perDay = w.optInt("perDay", 200),
                    )
                },
            )
        }
    }
}

/** Extra time with a chosen mode. Applied as a union with the scheduled mode, so it never reduces access. */
data class Boost(val mode: String, val until: Long)
