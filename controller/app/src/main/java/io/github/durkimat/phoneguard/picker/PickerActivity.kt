package io.github.durkimat.phoneguard.picker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.util.LruCache
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import io.github.durkimat.media.Media
import io.github.durkimat.media.StickerLibrary
import io.github.durkimat.phoneguard.launcher.Key
import io.github.durkimat.phoneguard.launcher.KeyMap
import io.github.durkimat.phoneguard.launcher.LauncherActivity
import java.util.Collections
import java.util.concurrent.Executors

/** What a picker chose, waiting for the write screen that opened it. The launcher takes it when it comes back to the front. */
object PickerResult {
    private var result: Pair<PickKind, PickItem>? = null
    @Synchronized fun put(kind: PickKind, item: PickItem) { result = kind to item }
    @Synchronized fun take(): Pair<PickKind, PickItem>? = result.also { result = null }
}

/** The emoji or sticker picker, over the write screen. What each key does is [PickerModel]. */
class PickerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_KIND = "kind"
        fun intent(c: Context, kind: PickKind): Intent = Intent(c, PickerActivity::class.java).putExtra(EXTRA_KIND, kind.name)

        private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("picker", Context.MODE_PRIVATE)
        private fun recent(c: Context, kind: PickKind): List<String> = (prefs(c).getString(kind.name, "") ?: "").split('\n').filter { it.isNotEmpty() }
        private fun remember(c: Context, kind: PickKind, id: String) =
            prefs(c).edit().putString(kind.name, StickerLibrary.pushRecent(recent(c, kind), id).joinToString("\n")).apply()
    }

    private lateinit var model: PickerModel
    private lateinit var view: PickerView
    private val loader = Executors.newSingleThreadExecutor()
    private val thumbs = LruCache<String, Bitmap>(60)
    private val pending: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val kind = PickKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: PickKind.EMOJI.name)
        val groups = if (kind == PickKind.EMOJI) PickerModel.emojiGroups(recent(this, kind)) else PickerModel.stickerGroups(StickerLibrary.all(this), recent(this, kind))
        model = PickerModel(kind, groups)
        val look = io.github.durkimat.phoneguard.launcher.Look.current(this)
        view = PickerView(this, model, look.theme, look.font)
        view.thumb = { thumbs.get(it.value) }
        setContentView(view)
        refresh()
    }

    private fun refresh() {
        if (model.kind == PickKind.STICKER) {
            if (model.view == PView.PREVIEW) showPreview() else { view.preview = null; loadThumbs() }
        }
        view.invalidate()
    }

    private fun loadThumbs() {
        val items = model.items
        for (i in model.visible(margin = 1)) {
            val item = items.getOrNull(i) ?: continue
            if (thumbs.get(item.value) != null || !pending.add(item.value)) continue
            loader.execute {
                try {
                    thumbs.put(item.value, ImageDecoder.decodeBitmap(ImageDecoder.createSource(assets, item.value)) { d, _, _ -> d.setTargetSize(150, 150) })
                    view.postInvalidate()
                } catch (_: Exception) { } finally { pending.remove(item.value) }
            }
        }
    }

    private fun showPreview() {
        val item = model.current ?: return
        view.preview = try { ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, item.value)).also { Media.start(it) } } catch (_: Exception) { null }
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val mapped = KeyMap.of(ev.keyCode) ?: return super.dispatchKeyEvent(ev)
        val moving = mapped.first == Key.UP || mapped.first == Key.DOWN || mapped.first == Key.LEFT || mapped.first == Key.RIGHT
        if (ev.action == KeyEvent.ACTION_DOWN && (ev.repeatCount == 0 || moving)) perform(model.onKey(mapped.first, mapped.second))
        return true
    }

    private fun perform(a: PAction) {
        when (a) {
            PAction.None -> {}
            PAction.Redraw -> refresh()
            is PAction.Picked -> { remember(this, model.kind, a.item.id); PickerResult.put(model.kind, a.item); finish() }
            PAction.Exit -> finish()
            PAction.Home -> { startActivity(LauncherActivity.homeIntent(this)); finish() }
        }
    }

    /** The left key under the screen reaches us through the key router, as it does for the camera and gallery. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        io.github.durkimat.phoneguard.incall.LeftKey.camera = if (hasFocus) ({ view.post { perform(model.onKey(Key.SOFT_LEFT, -1)) }; Unit }) else null
    }

    override fun onDestroy() {
        io.github.durkimat.phoneguard.incall.LeftKey.camera = null
        loader.shutdownNow(); thumbs.evictAll()
        super.onDestroy()
    }
}
