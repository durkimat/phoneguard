package io.github.durkimat.phoneguard.notes

import android.app.NotificationManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.alarm.RingState
import io.github.durkimat.phoneguard.alarm.SoundLevels
import io.github.durkimat.phoneguard.alarm.ToneStore

/**
 * What a new text or WhatsApp message does, the classic way, decided by us and not by the messaging app's channel: the screen lights for its
 * usual timeout, the child's message tone plays and/or the phone buzzes (the Ringer mode choice, at the Messages level), and a quiet mode
 * (Silent, Do Not Disturb, a school or night mode that sets the ringer) keeps all of it quiet, screen included.
 * During a call the screen is already on and the tone would cut across the voice, so it is one short buzz at most; while an alarm or timer
 * rings the alarm has the phone and the message just waits in the Notifications list.
 */
object MessageAlert {
    private var last = 0L

    fun fire(c: Context, slot: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) { if (now - last < 3_000L) return; last = now } // one alert for a burst
        try {
            if (RingState.sounding) { Log.log(c, "message: alert skipped (an alarm is ringing)"); return }
            val nm = c.getSystemService(NotificationManager::class.java)
            val dnd = try { nm.currentInterruptionFilter.let { it == NotificationManager.INTERRUPTION_FILTER_NONE || it == NotificationManager.INTERRUPTION_FILTER_ALARMS } } catch (_: Exception) { false }
            val eff = SoundLevels.effective(c)
            if (dnd || eff == 3) { Log.log(c, "message: kept quiet (${if (dnd) "do not disturb" else "silent"})"); return }
            if (io.github.durkimat.phoneguard.incall.CallState.info != null) {
                if (SoundLevels.buzzes(c, SoundLevels.MSG)) try { c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) { }
                Log.log(c, "message: during a call, one short buzz only"); return
            }
            ScreenWake.wake(c) // lights the screen for about fifteen seconds unless someone touches the phone
            ToneStore.notify(c, slot) // the tone and the buzz, by the ringer choice and the Messages level
            Log.log(c, "message: alert ($slot, choice $eff)")
        } catch (e: Exception) { Log.log(c, "message: alert failed ${e.javaClass.simpleName}") }
    }
}
