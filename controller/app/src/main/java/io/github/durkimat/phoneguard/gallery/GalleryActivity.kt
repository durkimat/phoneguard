package io.github.durkimat.phoneguard.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.camera.Dither
import io.github.durkimat.phoneguard.camera.Effect
import io.github.durkimat.phoneguard.camera.Effects
import io.github.durkimat.phoneguard.launcher.Key
import io.github.durkimat.phoneguard.launcher.KeyMap
import java.util.Collections
import java.util.concurrent.Executors

/**
 * The gallery: every picture and video on the phone in a grid you move over with the D-pad, opening on the photos folder. Draws in the
 * launcher's theme (pictures dithered into its two colours, or in colour with 1). What each key does is [GalleryModel]; this class loads
 * pictures, plays a video and turns the model's actions into screen changes.
 */
class GalleryActivity : AppCompatActivity() {
    private val model = GalleryModel()
    private lateinit var view: GalleryView
    private lateinit var video: VideoView
    private val ui = Handler(Looper.getMainLooper())
    /** Tiles load one at a time on this thread, the viewer's picture on its own so a tile queue never delays it. */
    private val tiles = Executors.newSingleThreadExecutor()
    private val bigs = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(160) {}
    private val pending: MutableSet<String> = Collections.synchronizedSet(HashSet())
    @Volatile private var bigToken = 0
    private var playing = false
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!io.github.durkimat.phoneguard.launcher.LauncherData.cameraAllowed(this)) { finish(); return } // the mode does not offer the camera or its pictures
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val look = io.github.durkimat.phoneguard.launcher.Look.current(this)
        view = GalleryView(this, model, look.theme, look.font)
        view.thumb = { cache.get(tileKey(it, model.colour)) }
        video = VideoView(this).apply { visibility = android.view.View.GONE }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(root)
        if (hasMedia()) load() else requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO), 1)
    }

    private fun hasMedia() = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMedia()) load() else { model.setItems(emptyList()); view.invalidate() }
    }

    private fun load() {
        Thread {
            val list = GalleryData.load(this)
            ui.post { model.setItems(list); refresh() }
        }.start()
    }

    // ---- pictures

    private fun tileKey(i: GItem, colour: Boolean) = i.uri + if (colour) "|c" else "|d"

    private fun refresh() {
        if (model.view == GView.VIEWER) showItem() else ensureTiles()
        view.invalidate()
    }

    /** Starts loading the tiles on screen and one row either side that are not ready yet. */
    private fun ensureTiles() {
        val colour = model.colour
        val items = model.items
        for (i in model.visible(margin = 1)) {
            val item = items.getOrNull(i) ?: continue
            val key = tileKey(item, colour)
            if (cache.get(key) != null || !pending.add(key)) continue
            tiles.execute {
                try {
                    // a tile the view has scrolled well past is not worth making
                    if (model.items.getOrNull(i)?.uri != item.uri || i !in model.visible(margin = 3)) return@execute
                    val b = contentResolver.loadThumbnail(Uri.parse(item.uri), Size(220, 220), null)
                    cache.put(key, if (colour) b else dithered(b, 50, 50, square = true))
                    view.postInvalidate()
                } catch (e: Exception) { /* a picture that will not load stays an empty frame */ } finally { pending.remove(key) }
            }
        }
    }

    private fun showItem() {
        val item = model.current ?: return
        val token = ++bigToken
        val colour = model.colour
        view.big = null; view.notice = null
        bigs.execute {
            try {
                val b = contentResolver.loadThumbnail(Uri.parse(item.uri), Size(960, 960), null)
                val shown = if (colour) b else {
                    val k = minOf(156f / b.width, 164f / b.height)
                    dithered(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), square = false)
                }
                if (token != bigToken) return@execute
                view.big = shown; view.postInvalidate()
            } catch (e: Exception) {
                Log.log(this, "gallery: ${e.javaClass.simpleName} opening a picture")
                if (token == bigToken) { view.notice = "Cannot open"; view.postInvalidate() }
            }
        }
        view.invalidate()
    }

    /** The picture as [cols] by [rows] cells in the theme's two colours (the camera's Tones look); [square] crops the middle square first. */
    private fun dithered(b: Bitmap, cols: Int, rows: Int, square: Boolean): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val s = if (square) minOf(w, h) else 0
        val sw = if (square) s else w; val sh = if (square) s else h
        val ox = if (square) (w - s) / 2 else 0; val oy = if (square) (h - s) / 2 else 0
        val cells = Dither.sample(sw, sh, 0, false, cols, rows) { x, y -> val c = px[(y + oy) * w + x + ox]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
        val out = Effects.render(Effect.TONES, cells, cols, rows, view.lcd, view.ink, fine = false)
        return Bitmap.createBitmap(out, cols, rows, Bitmap.Config.ARGB_8888)
    }

    // ---- video

    private fun play(item: GItem) {
        playing = true; paused = false
        video.visibility = android.view.View.VISIBLE
        video.setOnPreparedListener { it.isLooping = false; video.start() }
        video.setOnCompletionListener { stopPlay() }
        video.setOnErrorListener { _, _, _ -> stopPlay(); view.notice = "Cannot play"; view.invalidate(); true }
        video.setVideoURI(Uri.parse(item.uri))
    }

    private fun stopPlay() {
        if (!playing) return
        playing = false
        try { video.stopPlayback() } catch (_: Exception) { }
        video.visibility = android.view.View.GONE
        view.invalidate()
    }

    // ---- keys

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val mapped = KeyMap.of(ev.keyCode) ?: return super.dispatchKeyEvent(ev)
        val key = mapped.first; val digit = mapped.second
        val moving = key == Key.UP || key == Key.DOWN || key == Key.LEFT || key == Key.RIGHT
        if (ev.action != KeyEvent.ACTION_DOWN || (ev.repeatCount != 0 && !moving)) return true
        if (playing) {
            when {
                key == Key.OK || (key == Key.DIGIT && digit == 5) -> if (paused) { video.start(); paused = false } else { video.pause(); paused = true }
                key == Key.SOFT_RIGHT -> stopPlay()
                key == Key.END -> { stopPlay(); finish() }
            }
            return true
        }
        perform(model.onKey(key, digit))
        return true
    }

    private fun perform(a: GAction) {
        when (a) {
            GAction.None -> {}
            GAction.Redraw, GAction.ShowItem -> refresh()
            is GAction.Play -> play(a.item)
            GAction.Exit, GAction.Home -> finish()
        }
    }

    /** The left key under the screen reaches us through the key router (see HardKeys), as it does for the camera. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        io.github.durkimat.phoneguard.incall.LeftKey.camera = if (hasFocus) ({ ui.post { if (!playing) perform(model.onKey(Key.SOFT_LEFT, -1)) }; Unit }) else null
    }

    override fun onPause() { stopPlay(); super.onPause() }

    override fun onDestroy() {
        io.github.durkimat.phoneguard.incall.LeftKey.camera = null
        tiles.shutdownNow(); bigs.shutdownNow(); cache.evictAll()
        super.onDestroy()
    }
}
