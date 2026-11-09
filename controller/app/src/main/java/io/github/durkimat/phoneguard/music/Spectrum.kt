package io.github.durkimat.phoneguard.music

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import io.github.durkimat.phoneguard.Log
import kotlin.math.max

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
            v.captureSize = Visualizer.getCaptureSizeRange()[1] // the largest: 1024 gives about 21 Hz per bin, the smallest would give 340 Hz and no bass detail
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, wf: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) feed(fft, rate) }
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

    private var ref = -20f // loudest recent band, in dB: the display scales to it so quiet and loud music both fill the bars
    private val DYNAMIC_DB = 42f
    private const val LOW_HZ = 50.0
    private const val HIGH_HZ = 16000.0

    /**
     * Folds the FFT (byte 0 and 1 are DC and Nyquist, then a real and imaginary byte per bin) into bands spaced evenly by pitch
     * (50 Hz to 16 kHz, so each bar is a similar slice of the musical range). Each band is its RMS level in dB, with a lift of
     * about 2 dB per bar because music carries far less energy up high, and the scale follows the loudest recent band.
     * Bars rise at once and fall steadily.
     */
    private fun feed(fft: ByteArray, rateMilliHz: Int) {
        val bins = fft.size / 2
        val hzPerBin = rateMilliHz / 1000.0 / 2 / bins
        val now = System.currentTimeMillis()
        val db = FloatArray(BARS)
        for (b in 0 until BARS) {
            val loHz = LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, b / BARS.toDouble())
            val hiHz = LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, (b + 1) / BARS.toDouble())
            val centre = ((loHz + hiHz) / 2 / hzPerBin).toInt()
            val lo = (loHz / hzPerBin).toInt().coerceIn(1, bins - 1)
            val hi = max((hiHz / hzPerBin).toInt(), lo).coerceAtMost(bins - 1)
            var sum = 0.0; var n = 0
            // a band narrower than a bin takes the bin at its centre
            val from = if (hi - lo < 1) centre.coerceIn(1, bins - 1) else lo
            val to = if (hi - lo < 1) from else hi
            for (k in from..to) { val re = fft[2 * k].toDouble(); val im = fft[2 * k + 1].toDouble(); sum += re * re + im * im; n++ }
            db[b] = (10 * Math.log10(sum / n + 1e-3)).toFloat() + b * 2f
        }
        val loud = db.max()
        ref = if (loud > ref) loud else max(loud, ref - 0.4f)
        for (b in 0 until BARS) {
            val v = ((db[b] - (ref - DYNAMIC_DB)) / DYNAMIC_DB).coerceIn(0f, 1f)
            level[b] = if (v > level[b]) v else max(v, level[b] - 0.12f)
            if (level[b] >= peak[b]) { peak[b] = level[b]; peakAge[b] = now }
            else if (now - peakAge[b] > 450) peak[b] = max(level[b], peak[b] - 0.04f)
        }
    }
}
