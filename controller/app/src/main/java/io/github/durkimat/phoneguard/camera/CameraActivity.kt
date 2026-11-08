package io.github.durkimat.phoneguard.camera

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.launcher.Key
import io.github.durkimat.phoneguard.launcher.KeyMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The themed camera: the viewfinder is drawn by us (dots or tones in the theme's colours), the photo or video that is saved
 * is the ordinary colour file. Works from the keypad; the OK key, the volume keys and a camera key all take the picture.
 */
class CameraActivity : AppCompatActivity() {
    private val model = CameraModel()
    private lateinit var view: CamView
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lastFrame = 0L
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = createDeviceProtectedStorageContext().getSharedPreferences("launcher", MODE_PRIVATE)
        view = CamView(this, model, prefs.getString("theme", null), prefs.getString("font", null))
        setContentView(view)
        if (!allowed(Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            flash("Camera needs permission")
        } else start()
    }

    private fun allowed(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (allowed(Manifest.permission.CAMERA)) start() else flash("No camera permission")
    }

    private fun start() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        try {
            p.unbindAll()
            val selector = if (model.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(480, 640)).build()
            analysis.setAnalyzer(exec) { img -> frame(img) }
            camera = if (model.mode == CamMode.PHOTO) {
                val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(flashMode()).build()
                imageCapture = cap; videoCapture = null
                p.bindToLifecycle(this, selector, analysis, cap)
            } else {
                val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                val vc = VideoCapture.withOutput(rec)
                videoCapture = vc; imageCapture = null
                p.bindToLifecycle(this, selector, analysis, vc)
            }
            applyZoomAndTorch()
        } catch (e: Exception) {
            Log.log(this, "camera: could not start: ${e.javaClass.simpleName} ${e.message}")
            flash("No camera")
        }
    }

    private fun flashMode() = when (model.flash) { FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF; FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO; FlashMode.ON -> ImageCapture.FLASH_MODE_ON }

    private fun applyZoomAndTorch() {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value
        val z = if (zs != null) model.zoom.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else model.zoom
        cam.cameraControl.setZoomRatio(z)
        cam.cameraControl.enableTorch(model.mode == CamMode.VIDEO && model.flash == FlashMode.ON)
        imageCapture?.flashMode = flashMode()
    }

    /** A live frame: turn it into the viewfinder picture. About twelve a second is plenty for a screen this small. */
    private fun frame(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrame < 80 || model.inGallery) return
            lastFrame = now
            val plane = img.planes[0]; val buf = plane.buffer; val rs = plane.rowStride; val ps = plane.pixelStride
            val w = img.width; val h = img.height
            val cells = Dither.sample(w, h, img.imageInfo.rotationDegrees, model.front, CamView.COLS, CamView.ROWS) { x, y -> buf.get(y * rs + x * ps).toInt() and 0xFF }
            view.show(bitmapOf(cells))
        } catch (_: Exception) { } finally { img.close() }
    }

    private fun bitmapOf(cells: IntArray): Bitmap {
        val px = if (model.look == Look.DITHER) {
            val ink = Dither.twoTone(cells, CamView.COLS, true)
            IntArray(ink.size) { if (ink[it]) view.ink or (0xFF shl 24) else view.lcd }
        } else Dither.ramp(cells, view.lcd, view.ink)
        return Bitmap.createBitmap(px, CamView.COLS, CamView.ROWS, Bitmap.Config.ARGB_8888)
    }

    private fun flash(msg: String, ms: Long = 1400) {
        model.notice = msg; view.postInvalidate()
        ui.removeCallbacks(clearNotice); ui.postDelayed(clearNotice, ms)
    }
    private val clearNotice = Runnable { model.notice = null; view.invalidate() }

    // ---- keys

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val mapped = when (ev.keyCode) {
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> Key.OK to -1
            else -> KeyMap.of(ev.keyCode)
        } ?: return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(model.onKey(mapped.first, mapped.second))
        return true
    }

    private fun perform(a: CamAction) {
        when (a) {
            CamAction.None -> {}
            CamAction.Redraw -> { applyZoomAndTorch(); view.invalidate() }
            CamAction.Shoot -> if (model.countdown > 0) countdownThenShoot() else takePhoto()
            CamAction.ToggleRecording -> toggleRecording()
            CamAction.Rebind -> { bind(); view.invalidate() }
            CamAction.OpenGallery -> openGallery()
            CamAction.ShowItem -> showItem()
            CamAction.Exit, CamAction.Home -> finish()
        }
    }

    private fun countdownThenShoot() {
        view.invalidate()
        ui.postDelayed({
            if (model.countdown <= 0) return@postDelayed // cancelled by a key
            model.countdown--
            if (model.countdown == 0) takePhoto() else countdownThenShoot()
            view.invalidate()
        }, 1000)
    }

    // ---- saving

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())

    private fun takePhoto() {
        val cap = imageCapture ?: return flash("No camera")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PG_" + stamp())
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        cap.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { flash("Saved") }
            override fun onError(e: ImageCaptureException) { Log.log(this@CameraActivity, "camera: save failed: ${e.imageCaptureError}"); flash("Not saved") }
        })
    }

    private fun toggleRecording() {
        val r = recording
        if (r != null) { r.stop(); return }
        val vc = videoCapture ?: return flash("No camera")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PG_" + stamp())
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val out = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, out)
        if (allowed(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { e ->
            when (e) {
                is VideoRecordEvent.Start -> { model.recording = true; model.recordingSeconds = 0; tickRecording() }
                is VideoRecordEvent.Finalize -> {
                    model.recording = false; recording = null
                    flash(if (e.hasError()) "Not saved" else "Saved")
                }
                else -> {}
            }
            view.invalidate()
        }
    }

    private fun tickRecording() {
        ui.postDelayed({
            if (!model.recording) return@postDelayed
            model.recordingSeconds++; view.blink = !view.blink; view.invalidate()
            if (model.recordingSeconds >= 600) recording?.stop() // ten minutes is the most one clip can be
            else tickRecording()
        }, 1000)
    }

    // ---- gallery

    private fun openGallery() {
        Thread {
            val list = ArrayList<MediaItem>()
            try {
                val uri = MediaStore.Files.getContentUri("external")
                val cols = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATE_ADDED)
                val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
                contentResolver.query(uri, cols, sel, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext() && list.size < 200) {
                        val id = c.getLong(0)
                        list.add(MediaItem(ContentUris.withAppendedId(uri, id).toString(), c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, c.getString(2) ?: "", c.getLong(3)))
                    }
                }
            } catch (e: Exception) { Log.log(this, "camera: gallery: ${e.javaClass.simpleName}") }
            ui.post { model.setGallery(list); showItem() }
        }.start()
    }

    private fun showItem() {
        val item = model.current
        val token = ++loadToken
        if (item == null) { view.show(null); return }
        Thread {
            try {
                val bmp = contentResolver.loadThumbnail(android.net.Uri.parse(item.uri), Size(640, 640), null)
                if (token != loadToken) return@Thread
                val (cols, rows) = if (bmp.width >= bmp.height) CamView.COLS to (CamView.COLS * bmp.height / bmp.width).coerceAtLeast(1) else (CamView.ROWS * bmp.width / bmp.height).coerceAtLeast(1) to CamView.ROWS
                val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                val cells = Dither.sample(bmp.width, bmp.height, 0, false, cols, rows) { x, y -> val c = px[y * bmp.width + x]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
                val dots = if (model.look == Look.DITHER) {
                    val ink = Dither.twoTone(cells, cols, true); IntArray(ink.size) { if (ink[it]) view.ink or (0xFF shl 24) else view.lcd }
                } else Dither.ramp(cells, view.lcd, view.ink)
                view.show(Bitmap.createBitmap(dots, cols, rows, Bitmap.Config.ARGB_8888), bmp)
            } catch (e: Exception) { if (token == loadToken) view.show(null) }
        }.start()
    }

    override fun onDestroy() { recording?.stop(); exec.shutdown(); super.onDestroy() }
}
