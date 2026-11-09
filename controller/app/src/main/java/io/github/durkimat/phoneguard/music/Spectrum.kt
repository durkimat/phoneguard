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
                override fun onWaveFormDataCapture(vz: Visualizer?, wf: ByteArray?, rate: Int) { if (wf != null) feed(wf, rate) }
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) {}
            }, Visualizer.getMaxCaptureRate(), true, false)
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

    private var ref = -40f // loudest recent band in dB below full scale; the display follows it but never below this, so silence stays empty
    private const val REF_FLOOR = -45f
    private const val DYNAMIC_DB = 34f
    private const val LOW_HZ = 50.0
    private const val HIGH_HZ = 16000.0
    private val window = DoubleArray(1024) { 0.5 - 0.5 * Math.cos(2 * Math.PI * it / 1023) }

    /**
     * The phone's FFT output is only 8 bits and clips on strong bass, which made the low bars move whatever the music did, so the
     * FFT is done here from the raw waveform (unsigned 8-bit samples) with a Hann window. The 16 bars are spaced evenly by pitch
     * (50 Hz to 16 kHz); each is its RMS level in dB with a lift of about 1.5 dB per bar (music has far less energy up high),
     * scaled to the loudest recent band. Bars rise at once and fall steadily.
     */
    private fun feed(wave: ByteArray, rateMilliHz: Int) {
        val n = wave.size
        if (n != 1024) return
        val re = DoubleArray(n) { ((wave[it].toInt() and 0xFF) - 128) / 128.0 * window[it] }
        val im = DoubleArray(n)
        fft(re, im)
        val bins = n / 2
        val hzPerBin = (if (rateMilliHz < 8_000_000) 44_100_000 else rateMilliHz) / 1000.0 / n // some builds pass the capture rate here
        val now = System.currentTimeMillis()
        val db = FloatArray(BARS)
        for (b in 0 until BARS) {
            val loHz = LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, b / BARS.toDouble())
            val hiHz = LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, (b + 1) / BARS.toDouble())
            var from = (loHz / hzPerBin).toInt().coerceIn(1, bins - 1)
            var to = (hiHz / hzPerBin).toInt().coerceIn(from, bins - 1)
            if (to == from) { from = ((loHz + hiHz) / 2 / hzPerBin).toInt().coerceIn(1, bins - 1); to = from } // narrower than a bin: the one at its centre
            // the strongest bin, not the average: a voice or an instrument is a few sharp lines, and averaging them with the
            // empty bins around them flattened the middle bars into the same muddle
            var top = 0.0
            for (k in from..to) top = max(top, re[k] * re[k] + im[k] * im[k])
            val amp = Math.sqrt(top) / (n / 4.0) // 1.0 is a full-scale sine
            db[b] = (20 * Math.log10(amp + 1e-6)).toFloat() + b * 1.5f
        }
        val loud = db.max()
        ref = max(REF_FLOOR, if (loud > ref) loud else ref - 0.3f)
        for (b in 0 until BARS) {
            val v = Math.pow(((db[b] - (ref - DYNAMIC_DB)) / DYNAMIC_DB).coerceIn(0f, 1f).toDouble(), 1.6).toFloat() // a curve, so middling levels sit low and real peaks stand out
            level[b] = if (v > level[b]) v else max(v, level[b] - 0.12f)
            if (level[b] >= peak[b]) { peak[b] = level[b]; peakAge[b] = now }
            else if (now - peakAge[b] > 450) peak[b] = max(level[b], peak[b] - 0.04f)
        }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
