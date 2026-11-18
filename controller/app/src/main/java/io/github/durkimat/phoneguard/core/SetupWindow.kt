package io.github.durkimat.phoneguard.core

/** What the phone remembers about a running setup window: when it started (wall clock and since boot) and what the companion's counters said then. */
data class SetupRun(val startedAt: Long, val startedElapsed: Long, val extraAtStart: Int, val endRevAtStart: Int)

/**
 * The WhatsApp setup window (SIM method): the companion allows it, a person on the phone starts it, it lasts 30 minutes plus whatever the companion
 * added, and it ends on Done, on "End now", when the companion cancels the permission, at the end of the time, or at a reboot. Pure logic, so it is tested.
 */
object SetupWindow {
    const val MINUTES = 30
    private const val MIN_MS = 60_000L

    /** The window can be started now: the SIM method is chosen, the companion allowed it and the permission has not run out. Mode checks are the caller's. */
    fun canStart(w: WaConfig?, now: Long): Boolean = w != null && w.setup == "sim" && w.setupWindow != null && now < w.setupWindow.allowedUntil

    fun start(w: WaConfig?, now: Long, elapsed: Long): SetupRun? =
        if (canStart(w, now)) SetupRun(now, elapsed, w!!.setupWindow!!.extraMin, w.setupWindow!!.endRev) else null

    /** When the window ends, on the wall clock, or null if it is not running (ended by the companion, cancelled, or a reboot happened). */
    fun until(w: WaConfig?, run: SetupRun?, now: Long, elapsed: Long): Long? {
        if (run == null || w == null || w.setup != "sim") return null
        val a = w.setupWindow ?: return null
        if (a.endRev != run.endRevAtStart || elapsed < run.startedElapsed) return null
        val end = run.startedAt + (MINUTES + maxOf(0, a.extraMin - run.extraAtStart)) * MIN_MS
        return if (now < end) end else null
    }
}
