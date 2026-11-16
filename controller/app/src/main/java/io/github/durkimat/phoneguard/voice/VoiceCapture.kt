package io.github.durkimat.phoneguard.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records a few seconds of speech from the microphone (16 kHz mono 16-bit) while a key is held. The sound is kept in memory only and is never written to a
 * file. [stop] ends the recording and delivers it; [cancel] throws it away. Runs its own thread; [onLevel] and [onDone] are called from that thread.
 */
class VoiceCapture(private val maxMs: Int, private val onLevel: (Int) -> Unit, private val onDone: (ByteArray?) -> Unit) {
    private val stopped = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @SuppressLint("MissingPermission") // granted to Phone Guard by the image and the device owner
    fun start() {
        Thread({
            val out = ByteArrayOutputStream()
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(VoiceWav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, VoiceWav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                if (rec.state != AudioRecord.STATE_INITIALIZED) { onDone(null); return@Thread }
                rec.startRecording()
                val buf = ByteArray(3200) // 100 ms
                val limit = maxMs.toLong() * VoiceWav.BYTES_PER_SECOND / 1000
                while (!stopped.get() && out.size() < limit) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    onLevel(VoiceWav.level(buf, n))
                }
            } catch (_: Exception) {
                onDone(null); return@Thread
            } finally {
                try { rec?.stop() } catch (_: Exception) { }
                try { rec?.release() } catch (_: Exception) { }
            }
            onDone(if (cancelled.get()) null else out.toByteArray())
        }, "voice-capture").start()
    }

    fun stop() { stopped.set(true) }
    fun cancel() { cancelled.set(true); stopped.set(true) }
}
