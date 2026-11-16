package io.github.durkimat.phoneguard.core

/**
 * What the side button (F2) means, as a pure state machine with no clock of its own: the caller says when the key went down and up and when a timer it
 * asked for ran out, and gets back what to do. Tested with fake times. The new mapping (owner, 2026-11-16): one press = camera (after a short wait to see
 * whether a second press follows), two presses = torch, hold = speak. [Mapping.LEGACY] is the old one (two presses = camera, hold = torch, one press
 * does nothing) kept for a quick revert: change [MAPPING] in one place.
 *
 * Whether an action is allowed right now (call, lock, proximity, mode) is the caller's [Policy], read when the action would fire; a disallowed one is
 * simply not emitted. Inside our own camera the caller does not use the machine at all (one press is the shutter at once).
 */
class F2Gesture(private val mapping: Mapping = MAPPING) {
    enum class Mapping { NEW, LEGACY }
    sealed class Out { object Camera : Out(); object Torch : Out(); object SpeakStart : Out(); object SpeakStop : Out() }
    data class Policy(val camera: Boolean = true, val torch: Boolean = true, val speak: Boolean = true)

    private enum class S { IDLE, FIRST, WAIT, SECOND, HELD }
    private var s = S.IDLE
    /** When the caller should call [timer] next (uptime milliseconds), or -1 for no timer. */
    var deadline = -1L
        private set
    private var spoke = false

    fun down(now: Long, p: Policy): List<Out> {
        when (s) {
            S.WAIT -> { s = S.SECOND }
            else -> { s = S.FIRST; spoke = false }
        }
        deadline = now + HOLD_MS
        return emptyList()
    }

    fun up(now: Long, p: Policy): List<Out> {
        val out = ArrayList<Out>()
        when (s) {
            S.FIRST -> { s = S.WAIT; deadline = now + (if (mapping == Mapping.NEW) DOUBLE_MS else LEGACY_DOUBLE_MS) }
            S.SECOND -> {
                s = S.IDLE; deadline = -1
                if (mapping == Mapping.NEW) { if (p.torch) out += Out.Torch } else if (p.camera) out += Out.Camera
            }
            S.HELD -> { s = S.IDLE; deadline = -1; if (spoke) out += Out.SpeakStop }
            else -> { s = S.IDLE; deadline = -1 }
        }
        return out
    }

    fun timer(now: Long, p: Policy): List<Out> {
        val out = ArrayList<Out>()
        if (deadline < 0 || now < deadline) return out
        when (s) {
            S.FIRST, S.SECOND -> { // held long enough
                s = S.HELD; deadline = -1
                if (mapping == Mapping.NEW) { if (p.speak) { spoke = true; out += Out.SpeakStart } } else if (p.torch) out += Out.Torch
            }
            S.WAIT -> { // no second press came
                s = S.IDLE; deadline = -1
                if (mapping == Mapping.NEW && p.camera) out += Out.Camera
            }
            else -> deadline = -1
        }
        return out
    }

    companion object {
        /** The one place to switch back to the old buttons if the new ones feel wrong on the phone. */
        val MAPPING = Mapping.NEW
        const val HOLD_MS = 600L
        /** How long a single press waits for a second one (the camera opens this much later). */
        const val DOUBLE_MS = 400L
        const val LEGACY_DOUBLE_MS = 700L
    }
}
