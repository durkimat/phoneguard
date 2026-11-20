package io.github.durkimat.familychat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout

/**
 * Screen R6: the wallpaper of one chat, chosen by whoever holds the phone (no PIN). Only this phone sees it. This is the place to grow into a
 * full chat theme later (wallpaper plus bubble colour): each [Wallpapers.Option] could carry the bubble colours too.
 */
class WallpaperActivity : Activity() {
    private lateinit var chat: String
    private var choice = "sand"
    private lateinit var preview: LinearLayout
    private lateinit var tiles: GridLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fc.init(this)
        val c = this
        chat = intent.getStringExtra("chat") ?: ""
        choice = Fc.settings.wallpaper(chat)
        val body = Ui.pad(c)
        body.addView(Ui.title(c, "Chat wallpaper").apply { textSize = 24f })
        preview = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16)) }
        body.addView(preview, Ui.params(h = Ui.dp(c, 170), c = c, top = 12))
        body.addView(Ui.text(c, "Choose a wallpaper", 18f, Ui.INK, true), Ui.params(c = c, top = 16))
        tiles = GridLayout(c).apply { columnCount = 3 }
        body.addView(tiles, Ui.params(c = c, top = 8))
        body.addView(Ui.text(c, "Only you see your wallpaper. A photo stays on this phone and is never sent to anyone.", 15f, Ui.MUTED), Ui.params(c = c, top = 12))
        setContentView(Ui.screen(c, body, Ui.footer(c, Ui.button(c, "Use this wallpaper") { Fc.settings.setWallpaper(chat, choice); finish() }, Ui.button(c, "Cancel", primary = false) { finish() })))
        draw()
    }

    private fun draw() {
        val c = this
        preview.background = Wallpapers.drawable(c, choice, chat)
        preview.removeAllViews()
        val dark = Wallpapers.dark(choice)
        fun bubble(t: String, mine: Boolean) = Ui.text(c, t, 16f, Ui.INK).apply { setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8)); background = Ui.shape(c, if (mine) Ui.MINE else Ui.CARD, 14) }
        preview.addView(bubble("Tea at 6, can you lay the table?", false), Ui.params(w = -2))
        preview.addView(bubble("Yes, on it!", true), Ui.params(w = -2, c = c, top = 8).also { it.gravity = Gravity.END })
        preview.addView(Ui.text(c, "Thank you!", 14f, if (dark) 0xFFD6DEE6.toInt() else Ui.MUTED), Ui.params(w = -2, c = c, top = 8))
        tiles.removeAllViews()
        for (o in Wallpapers.options + Wallpapers.Option("photo", "From my photos")) {
            val sel = o.key == choice
            val tile = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4)); isClickable = true; contentDescription = o.label
                background = Ui.shape(c, Ui.CARD, 14, if (sel) Ui.ACCENT else Ui.BORDER)
                setOnClickListener { if (o.key == "photo") pickPhoto() else { choice = o.key; draw() } }
            }
            val sw = View(c).apply { background = if (o.key == "photo") Ui.shape(c, Ui.ACCENT_SOFT, 10) else Wallpapers.drawable(c, o.key, chat) }
            tile.addView(sw, LinearLayout.LayoutParams(Ui.dp(c, 84), Ui.dp(c, 56)))
            tile.addView(Ui.text(c, o.label, 14f, Ui.INK, sel).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 4), 0, 0) })
            tiles.addView(tile, GridLayout.LayoutParams().apply { width = Ui.dp(c, 100); height = Ui.dp(c, 98); setMargins(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4)) })
        }
    }

    private fun pickPhoto() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, 21) } catch (_: Throwable) { }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        if (req == 21 && res == RESULT_OK && Wallpapers.savePhoto(this, chat, uri)) { choice = "photo"; draw() }
    }
}
