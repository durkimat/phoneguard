package io.github.durkimat.phoneguard.core

import java.time.Instant
import java.time.ZoneId

/**
 * Overnight app updates: what to touch and when. Pure, so it is tested. The store app itself is NEVER updated this way (it would change the labels the install
 * and update jobs press); neither is Phone Guard (it has its own update path). Only apps the store installed, only apps nobody is using, and never a paused phone.
 */
object UpdatePlan {
    const val STORE = "com.aurora.store"
    const val DEFAULT_AT_MIN = 180

    fun settings(cfg: Config?) = cfg?.autoUpdate ?: AutoUpdate()

    /** The next quiet time after [now], as a wall-clock time in [zone] (the companion sets the time of day; the phone's time zone applies). */
    fun nextRun(atMin: Int, now: Long, zone: ZoneId): Long {
        val z = Instant.ofEpochMilli(now).atZone(zone)
        var t = z.toLocalDate().atStartOfDay(zone).plusMinutes(atMin.toLong())   // plusMinutes on a zoned time keeps the clock-change days right
        if (t.toInstant().toEpochMilli() <= now) t = z.toLocalDate().plusDays(1).atStartOfDay(zone).plusMinutes(atMin.toLong())
        return t.toInstant().toEpochMilli()
    }

    /** Why a package is not touched (null = it may be updated). [installer] is who installed it, [busy] whether it is in use right now. */
    fun skip(pkg: String, installer: String?, own: String, s: AutoUpdate, busy: Boolean): String? = when {
        pkg == STORE -> "the store itself is only updated with Phone Guard"
        pkg == own -> "Phone Guard updates itself"
        pkg in s.exclude -> "left out by the companion"
        installer != STORE -> "not installed by the store"
        busy -> "in use right now"
        else -> null
    }

    /** What the overnight check does about the network: "go", or why it cannot ("no Wi-Fi"). Data is only used when the companion allowed it. */
    fun network(onWifi: Boolean, online: Boolean, s: AutoUpdate): String = when {
        onWifi -> "go"
        online && s.data -> "go"
        else -> "no Wi-Fi"
    }
}
