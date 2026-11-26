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
    companion object {
        private const val EXTRA_PATH = "path"; private const val EXTRA_KIND = "kind"; private const val EXTRA_WHO = "who"
        private const val EXTRA_CAPTION = "caption"; private const val EXTRA_SECONDS = "seconds"; private const val EXTRA_ANIMATED = "animated"

        /** The full view of one message's media: the same viewer, on its own. */
        fun messageMedia(c: android.content.Context, a: io.github.durkimat.phoneguard.launcher.Action.WaViewMedia): android.content.Intent =
            android.content.Intent(c, GalleryActivity::class.java).putExtra(EXTRA_PATH, a.path).putExtra(EXTRA_KIND, a.kind).putExtra(EXTRA_WHO, a.who)
                .putExtra(EXTRA_CAPTION, a.caption).putExtra(EXTRA_SECONDS, a.seconds).putExtra(EXTRA_ANIMATED, a.animated)
    }

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
        val file = intent.getStringExtra(EXTRA_PATH) // one message's media (from WhatsApp), not the phone's pictures
        // the gallery goes with the camera; a message's own picture goes with the message, whatever the mode says about the camera
        if (file == null && !io.github.durkimat.phoneguard.launcher.LauncherData.cameraAllowed(this)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val look = io.github.durkimat.phoneguard.launcher.Look.current(this)
        view = GalleryView(this, model, look.theme, look.font)
        view.thumb = { cache.get(tileKey(it, model.colour)) }
        video = VideoView(this).apply { visibility = android.view.View.GONE }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(root)
        if (file != null) showMessageMedia(file) else if (hasMedia()) load() else requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO), 1)
    }

    private fun showMessageMedia(path: String) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "image"
        model.showOne(
            GItem(path, kind == "video" || kind == "gif", intent.getStringExtra(EXTRA_CAPTION) ?: "", 0, "", intent.getIntExtra(EXTRA_SECONDS, 0),
                loops = kind == "gif", animated = intent.getBooleanExtra(EXTRA_ANIMATED, false)),
            intent.getStringExtra(EXTRA_WHO) ?: "",
        )
        refresh()
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
                    cache.put(key, if (colour) b else Pictures.dithered(b, 50, 50, true, view.lcd, view.ink))
                    view.postInvalidate()
                } catch (e: Exception) { /* a picture that will not load stays an empty frame */ } finally { pending.remove(key) }
            }
        }
    }

    private fun showItem() {
        val item = model.current ?: return
        val token = ++bigToken
        val colour = model.colour
        view.big = null; view.notice = null; view.anim = null
        if (colour && item.animated) io.github.durkimat.media.Media.drawable(item.uri)?.let { view.anim = it; io.github.durkimat.media.Media.start(it) }
        bigs.execute {
            try {
                val b = Pictures.load(this, item.uri, item.video, 960) ?: throw java.io.IOException("unreadable")
                val shown = if (colour) b else Pictures.ditheredToFit(b, 156, 160, view.lcd, view.ink)
                if (token != bigToken) return@execute
                view.big = shown; view.postInvalidate()
            } catch (e: Exception) {
                Log.log(this, "gallery: ${e.javaClass.simpleName} opening a picture")
                if (token == bigToken) { view.notice = "Cannot open"; view.postInvalidate() }
            }
        }
        view.invalidate()
    }

    // ---- video

    private fun play(item: GItem) {
        playing = true; paused = false
        video.visibility = android.view.View.VISIBLE
        view.visibility = android.view.View.INVISIBLE // only the video on the black background
        video.setOnPreparedListener { it.isLooping = item.loops; video.start() }
        video.setOnCompletionListener { stopPlay() }
        video.setOnErrorListener { _, _, _ -> stopPlay(); view.notice = "Cannot play"; view.invalidate(); true }
        video.setVideoURI(Uri.parse(item.uri))
    }

    private fun stopPlay() {
        if (!playing) return
        playing = false
        try { video.stopPlayback() } catch (_: Exception) { }
        video.visibility = android.view.View.GONE
        view.visibility = android.view.View.VISIBLE
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
                key == Key.END -> { stopPlay(); goHome() }
            }
            return true
        }
        perform(model.onKey(key, digit))
        return true
    }

    private fun goHome() {
        startActivity(io.github.durkimat.phoneguard.launcher.LauncherActivity.homeIntent(this)); finish()
    }

    private fun perform(a: GAction) {
        when (a) {
            GAction.None -> {}
            GAction.Redraw, GAction.ShowItem -> refresh()
            is GAction.Play -> play(a.item)
            GAction.Exit -> finish()
            GAction.Home -> goHome()
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
