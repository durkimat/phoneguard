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
        lastUse = System.currentTimeMillis(); ctx = c.applicationContext
        if (vis != null || failed) return
        try {
            val v = Visualizer(0)
            v.enabled = false
            try { v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED } catch (_: Throwable) { } // the default scales the data up to fill the range, so quiet music looked as loud as loud
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

    // Fixed scale, so a quiet song looks quiet and a loud one fills the bars (following the loudest band made everything look like rock)
    private const val BOTTOM_DB = -50f
    private const val TOP_DB = 10f
    private var lastLog = 0L
    private var ctx: Context? = null
    /** The 8-bit waveform's own rounding noise is about -60 dB; anything under this is silence, whatever the lift would make of it. */
    private const val GATE_DB = -54f
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
            val raw = (20 * Math.log10(amp + 1e-6)).toFloat()
            db[b] = if (raw < GATE_DB) -200f else raw + b * 0.6f
        }
        val loud = db.max().coerceAtLeast(-100f)
        if (now - lastLog > 5000) { lastLog = now; ctx?.let { Log.log(it, "spectrum: loudest band ${"%.0f".format(loud)} dB bands " + db.joinToString(" ") { "%.0f".format(it) }) } }
        for (b in 0 until BARS) {
            val v = Math.pow(((db[b] - BOTTOM_DB) / (TOP_DB - BOTTOM_DB)).coerceIn(0f, 1f).toDouble(), 1.8).toFloat() // a curve, so middling levels sit low and real peaks stand out
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
