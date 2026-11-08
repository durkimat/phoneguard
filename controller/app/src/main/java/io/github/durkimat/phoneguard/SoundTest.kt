package io.github.durkimat.phoneguard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * A short two-note tone through a chosen route, so the loudspeaker and the earpiece can be tried without a SIM or a call:
 * 0 the loudspeaker as music uses it, 1 the call speaker (speakerphone), 2 the earpiece as a call uses it.
 */
object SoundTest {
    val routes = listOf("Loudspeaker (music)", "Call speaker", "Earpiece")
    @Volatile private var busy = false

    fun play(c: Context, route: Int) {
        if (busy) return
        busy = true
        Thread {
            val am = c.getSystemService(AudioManager::class.java)
            val oldMode = am.mode; val oldSpeaker = am.isSpeakerphoneOn
            try {
                val call = route != 0
                if (call) { am.mode = AudioManager.MODE_IN_COMMUNICATION; am.isSpeakerphoneOn = route == 1 }
                val rate = 16000
                val n = rate * 3
                val pcm = ShortArray(n) { i ->
                    val t = i / rate.toDouble()
                    val f = if (((t * 2).toInt() % 2) == 0) 440.0 else 660.0 // two alternating notes, half a second each
                    val fade = minOf(1.0, minOf(i, n - i) / (rate * 0.02))
                    (sin(2 * PI * f * t) * 0.8 * fade * Short.MAX_VALUE).toInt().toShort()
                }
                val attrs = AudioAttributes.Builder()
                    .setUsage(if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (call) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC).build()
                val fmt = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(pcm, 0, n); track.play()
                Thread.sleep(3200)
                track.stop(); track.release()
            } catch (e: Exception) { Log.log(c, "sound test: ${e.javaClass.simpleName} ${e.message?.take(60)}") }
            finally { am.mode = oldMode; am.isSpeakerphoneOn = oldSpeaker; busy = false }
        }.start()
    }
}
