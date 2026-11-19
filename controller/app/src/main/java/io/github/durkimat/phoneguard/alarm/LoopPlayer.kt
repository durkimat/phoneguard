package io.github.durkimat.phoneguard.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Context

/**
 * Plays a tune over and over on the alarm stream, with OUR OWN gain applied to the samples a few times a second. The stream's own index is not relied
 * on (on this phone it sits at the top, and the track volume was not followed), so the level a person hears is exactly what [gain] says, 0..1.
 * Used by the ringing alarm and by the volume row's preview.
 */
class LoopPlayer(private val c: Context, tune: ShortArray, private val gain: () -> Float, private val gapMs: Int = 700) {
    private val pcm: ShortArray = tune.copyOf(tune.size + 16 * gapMs) // a breath of silence between repeats
    @Volatile private var run = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var savedVol = -1

    fun start() {
        if (run) return
        run = true
        val am = c.getSystemService(AudioManager::class.java)
        try { savedVol = am.getStreamVolume(AudioManager.STREAM_ALARM); am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) } catch (_: Exception) { }
        val rate = 16000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t; t.play()
        thread = Thread({
            val buf = ShortArray(800) // 50 ms: the gain follows a ramp smoothly
            var at = 0
            while (run) {
                val g = gain().coerceIn(0f, 1f)
                for (i in buf.indices) { buf[i] = (pcm[at] * g).toInt().toShort(); at = (at + 1) % pcm.size }
                try { if (t.write(buf, 0, buf.size) < 0) break } catch (_: Exception) { break }
            }
        }, "tune-loop").also { it.start() }
    }

    fun stop() {
        run = false
        try { thread?.join(500) } catch (_: Exception) { }
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
        track = null
        if (savedVol >= 0) try { c.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0) } catch (_: Exception) { }
        savedVol = -1
    }
}

/** The loudness curve shared by the ring and the preview: the volume step (1..7) squared, so the low steps are quiet and the top is the full tune. */
object AlarmGain {
    fun level(vol: Int) = (vol.coerceIn(1, AlarmMath.MAX_VOLUME) / AlarmMath.MAX_VOLUME.toFloat()).let { it * it }
    /** A gradual alarm starts at a seventh of its level and reaches it over a minute. */
    fun ramp(msSinceStart: Long, gradual: Boolean) = if (gradual) (0.12f + 0.88f * (msSinceStart / 60_000f)).coerceAtMost(1f) else 1f
}
