package io.github.durkimat.phoneguard.core

import org.json.JSONArray

/**
 * One line of the radio diary the parent can read: when, whether the phone was locked, what the radios were ACTUALLY doing
 * (read from the phone after any change, not what we asked for), and why. [data] is -1 when there is no mobile data to switch.
 */
data class RadioEntry(val t: Long, val locked: Boolean, val wifi: Boolean, val bt: Boolean, val data: Int, val why: String) {
    fun toJson(): JSONArray = JSONArray().put(t).put(if (locked) 1 else 0).put(if (wifi) 1 else 0).put(if (bt) 1 else 0).put(data).put(why)

    /** Whether this says something the line before did not (the time alone does not count). */
    fun differsFrom(before: RadioEntry?): Boolean = before == null || copy(t = 0) != before.copy(t = 0)

    companion object {
        fun fromJson(a: JSONArray): RadioEntry? = try {
            RadioEntry(a.getLong(0), a.getInt(1) == 1, a.getInt(2) == 1, a.getInt(3) == 1, a.getInt(4), a.getString(5))
        } catch (_: Exception) { null }
    }
}

object RadioLog {
    /** The reason when the radios ought to be off. The parent's page counts a radio that is on under this reason as a fault. */
    const val RESTING = "resting"
    const val IN_USE = "in use"
    const val WAITING = "waiting to rest"

    /**
     * Why the radios are as they are. [modeRests] is the mode's own switch, [live] live location (which needs the radios),
     * [reportOnly] the power saver's "only report" tick (which changes nothing, radios included), [heldBy] what else keeps them
     * awake, and [job] the check-in that has woken them.
     */
    fun why(i: RadioInputs, modeRests: Boolean, live: Boolean, reportOnly: Boolean, heldBy: String?, job: String?): String = when {
        i.inUse -> IN_USE
        reportOnly -> "not resting: power saver set to only report"
        !modeRests -> "not resting: this mode keeps radios on"
        live -> "not resting: live location"
        i.windowOpen -> "woken for " + (job ?: "a check-in")
        i.hold -> "kept on: " + (heldBy ?: "something needs them")
        i.offForMs < i.graceMs -> WAITING
        else -> RESTING
    }
}
