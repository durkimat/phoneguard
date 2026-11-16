package io.github.durkimat.phoneguard.keys

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.durkimat.phoneguard.incall.CallState
import io.github.durkimat.phoneguard.launcher.LauncherActivity
import io.github.durkimat.phoneguard.launcher.LauncherData
import io.github.durkimat.phoneguard.voice.VoiceCapture
import io.github.durkimat.phoneguard.voice.VoiceReply
import io.github.durkimat.phoneguard.voice.VoiceWav
import io.github.durkimat.phoneguard.companion.WorkerLink

/**
 * Hold the side button and speak: the recording runs while the button is held, letting go sends it. When a text box has the focus (ours or another app's) the
 * words are typed into it (dictation); otherwise it is a spoken command. The "Listening" panel is drawn first (a visible window of ours, which is also
 * what lets the microphone open while another app is in front), then the recording starts. Audio stays in memory.
 */
object F2Speak {
    private val main = Handler(Looper.getMainLooper())
    private var cap: VoiceCapture? = null
    @Volatile private var busy = false
    private var dictation = true

    /** The hold does anything only where voice is on for the mode, the phone is paired and awake and unlocked, and not in a call. */
    fun allowed(c: Context): Boolean {
        if (busy) return false
        val locked = try { c.getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Exception) { false }
        return !locked && CallState.info == null && WorkerLink.isPaired(c) && (LauncherData.voiceTypingAllowed(c) || LauncherData.voiceCommandsAllowed(c))
    }

    private fun overlay(text: String, forMs: Long = 0) { F2Service.cover(true, text); if (forMs > 0) main.postDelayed({ if (!busy) F2Service.cover(false) }, forMs) }

    fun start(c: Context) {
        if (busy) return
        val inOurs = F2Service.frontPackage() == c.packageName
        dictation = if (inOurs) LauncherActivity.dictationTargetOpen() else F2Service.editableFocused()
        val ok = if (dictation) LauncherData.voiceTypingAllowed(c) else LauncherData.voiceCommandsAllowed(c)
        if (!ok) return // voice is off here: the hold does nothing
        if (!io.github.durkimat.phoneguard.voice.VoiceNet.online(c)) { overlay(VoiceReply.NO_NETWORK, 1500); return }
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { overlay("Microphone is off", 1500); return }
        busy = true
        overlay(if (dictation) "Listening..." else "Listening for a command...")
        try { c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) { }
        val c0 = VoiceCapture(if (dictation) VoiceWav.MAX_DICTATION_MS else 8_000, { }, { pcm -> main.post { finish(c, pcm) } })
        cap = c0; main.postDelayed({ if (cap === c0) c0.start() }, 150) // after the panel and the buzz, so they are not in the recording
    }

    fun stop() { cap?.stop() }

    private fun finish(c: Context, pcm: ByteArray?) {
        cap = null
        if (pcm == null) { busy = false; overlay("Microphone not available", 1500); return }
        if (VoiceWav.millis(pcm.size) < VoiceWav.MIN_MS) { busy = false; F2Service.cover(false); return }
        overlay("Sending...")
        val wav = VoiceWav.wav(pcm); val mode = if (dictation) "dictation" else "command"
        Thread {
            val r = WorkerLink.voiceTranscribe(c, wav, mode)
            main.post {
                busy = false
                when (r) {
                    is VoiceReply.Heard -> deliver(c, r)
                    VoiceReply.Nothing -> overlay(VoiceReply.DIDNT_CATCH, 1500)
                    is VoiceReply.Refused -> overlay(r.shown, 2000)
                    is VoiceReply.Failed -> overlay(r.shown, 2000)
                }
            }
        }.start()
    }

    private fun deliver(c: Context, r: VoiceReply.Heard) {
        if (dictation) {
            val inOurs = F2Service.frontPackage() == c.packageName
            val ok = if (inOurs) LauncherActivity.dictate(r.text) else F2Service.insertText(r.text)
            if (ok) F2Service.cover(false) else overlay("Couldn't type that here", 2000)
        } else {
            io.github.durkimat.phoneguard.voice.VoiceCommands.run(c, r, ::overlay)
        }
    }
}
