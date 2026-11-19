package io.github.durkimat.phoneguard.incall

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.alarm.LoopPlayer
import io.github.durkimat.phoneguard.alarm.SoundLevels
import io.github.durkimat.phoneguard.alarm.ToneStore
import io.github.durkimat.phoneguard.alarm.Tones

/**
 * Rings an incoming call ourselves (the manifest tells Telecom the in-call service does the ringing). The child's ringtone loops on the ring
 * channel at our own Ringtone level, read live, so the side keys change it while it rings; the phone's vibration follows the ringer mode.
 * Ringer mode Silent and Do Not Disturb (none/alarms only) keep it quiet, as the system's ringer would. The phone's own sound is never
 * played: with no tune picked the default one is used.
 */
class CallRinger(private val c: Context) {
    private var player: LoopPlayer? = null
    private var vibrator: Vibrator? = null
    val ringing get() = player != null || vibrator != null

    fun start() {
        if (ringing) return
        val am = c.getSystemService(AudioManager::class.java)
        val nm = c.getSystemService(NotificationManager::class.java)
        val eff = SoundLevels.effective(c)
        val dnd = try { nm.currentInterruptionFilter.let { it == NotificationManager.INTERRUPTION_FILTER_NONE || it == NotificationManager.INTERRUPTION_FILTER_ALARMS } } catch (_: Exception) { false }
        if (dnd || eff == 3) { Log.log(c, "incall: ring kept quiet (ringer ${if (dnd) "dnd" else "silent"})"); return }
        if (SoundLevels.sounds(c)) {
            try {
                SoundLevels.pin(c)
                val pick = ToneStore.pick(c, ToneStore.RING).let { if (it == ToneStore.STOCK) Tones.DEFAULT else it }
                val pcm = Tones.render(Tones.byId(pick), amp = ToneStore.RING_AMP, style = ToneStore.style(c))
                val first = SoundLevels.ringLive.takeIf { it >= 0 } ?: SoundLevels.pos(c, SoundLevels.RING)
                if (SoundLevels.ringLive < 0) SoundLevels.ringLive = first
                player = LoopPlayer(c, pcm, { SoundLevels.gainAt(SoundLevels.ringLive).toFloat() }, 1200, AudioAttributes.USAGE_NOTIFICATION_RINGTONE).also { it.start() }
            } catch (e: Exception) { Log.log(c, "incall: ring tune failed ${e.javaClass.simpleName}"); player = null }
        }
        if (SoundLevels.buzzes(c)) try {
            val v = c.getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            vibrator = v
        } catch (e: Exception) { Log.log(c, "incall: vibrate failed ${e.javaClass.simpleName}") }
        Log.log(c, "incall: ringing (choice $eff, tune ${player != null}, buzz ${vibrator != null}, level ${SoundLevels.ringLive})")
    }

    fun stop() {
        try { player?.stop() } catch (_: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (_: Exception) { }
        vibrator = null
    }
}
