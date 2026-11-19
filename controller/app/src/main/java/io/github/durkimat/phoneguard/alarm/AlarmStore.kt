package io.github.durkimat.phoneguard.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Log
import java.time.ZoneId

/**
 * Saves the alarms, the timer and the Clock settings (in device-protected storage, so they still ring after a restart before the first
 * unlock) and keeps the system alarm clock in step: one exact alarm per alarm, one for a snooze, one for the timer.
 */
object AlarmStore {
    private fun sp(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun alarms(c: Context): List<Alarm> = AlarmMath.decode(sp(c).getString("list", "") ?: "")
    fun saveAlarms(c: Context, l: List<Alarm>) { sp(c).edit().putString("list", AlarmMath.encode(l)).apply(); reschedule(c) }
    fun prefs(c: Context) = ClockPrefs(sp(c).getInt("volume", 5).coerceIn(1, AlarmMath.MAX_VOLUME), sp(c).getBoolean("crescendo", false))
    fun savePrefs(c: Context, p: ClockPrefs) { sp(c).edit().putInt("volume", p.volume).putBoolean("crescendo", p.crescendo).apply() }
    fun timer(c: Context) = TimerState.decode(sp(c).getString("timer", "") ?: "")
    fun saveTimer(c: Context, t: TimerState) { sp(c).edit().putString("timer", t.encode()).apply(); reschedule(c) }
    fun stopwatch(c: Context): Stopwatch { val p = (sp(c).getString("sw", "") ?: "").split(','); return Stopwatch(p.getOrNull(0)?.toLongOrNull() ?: 0, p.getOrNull(1)?.toLongOrNull() ?: 0, p.drop(2).mapNotNull { it.toLongOrNull() }) }
    fun saveStopwatch(c: Context, s: Stopwatch) { sp(c).edit().putString("sw", (listOf(s.before, s.startedAt) + s.laps).joinToString(",")).apply() }
    fun snoozeAt(c: Context) = sp(c).getLong("snoozeAt", 0)
    fun snoozeTone(c: Context) = sp(c).getString("snoozeTone", Tones.DEFAULT) ?: Tones.DEFAULT
    fun snoozeVol(c: Context) = sp(c).getInt("snoozeVol", 5)
    fun snoozeGradual(c: Context) = sp(c).getBoolean("snoozeGrad", false)
    fun setSnooze(c: Context, at: Long, tone: String, vol: Int = 5, gradual: Boolean = false) { sp(c).edit().putLong("snoozeAt", at).putString("snoozeTone", tone).putInt("snoozeVol", vol).putBoolean("snoozeGrad", gradual).apply(); reschedule(c) }
    fun clearSnooze(c: Context) { if (snoozeAt(c) != 0L) { sp(c).edit().putLong("snoozeAt", 0).apply(); reschedule(c) } }

    private const val SNOOZE_CODE = 200
    private const val TIMER_CODE = 300

    private fun pi(c: Context, code: Int, kind: String, id: Int = 0, tone: String = ""): PendingIntent =
        PendingIntent.getBroadcast(c, code, Intent(c, AlarmFire::class.java).putExtra("kind", kind).putExtra("id", id).putExtra("tone", tone), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Puts every exact alarm right: called after any change, after a restart and when the clock or time zone changes. */
    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val now = Clock.now(); val zone = ZoneId.systemDefault()
        val list = alarms(c)
        try {
            for (id in 1..AlarmMath.MAX_ALARMS) {
                val a = list.firstOrNull { it.id == id }
                val at = a?.let { AlarmMath.next(it, now, zone) }
                if (at == null) am.cancel(pi(c, 100 + id, "alarm", id))
                else am.setAlarmClock(AlarmManager.AlarmClockInfo(at, PendingIntent.getActivity(c, 150 + id, Intent(c, io.github.durkimat.phoneguard.launcher.LauncherActivity::class.java), PendingIntent.FLAG_IMMUTABLE)), pi(c, 100 + id, "alarm", id, a.tone))
            }
            val sn = snoozeAt(c)
            if (sn > now) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sn, pi(c, SNOOZE_CODE, "snooze", 0, snoozeTone(c))) else am.cancel(pi(c, SNOOZE_CODE, "snooze"))
            val t = timer(c)
            if (t.running) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAt, pi(c, TIMER_CODE, "timer")) else am.cancel(pi(c, TIMER_CODE, "timer"))
        } catch (e: Exception) { Log.log(c, "alarms: schedule failed ${e.javaClass.simpleName}") }
    }

    /** A once-only alarm that has rung switches itself off. */
    fun rang(c: Context, id: Int) {
        val l = alarms(c)
        val a = l.firstOrNull { it.id == id } ?: return
        if (a.days == 0) saveAlarms(c, l.map { if (it.id == id) it.copy(on = false) else it }) else reschedule(c)
    }
}
