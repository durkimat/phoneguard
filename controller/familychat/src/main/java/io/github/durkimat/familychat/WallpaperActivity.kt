package io.github.durkimat.familychat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Chat theme": the look of one chat, chosen by whoever holds the phone (no PIN, only this phone sees it): the colour of "my" bubbles and a wallpaper
 * (five groups drawn in code, plus "From my photos"). The preview shows both at once. See [Themes].
 */
class WallpaperActivity : Activity() {
    private lateinit var chat: String
    private var wallpaper = "sand"
    private var bubble = "green"
    private var tab = "Colours"
    private lateinit var preview: LinearLayout
    private lateinit var bubbleRow: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var tiles: GridLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fc.init(this)
        val c = this
        chat = intent.getStringExtra("chat") ?: ""
        wallpaper = Themes.normalize(Fc.settings.wallpaper(chat)); bubble = Fc.settings.bubbleColor(chat)
        tab = Themes.categories.firstOrNull { cat -> cat.second.any { it.key == wallpaper } }?.first ?: "Colours"
        val body = Ui.pad(c)
        body.addView(Ui.title(c, "Chat theme").apply { textSize = 24f })
        preview = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16)) }
        body.addView(preview, Ui.params(h = Ui.dp(c, 170), c = c, top = 12))
        body.addView(Ui.text(c, "My bubbles", 18f, Ui.INK, true), Ui.params(c = c, top = 16))
        bubbleRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(bubbleRow) }, Ui.params(c = c, top = 8))
        body.addView(Ui.text(c, "Wallpaper", 18f, Ui.INK, true), Ui.params(c = c, top = 16))
        tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, Ui.params(c = c, top = 8))
        tiles = GridLayout(c).apply { columnCount = 3 }
        body.addView(tiles, Ui.params(c = c, top = 8))
        body.addView(Ui.text(c, "Only you see your theme. A photo stays on this phone and is never sent to anyone.", 15f, Ui.MUTED), Ui.params(c = c, top = 12))
        setContentView(Ui.screen(c, body, Ui.footer(c, Ui.button(c, "Use this theme") { Fc.settings.setWallpaper(chat, wallpaper); Fc.settings.setBubbleColor(chat, bubble); finish() }, Ui.button(c, "Cancel", primary = false) { finish() })))
        draw()
    }

    private fun draw() {
        val c = this
        // preview
        preview.background = Themes.drawable(c, wallpaper, chat); preview.removeAllViews()
        fun bub(t: String, mine: Boolean) = Ui.text(c, t, 16f, Ui.INK).apply { setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8)); background = Ui.bubbleShape(c, mine, Themes.bubbleColor(bubble)) }
        preview.addView(bub("Tea at 6, can you lay the table?", false), Ui.params(w = -2))
        preview.addView(bub("Yes, on it!", true), Ui.params(w = -2, c = c, top = 8).also { it.gravity = Gravity.END })
        preview.addView(meta("Thank you!", wallpaper), Ui.params(w = -2, c = c, top = 8))
        // bubble colours
        bubbleRow.removeAllViews()
        for (bc in Themes.bubbles) bubbleRow.addView(swatch(bc.label, bc.key == bubble, 0) { v -> v.background = Ui.shape(c, bc.color, 14, if (bc.key == bubble) Ui.ACCENT else Ui.BORDER, if (bc.key == bubble) 3 else 1) }.also { it.setOnClickListener { bubble = bc.key; draw() } },
            LinearLayout.LayoutParams(Ui.dp(c, 84), Ui.dp(c, 64)).also { it.rightMargin = Ui.dp(c, 8) })
        // wallpaper groups
        tabs.removeAllViews()
        for ((name, _) in Themes.categories) tabs.addView(Ui.text(c, name, 16f, if (name == tab) 0xFFFFFFFF.toInt() else Ui.INK, true).apply {
            gravity = Gravity.CENTER; minHeight = Ui.dp(c, 44); setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0); background = Ui.shape(c, if (name == tab) Ui.ACCENT else Ui.THEIRS, 22); isClickable = true
            setOnClickListener { tab = name; draw() } }, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = Ui.dp(c, 8) })
        tiles.removeAllViews()
        val options = Themes.categories.first { it.first == tab }.second.map { it.key to it.label } + (if (tab == "Colours") listOf("photo" to "From my photos") else emptyList())
        for ((key, label) in options) {
            val sel = key == wallpaper
            val tile = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4)); isClickable = true; contentDescription = label
                background = Ui.shape(c, Ui.CARD, 14, if (sel) Ui.ACCENT else Ui.BORDER, if (sel) 3 else 1); setOnClickListener { if (key == "photo") pickPhoto() else { wallpaper = key; draw() } } }
            val sw = View(c).apply { background = if (key == "photo") Ui.shape(c, Ui.ACCENT_SOFT, 10) else Themes.drawable(c, key, chat) }
            tile.addView(sw, LinearLayout.LayoutParams(Ui.dp(c, 84), Ui.dp(c, 56)))
            tile.addView(Ui.text(c, label, 14f, Ui.INK, sel).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 4), 0, 0) })
            tiles.addView(tile, GridLayout.LayoutParams().apply { width = Ui.dp(c, 100); height = Ui.dp(c, 98); setMargins(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4)) })
        }
    }

    private fun swatch(label: String, on: Boolean, unused: Int, bg: (View) -> Unit): TextView = Ui.text(this, label, 15f, Ui.INK, on).apply { gravity = Gravity.CENTER; isClickable = true; contentDescription = "$label bubbles"; bg(this) }

    /** A small text on the wallpaper: light on dark ones, on a white chip on mid-tone ones. */
    private fun meta(t: String, key: String) = Ui.metaText(this, t, 14f, key)

    private fun pickPhoto() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, 21) } catch (_: Throwable) { }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        if (req == 21 && res == RESULT_OK && Themes.savePhoto(this, chat, uri)) { wallpaper = "photo"; draw() }
    }
}
