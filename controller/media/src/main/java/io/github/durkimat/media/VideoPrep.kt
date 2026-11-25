package io.github.durkimat.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer

/**
 * Gets a video ready to send: at most 5 minutes, re-encoded as H.264/AAC scaled to 720p (Media3 Transformer, Apache-2.0), then copied once more
 * without its location metadata. A result over 100 MB is refused. [run] may be called from any thread; [onDone] is called on the main thread.
 */
object VideoPrep {
    data class Done(val file: File?, val seconds: Int, val width: Int, val height: Int, val error: String?)

    fun run(c: Context, uri: Uri, outDir: File, onProgress: (Int) -> Unit, onDone: (Done) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val app = c.applicationContext
        val info = probe(app, uri)
        Rich.videoRefusal(info.seconds, 0)?.let { main.post { onDone(Done(null, 0, 0, 0, it)) }; return }
        outDir.mkdirs()
        val coded = File(outDir, "v-${System.nanoTime()}.mp4"); val clean = File(outDir, "send-${System.nanoTime()}.mp4")
        main.post {
            try {
                val scale = Rich.videoScale(info.w, info.h, info.rotation)
                val effects = if (scale == null) emptyList() else listOf(if (scale.first) Presentation.createForWidthAndHeight(scale.second, info.longSide(scale.second), Presentation.LAYOUT_SCALE_TO_FIT) else Presentation.createForHeight(scale.second))
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(), effects)).build()
                lateinit var t: Transformer
                val poll = object : Runnable { override fun run() { val h = ProgressHolder(); if (t.getProgress(h) != Transformer.PROGRESS_STATE_NOT_STARTED) { onProgress(h.progress); main.postDelayed(this, 500) } } }
                t = Transformer.Builder(app).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        main.removeCallbacks(poll)
                        Thread {
                            val r = try { strip(coded, clean); coded.delete(); val p = probe(app, Uri.fromFile(clean)); val e = Rich.videoRefusal(p.seconds, clean.length()); if (e != null) { clean.delete(); Done(null, 0, 0, 0, e) } else Done(clean, p.seconds, p.dw, p.dh, null) }
                            catch (e: Throwable) { coded.delete(); clean.delete(); Done(null, 0, 0, 0, "That video could not be prepared.") }
                            main.post { onDone(r) }
                        }.start()
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        main.removeCallbacks(poll); coded.delete(); onDone(Done(null, 0, 0, 0, "That video could not be prepared."))
                    }
                }).build()
                t.start(item, coded.absolutePath)
                main.postDelayed(poll, 300)
            } catch (e: Throwable) { onDone(Done(null, 0, 0, 0, "That video could not be prepared.")) }
        }
    }

    private class Info(val seconds: Int, val w: Int, val h: Int, val rotation: Int) {
        val dw get() = if (rotation % 180 != 0) h else w
        val dh get() = if (rotation % 180 != 0) w else h
        /** For a portrait video scaled to width [width]: the matching height. */
        fun longSide(width: Int) = (dh.toLong() * width / maxOf(1, dw)).toInt().let { it - it % 2 }
    }

    private fun probe(c: Context, uri: Uri): Info = try {
        MediaMetadataRetriever().use {
            it.setDataSource(c, uri)
            fun i(k: Int) = it.extractMetadata(k)?.toIntOrNull() ?: 0
            Info(((it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt(), i(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), i(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), i(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
        }
    } catch (_: Throwable) { Info(0, 0, 0, 0) }

    /** Copies the video and audio samples into a new file without writing any location (MediaMuxer only writes one when told to). */
    fun strip(inFile: File, out: File) {
        val ex = MediaExtractor(); ex.setDataSource(inFile.absolutePath)
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                map[i] = mux.addTrack(f); ex.selectTrack(i)
                if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_ROTATION)) mux.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION))
            }
            mux.start()
            val buf = ByteBuffer.allocate(1 shl 20); val bi = MediaCodec.BufferInfo()
            while (true) {
                bi.size = ex.readSampleData(buf, 0); if (bi.size < 0) break
                bi.presentationTimeUs = ex.sampleTime; bi.flags = ex.sampleFlags; bi.offset = 0
                mux.writeSampleData(map[ex.sampleTrackIndex] ?: break, buf, bi); ex.advance()
            }
        } finally { try { mux.stop() } catch (_: Throwable) { }; mux.release(); ex.release() }
    }
}
