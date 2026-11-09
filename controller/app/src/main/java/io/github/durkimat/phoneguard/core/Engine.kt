package io.github.durkimat.phoneguard.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Which apps are visible. `all` means every manageable app. */
data class Allowed(val all: Boolean, val pkgs: Set<String>)

/** How locked down the phone is. The least restrictive of the mode and any boost wins for each setting. */
data class Lockdown(val launcher: String, val touch: Boolean, val shade: Boolean, val kiosk: Boolean) {
    companion object { val OPEN = Lockdown("default", true, true, false) }
}

/** Everything the enforcer needs for one moment. Equal values mean no visible change. */
data class Effective(val modeId: String, val allowed: Allowed, val gray: Boolean, val lockdown: Lockdown = Lockdown.OPEN, val sound: String = "normal", val inbound: Boolean = true, val wa: String = "open", val waApp: String = "auto", val camera: Boolean = true, val location: String = "off", val locEvery: Int = 30, val notes: String = "show", val idleMin: Int = 10, val radio: String = "any")

object Engine {
    const val EMERGENCY = "emergency"
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WARN_MS = 10L * 60 * 1000

    /** Built in: only protected apps stay visible. Used when there is no usable schedule. */
    val emergencyMode = ModeDef(EMERGENCY, "Emergency", false, emptySet(), false)

    fun modeDef(cfg: Config?, id: String): ModeDef = cfg?.modes?.get(id) ?: emergencyMode

    /** The mode the timetable wants at `now`, before any boost. */
    fun scheduledMode(cfg: Config?, now: Long): String {
        if (cfg == null) return EMERGENCY
        cfg.override?.let { if (cfg.modes.containsKey(it)) return it } // a parent forced a mode; an unknown one is ignored
        cfg.segments.firstOrNull { now >= it.from && now < it.to }?.let { return it.mode }
        if (now >= cfg.windowEnd) cfg.pattern?.let { p -> patternMode(p, now)?.let { return it } }
        return cfg.base
    }

    private fun patternMode(p: Pattern, now: Long): String? {
        val z = Instant.ofEpochMilli(now).atZone(ZoneId.of(p.tz))
        val min = z.hour * 60 + z.minute
        return p.days[z.dayOfWeek.value]?.firstOrNull { min >= it.startMin && min < it.endMin }?.mode
    }

    fun locRank(l: String) = when (l) { "gps" -> 2; "network" -> 1; else -> 0 }

    fun effective(cfg: Config?, boost: Boost?, now: Long): Effective {
        val id = scheduledMode(cfg, now)
        val s = modeDef(cfg, id)
        var all = s.allowAll
        val pkgs = s.allow.toMutableSet()
        var gray = s.gray
        var ld = Lockdown(s.launcher, s.touch, s.shade, s.kiosk)
        var sound = s.sound
        var inbound = s.inbound
        var wa = s.wa
        var waApp = s.waApp
        var camera = s.camera
        var location = s.location
        var locEvery = s.locEvery
        var notes = s.notes
        var radio = s.radio
        if (boost != null && now < boost.until) {
            val b = modeDef(cfg, boost.mode)
            all = all || b.allowAll
            pkgs += b.allow
            gray = gray && b.gray
            sound = louder(sound, b.sound)
            inbound = inbound || b.inbound
            wa = moreOften(wa, b.wa)
            waApp = moreFull(waApp, b.waApp)
            camera = camera || b.camera
            if (b.notes == "show") notes = "show"
            radio = moreRadio(radio, b.radio)
            // the more capable position setting wins; between two that report, the more frequent (0 is live)
            if (locRank(b.location) > locRank(location)) { location = b.location; locEvery = b.locEvery }
            else if (locRank(b.location) == locRank(location) && location != "off" && b.locEvery < locEvery) locEvery = b.locEvery
            ld = Lockdown(
                launcher = if (ld.launcher == "default" || b.launcher == "default") "default" else ld.launcher,
                touch = ld.touch || b.touch, shade = ld.shade || b.shade, kiosk = ld.kiosk && b.kiosk,
            )
        }
        return Effective(id, Allowed(all, if (all) emptySet() else pkgs), gray, ld, sound, inbound, wa, waApp, camera, location, locEvery, notes, s.idleMin, radio)
    }

    /** The more open of two radio settings (a boost never takes radio away). */
    fun moreRadio(a: String, b: String): String {
        val order = listOf("off", "presets", "any")
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    /** The more open of two "WhatsApp app" settings (a boost never takes the real app away). */
    fun moreFull(a: String, b: String): String {
        val order = listOf("simple", "auto", "full")
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    /**
     * Whether the real WhatsApp app is used instead of our simple screens: when the mode says "full" or "auto" and the real
     * app is installed and visible in this mode. If it is not there, our screens are used whatever was asked.
     */
    fun useRealWhatsApp(waApp: String, realAvailable: Boolean, ourLinked: Boolean = false): Boolean =
        realAvailable && waApp != "simple" && (waApp == "full" || !ourLinked) // Auto keeps our screens once ours is linked; Real app always uses the real one

    /** The more frequent of two WhatsApp check settings (a boost never makes it check less). */
    fun moreOften(a: String, b: String): String {
        val order = listOf("off", "open", "60", "30")
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    /** The louder of two sound settings: normal beats vibrate beats silent (a boost never makes the phone quieter). */
    fun louder(a: String, b: String): String {
        val order = listOf("silent", "vibrate", "normal")
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    /** Next instant at which the inputs to `effective` can change (some may not change it). */
    fun nextBoundary(cfg: Config?, boost: Boost?, now: Long): Long {
        var best = now + DAY_MS
        fun c(t: Long) { if (t > now && t < best) best = t }
        if (cfg != null) {
            cfg.segments.forEach { c(it.from); c(it.to) }
            c(cfg.windowEnd)
            cfg.pattern?.let { p -> patternEdges(p, now).forEach(::c) }
        }
        boost?.let { c(it.until) }
        return best
    }

    private fun patternEdges(p: Pattern, now: Long): List<Long> {
        val zone = ZoneId.of(p.tz)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val out = mutableListOf<Long>()
        for (d in 0L..3L) {
            val date = today.plusDays(d)
            for (seg in p.days[date.dayOfWeek.value].orEmpty()) {
                for (m in listOf(seg.startMin, seg.endMin)) {
                    val t = if (m >= 1440) date.plusDays(1).atStartOfDay(zone)
                    else date.atTime(LocalTime.of(m / 60, m % 60)).atZone(zone)
                    out += t.toInstant().toEpochMilli()
                }
            }
        }
        return out
    }

    /** The next moment the effective state actually differs from now, or null if nothing changes within a few days. */
    fun nextChange(cfg: Config?, boost: Boost?, now: Long): Pair<Long, Effective>? {
        val cur = effective(cfg, boost, now)
        var t = now
        repeat(80) {
            t = nextBoundary(cfg, boost, t)
            val e = effective(cfg, boost, t)
            if (e != cur) return t to e
        }
        return null
    }

    /** Packages visible under `a`, out of everything we can manage. */
    fun visible(a: Allowed, manageable: Set<String>): Set<String> =
        if (a.all) manageable else manageable intersect a.pkgs

    /** True when moving from `cur` to `next` takes away at least one visible app. */
    fun removesAccess(cur: Allowed, next: Allowed, manageable: Set<String>): Boolean =
        (visible(cur, manageable) - visible(next, manageable)).isNotEmpty()

    /** When to warn: [WARN_MS] before the next change, but only if that change removes access. */
    fun warningFor(cfg: Config?, boost: Boost?, now: Long, manageable: Set<String>): Pair<Long, Effective>? {
        val (t, next) = nextChange(cfg, boost, now) ?: return null
        val cur = effective(cfg, boost, now)
        return if (removesAccess(cur.allowed, next.allowed, manageable)) t to next else null
    }
}
