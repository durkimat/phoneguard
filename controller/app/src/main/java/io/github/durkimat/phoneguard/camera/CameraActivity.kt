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
import android.view.OrientationEventListener
import android.widget.FrameLayout
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import android.view.Surface
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
    companion object {
        /** For tests on a real phone: pretend the phone is held this way (a Surface.ROTATION_ value), or null. */
        @Volatile var debugHeld: Int? = null
        @Volatile var refresh: (() -> Unit)? = null
    }

    private val model = CameraModel()
    private lateinit var view: CamView
    /** How the phone is held, as the rotation CameraX wants (the screen itself always stays portrait). */
    @Volatile private var held = Surface.ROTATION_0
    private var orient: OrientationEventListener? = null
    private lateinit var previewView: PreviewView
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lastFrame = 0L
    private var analysisUse: ImageAnalysis? = null
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!io.github.durkimat.phoneguard.launcher.LauncherData.cameraAllowed(this)) { finish(); return } // the mode does not offer the camera
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = createDeviceProtectedStorageContext().getSharedPreferences("launcher", MODE_PRIVATE)
        view = CamView(this, model, prefs.getString("theme", null), prefs.getString("font", null))
        // video mode: this camera cannot run our live-picture stream and a video recorder together, so video shows the camera's plain colour picture
        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER; visibility = android.view.View.GONE }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(previewView, FrameLayout.LayoutParams(10, 10))
        root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val k = (r - l) / 480f
            val f = view.frame()
            val lp = previewView.layoutParams as FrameLayout.LayoutParams
            val w = (f[2] * k).toInt(); val h = (f[3] * k).toInt(); val left = (f[0] * k).toInt(); val top = (f[1] * k).toInt()
            if (lp.width != w || lp.height != h || lp.leftMargin != left || lp.topMargin != top) { lp.width = w; lp.height = h; lp.leftMargin = left; lp.topMargin = top; previewView.layoutParams = lp }
        }
        setContentView(root)
        if (!allowed(Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            flash("Camera needs permission")
        } else start()
        refresh = { ui.post { applyRotation() } }
        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180; in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                if (r != held) { held = r; ui.post { applyRotation() } }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
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
            if (model.mode == CamMode.PHOTO) {
                // photos: our own viewfinder drawn from the camera's frames (every effect, Normal included), so it can follow how the phone is held
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(Size(480, 640)).build().also { a -> a.setAnalyzer(exec) { img -> frame(img) } }
                val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(flashMode()).build()
                imageCapture = cap; videoCapture = null; analysisUse = analysis
                camera = p.bindToLifecycle(this, selector, analysis, cap)
            } else {
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                val vc = VideoCapture.withOutput(rec)
                videoCapture = vc; imageCapture = null; analysisUse = null
                camera = p.bindToLifecycle(this, selector, preview, vc)
            }
            previewView.visibility = if (model.mode == CamMode.VIDEO && !model.inGallery) android.view.View.VISIBLE else android.view.View.GONE
            view.show(null)
            applyRotation()
            applyZoomAndTorch()
        } catch (e: Exception) {
            Log.log(this, "camera: could not start: ${e.javaClass.simpleName} ${e.message}")
            flash("No camera")
        }
    }

    /** Photos, video and frames are turned to match how the phone is held, so a sideways shot is saved upright. */
    private fun applyRotation() {
        imageCapture?.targetRotation = heldNow
        analysisUse?.targetRotation = heldNow
        videoCapture?.targetRotation = heldNow
        view.show(null)
    }

    private val heldNow get() = debugHeld ?: held
    private val landscapeHeld get() = heldNow == Surface.ROTATION_90 || heldNow == Surface.ROTATION_270

    /** The angle to draw the upright picture at in the fixed portrait screen so it looks upright to the person holding the phone. */
    private val drawTurn get() = when (heldNow) { Surface.ROTATION_90 -> 90; Surface.ROTATION_270 -> 270; Surface.ROTATION_180 -> 180; else -> 0 }

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
            val w = img.width; val h = img.height; val rot = img.imageInfo.rotationDegrees
            val land = landscapeHeld
            if (model.effect == Effect.NONE) {
                // Normal: the real colours, at twice the cell resolution and drawn smooth
                val cols = (if (land) 140 else 105) * 2; val rows = (if (land) 105 else 140) * 2
                val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
                val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
                val px = Dither.sampleColour(w, h, rot, model.front, cols, rows) { x, yy ->
                    val yv = (yb.get(yy * y.rowStride + x * y.pixelStride).toInt() and 255) - 16
                    val cx = x / 2; val cy = yy / 2
                    val uv = (ub.get(cy * u.rowStride + cx * u.pixelStride).toInt() and 255) - 128
                    val vv = (vb.get(cy * v.rowStride + cx * v.pixelStride).toInt() and 255) - 128
                    val c = 1.164f * yv
                    val r = (c + 1.596f * vv).toInt().coerceIn(0, 255); val g = (c - 0.392f * uv - 0.813f * vv).toInt().coerceIn(0, 255); val b = (c + 2.017f * uv).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                view.show(Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888), null, drawTurn, smooth = true)
            } else {
                val plane = img.planes[0]; val buf = plane.buffer; val rs = plane.rowStride; val ps = plane.pixelStride
                val cols = if (land) 140 else 105; val rows = if (land) 105 else 140
                val cells = Dither.sample(w, h, rot, model.front, cols, rows) { x, y -> buf.get(y * rs + x * ps).toInt() and 0xFF }
                val px = Effects.render(model.effect, cells, cols, rows, view.lcd, view.ink, fine = false)
                view.show(Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888), null, drawTurn)
            }
        } catch (_: Exception) { } finally { img.close() }
    }

    private fun flash(msg: String, ms: Long = 1400) {
        model.notice = msg; view.postInvalidate()
        ui.removeCallbacks(clearNotice); ui.postDelayed(clearNotice, ms)
    }
    private val clearNotice = Runnable { model.notice = null; view.invalidate() }

    // ---- keys

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val mapped = when (ev.keyCode) {
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> Key.OK to -1
            else -> KeyMap.of(ev.keyCode)
        } ?: return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(model.onKey(mapped.first, mapped.second))
        return true
    }

    private fun perform(a: CamAction) {
        when (a) {
            CamAction.None -> {}
            CamAction.Redraw -> { applyZoomAndTorch(); previewView.visibility = if (model.mode == CamMode.VIDEO && !model.inGallery && !model.help) android.view.View.VISIBLE else android.view.View.GONE; view.invalidate() }
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
        val base = "PG_" + stamp()
        val effect = model.effect
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, base)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        cap.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                flash("Saved")
                val saved = r.savedUri
                // the effect copy is made from the saved colour photo, a little later, so the next shot is not held up
                if (effect != Effect.NONE && saved != null) exec.execute {
                    try {
                        EffectPhoto.make(contentResolver, saved, base, effect, view.lcd, view.ink)
                        ui.post { flash("Saved + ${effect.label.lowercase()}") }
                    } catch (e: Exception) { Log.log(this@CameraActivity, "camera: effect copy failed: ${e.javaClass.simpleName} ${e.message?.take(60)}") }
                }
            }
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
            ui.post { model.setGallery(list); previewView.visibility = android.view.View.GONE; showItem() }
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
                val maxC = CamView.BOX_COLS; val maxR = CamView.BOX_ROWS
                val k = minOf(maxC.toFloat() / bmp.width, maxR.toFloat() / bmp.height)
                val cols = (bmp.width * k).toInt().coerceAtLeast(1); val rows = (bmp.height * k).toInt().coerceAtLeast(1)
                val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                val cells = Dither.sample(bmp.width, bmp.height, 0, false, cols, rows) { x, y -> val c = px[y * bmp.width + x]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
                val dots = Effects.render(if (model.effect == Effect.NONE) Effect.TONES else model.effect, cells, cols, rows, view.lcd, view.ink, fine = false)
                view.show(Bitmap.createBitmap(dots, cols, rows, Bitmap.Config.ARGB_8888), bmp)
            } catch (e: Exception) { if (token == loadToken) view.show(null) }
        }.start()
    }

    /** The left key under the screen reaches us through the key router (see HardKeys); it is the Shoot key. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        io.github.durkimat.phoneguard.incall.LeftKey.camera = if (hasFocus) ({ ui.post { perform(model.onKey(Key.SOFT_LEFT, -1)) }; Unit }) else null
    }

    override fun onDestroy() {
        io.github.durkimat.phoneguard.incall.LeftKey.camera = null
        orient?.disable(); refresh = null
        recording?.stop(); exec.shutdown(); super.onDestroy()
    }
}
