package io.github.durkimat.phoneguard.music

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import io.github.durkimat.phoneguard.Log
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Levels for the Music screen's spectrum analyser, taken from what this phone is playing (the whole output mix, so any app).
 * It only listens while the screen asks for it: each draw calls [use], and a second after the last draw it lets go.
 * Music playing on another device makes no sound here, so the bars simply fall.
 */
object Spectrum {
    const val BARS = 16
    /** Bar heights and held peaks, 0..1. Read by the drawing code. */
    val level = FloatArray(BARS)
    val peak = FloatArray(BARS)
    private val peakAge = LongArray(BARS)

    private val main = Handler(Looper.getMainLooper())
    private var vis: Visualizer? = null
    private var lastUse = 0L
    private var failed = false
    private val watch = object : Runnable {
        override fun run() { if (System.currentTimeMillis() - lastUse > 1000) stop() else main.postDelayed(this, 500) }
    }

    @Synchronized fun use(c: Context) {
        lastUse = System.currentTimeMillis()
        if (vis != null || failed) return
        try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, wf: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) feed(fft) }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            vis = v
            main.postDelayed(watch, 500)
            Log.log(c, "spectrum: listening")
        } catch (e: Throwable) { failed = true; Log.log(c, "spectrum: not available: ${e.javaClass.simpleName} ${e.message}") }
    }

    @Synchronized fun stop() {
        main.removeCallbacks(watch)
        try { vis?.enabled = false; vis?.release() } catch (_: Throwable) { }
        vis = null
        for (i in 0 until BARS) { level[i] = 0f; peak[i] = 0f }
    }

    /** Folds the FFT (pairs of real and imaginary bytes) into log-spaced bands, rises at once and falls gently. */
    private fun feed(fft: ByteArray) {
        val bins = fft.size / 2
        val now = System.currentTimeMillis()
        for (b in 0 until BARS) {
            // bands from about 60 Hz up, spaced by pitch
            val lo = 1 + (Math.pow(bins.toDouble() - 1, b / BARS.toDouble())).toInt().coerceAtMost(bins - 1)
            val hi = max(lo + 1, 1 + (Math.pow(bins.toDouble() - 1, (b + 1) / BARS.toDouble())).toInt()).coerceAtMost(bins)
            var m = 0f
            for (k in lo until hi) {
                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                m = max(m, sqrt(re * re + im * im))
            }
            val v = (ln(1f + m) / ln(1f + 181f)).coerceIn(0f, 1f) // 181 is the largest a byte pair gives
            level[b] = if (v > level[b]) v else max(v, level[b] - 0.08f)
            if (level[b] >= peak[b]) { peak[b] = level[b]; peakAge[b] = now }
            else if (now - peakAge[b] > 500) peak[b] = max(level[b], peak[b] - 0.03f)
        }
    }
}
