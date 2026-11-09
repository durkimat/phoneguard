package io.github.durkimat.phoneguard

import android.app.Notification
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import io.github.durkimat.phoneguard.core.Engine

/**
 * Power and memory saver: an app nobody has used for the mode's idle time is stopped, so it stops using battery and memory.
 *
 * What is never stopped: protected apps (phone, messages, settings and so on), the apps a parent marks "keep running" in HA
 * (WhatsApp, the Clock and DAVx5 by default), an app that is playing or buffering music, and an app with an ongoing
 * foreground notification that is not a media player (a download, navigation, a recording, a call). Stopping is done by
 * hiding and at once showing the app again (a device owner may), which ends its processes even when it has a media
 * service running; Android starts the app again whenever something really needs it (a message, an alarm, a sync).
 */
object IdleReaper {
    /** Kept running unless the parent's list says otherwise: they must be ready to ring, deliver or sync at any moment. */
    val DEFAULT_KEEP = setOf("com.whatsapp", "com.android.deskclock", "com.dumbermini.davx")
    const val EVERY_MS = 3 * 60_000L
    private const val AGAIN_MS = 30 * 60_000L

    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("reaper", Context.MODE_PRIVATE)

    /** Whether an app counts as idle (a pure rule, tested without a phone). */
    fun idle(nowMs: Long, lastUsedMs: Long, lastPlayedMs: Long, idleMin: Int): Boolean =
        idleMin > 0 && nowMs - maxOf(lastUsedMs, lastPlayedMs) >= idleMin * 60_000L

    /** Stop an app now: hide it for a moment (Android ends all its processes) and show it again. Returns whether Android accepted it. */
    fun stop(c: Context, pkg: String): Boolean = try {
        val dpm = Dpm.dpm(c); val admin = Dpm.admin(c)
        if (dpm.isApplicationHidden(admin, pkg)) false // hidden by a mode: nothing of it runs anyway
        else {
            val ok = dpm.setApplicationHidden(admin, pkg, true)
            try { Thread.sleep(600) } finally { dpm.setApplicationHidden(admin, pkg, false) }
            ok
        }
    } catch (e: Exception) { Log.log(c, "idle stop $pkg: ${e.javaClass.simpleName}"); false }

    fun run(c: Context) {
        try {
            if (!Dpm.isOwner(c) || !Usage.allowed(c)) return
            val cfg = Store.config(c)
            val eff = Engine.effective(cfg, Store.boost(c), Clock.now())
            val min = Engine.modeDef(cfg, eff.modeId).idleMin
            if (min <= 0) return
            val now = System.currentTimeMillis()
            val p = prefs(c)
            val stats = c.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(now - 24 * 3_600_000L, now)
            val playing = playingPackages(c)
            val busy = busyPackages(c)
            val keep = (cfg?.keepRunning ?: DEFAULT_KEEP) + Protected.all(c, cfg)
            val stopped = mutableListOf<String>()
            val e = p.edit()
            for (pkg in playing) e.putLong("play_$pkg", now)
            for (pkg in Enforcer.launcherPackages(c)) {
                if (pkg in keep || pkg in playing || pkg in busy) continue
                val used = stats[pkg]?.lastTimeUsed ?: continue // not used in the last day: nothing of it is likely running
                val played = p.getLong("play_$pkg", 0)
                if (!idle(now, used, played, min)) continue
                if (now - p.getLong("stopped_$pkg", 0) < AGAIN_MS && p.getLong("stopped_$pkg", 0) > maxOf(used, played)) continue
                if (stop(c, pkg)) { e.putLong("stopped_$pkg", now); stopped += pkg }
            }
            e.apply()
            if (stopped.isNotEmpty()) Log.log(c, "idle apps stopped after $min min: ${stopped.joinToString()}")
        } catch (e: Exception) { Log.log(c, "idle saver: ${e.javaClass.simpleName}") }
    }

    /** Apps with a media session that is playing or buffering; when that cannot be read, the music apps we know are treated as playing. */
    private fun playingPackages(c: Context): Set<String> = try {
        c.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(c, io.github.durkimat.phoneguard.notes.NoteService::class.java))
            .filter { val s = it.playbackState?.state; s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING || s == PlaybackState.STATE_CONNECTING }
            .map { it.packageName }.toSet()
    } catch (_: Exception) { setOf("com.spotify.music") }

    /** Apps doing something visible in the background: an ongoing foreground-service notification that is not a media player. */
    private fun busyPackages(c: Context): Set<String> = try {
        (io.github.durkimat.phoneguard.notes.NoteService.instance?.activeNotifications ?: emptyArray())
            .filter { (it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 && it.notification.category != Notification.CATEGORY_TRANSPORT }
            .map { it.packageName }.toSet()
    } catch (_: Exception) { emptySet() }
}
