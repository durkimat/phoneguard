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
            )
        }
    }
}

/** Extra time with a chosen mode. Applied as a union with the scheduled mode, so it never reduces access. */
data class Boost(val mode: String, val until: Long)
