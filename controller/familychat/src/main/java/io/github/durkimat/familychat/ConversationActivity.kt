package io.github.durkimat.familychat

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import io.github.durkimat.wacore.WaMsg
import java.util.concurrent.Executors

/**
 * Screen 2: one group. Others on the left under their name (the parent's own messages under the parent's name, never "Me"); the child's on the right
 * in light green. Pictures, stickers, GIFs and videos show in the bubbles; reactions sit under a message; a long press opens reply, react, edit, delete.
 */
class ConversationActivity : Activity() {
    private lateinit var chat: String
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var error: TextView
    private lateinit var box: EditText
    private lateinit var root: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var stickerPanel: LinearLayout
    private lateinit var title: String
    private var replyTo: WaMsg? = null
    private var editing: WaMsg? = null
    private var lastCount = -1
    private var pendingCapture: Pair<String, Boolean>? = null
    private val redraw: () -> Unit = { drawMessages() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fc.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val c = this
        chat = "g" + (intent.getStringExtra("id") ?: "")
        title = intent.getStringExtra("name") ?: ""
        // only an allowed group opens; anything else goes back
        if (Fc.settings.allowedGroups.none { "g${it.id}" == chat }) { finish(); return }
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 8)); setBackgroundColor(Ui.GROUND)
            addView(IconView(c, "back", Ui.INK).apply { contentDescription = "Back to chats"; isClickable = true; setOnClickListener { finish() } }, Ui.params(Ui.dp(c, 48), Ui.dp(c, 48)))
            addView(Ui.text(c, title, 22f, Ui.INK, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.params(0, weight = 1f))
            addView(IconView(c, "picture", Ui.INK).apply { contentDescription = "Chat wallpaper"; isClickable = true; setOnClickListener { startActivity(Intent(c, WallpaperActivity::class.java).putExtra("chat", chat)) } }, Ui.params(Ui.dp(c, 48), Ui.dp(c, 48)))
        }
        list = Ui.col(c).apply { setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8)) }
        scroll = ScrollView(c).apply { addView(list) }
        error = Ui.text(c, "", 15f, Ui.DANGER).apply { setPadding(Ui.dp(c, 20), Ui.dp(c, 4), Ui.dp(c, 20), 0); visibility = View.GONE; setBackgroundColor(Ui.GROUND) }
        banner = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.CARD); setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6)); visibility = View.GONE }
        bannerText = Ui.text(c, "", 15f, Ui.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        banner.addView(bannerText, Ui.params(0, weight = 1f))
        banner.addView(IconView(c, "close", Ui.MUTED).apply { contentDescription = "Cancel"; isClickable = true; setOnClickListener { clearMode() } }, Ui.params(Ui.dp(c, 48), Ui.dp(c, 48)))
        box = Ui.input(c, "Write as ${Fc.settings.childName}").apply { setSingleLine(false); maxLines = 4; contentDescription = "Message" }
        val send = LinearLayout(c).apply { background = Ui.shape(c, Ui.ACCENT); gravity = Gravity.CENTER; isClickable = true; contentDescription = "Send"; addView(IconView(c, "send", 0xFFFFFFFF.toInt()), Ui.params(Ui.dp(c, 28), Ui.dp(c, 28))); setOnClickListener { sendNow() } }
        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 12)); setBackgroundColor(Ui.GROUND) }
        if (Fc.settings.photosAllowed) bar.addView(IconView(c, "plus", Ui.ACCENT).apply { contentDescription = "Send a photo or video"; isClickable = true; setOnClickListener { attachSheet() } }, Ui.params(Ui.dp(c, 48), Ui.dp(c, 48)))
        bar.addView(IconView(c, "smile", Ui.ACCENT).apply { contentDescription = "Stickers"; isClickable = true; setOnClickListener { toggleStickers() } }, Ui.params(Ui.dp(c, 48), Ui.dp(c, 48)))
        bar.addView(box, Ui.params(0, weight = 1f)); bar.addView(send, Ui.params(Ui.dp(c, 56), Ui.dp(c, 56)).also { it.leftMargin = Ui.dp(c, 8) })
        stickerPanel = buildStickerPanel().apply { visibility = View.GONE }
        root = Ui.col(c)
        root.addView(header, Ui.params()); root.addView(scroll, Ui.params(h = 0, weight = 1f)); root.addView(error, Ui.params()); root.addView(banner, Ui.params()); root.addView(stickerPanel, Ui.params()); root.addView(bar, Ui.params())
        Ui.fitInsets(root)
        setContentView(root)
        applyWallpaper()
    }

    private fun applyWallpaper() { scroll.background = Wallpapers.drawable(this, Fc.settings.wallpaper(chat), chat) }

    override fun onResume() {
        super.onResume(); Fc.openChat = chat; Notifier.cancel(this, chat); Fc.listeners.add(redraw); applyWallpaper(); drawMessages()
        Thread { Fc.readChat(chat) }.start(); ConnectionService.start(this); Thread { Fc.connect() }.start()
    }
    override fun onPause() { Fc.listeners.remove(redraw); if (Fc.openChat == chat) Fc.openChat = null; super.onPause() }

    private val loader = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }

    private fun loadInto(iv: ImageView, path: String, maxPx: Int) {
        cache.get(path)?.let { iv.setImageBitmap(it); return }
        iv.tag = path
        loader.execute { val bmp = Media.bitmap(path, maxPx); if (bmp != null) { cache.put(path, bmp); runOnUiThread { if (iv.tag == path) iv.setImageBitmap(bmp) } } }
    }

    // ---- the messages
    private fun drawMessages() {
        if (!::list.isInitialized) return
        val c = this
        val msgs = Fc.store().messages(chat)
        val nearEnd = scroll.scrollY + scroll.height >= list.height - Ui.dp(c, 80)
        val dark = Wallpapers.dark(Fc.settings.wallpaper(chat))
        list.removeAllViews()
        for (m in msgs) list.addView(row(m, msgs, dark), Ui.params(c = c, top = 8))
        if (nearEnd || lastCount < 0 || msgs.size > lastCount) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        lastCount = msgs.size
    }

    private fun row(m: WaMsg, all: List<WaMsg>, dark: Boolean): View {
        val c = this
        val mine = m.mine
        val muted = if (dark) 0xFFD6DEE6.toInt() else Ui.MUTED
        // a system line ("[call declined]"): small, centred, no bubble
        if (Rich.isSystem(m)) return Ui.text(c, m.body, 13f, muted).apply { gravity = Gravity.CENTER; setPadding(Ui.dp(c, 12), Ui.dp(c, 2), Ui.dp(c, 12), Ui.dp(c, 2)) }
        val sticker = m.kind == "sticker" && !m.deleted && Fc.settings.showMedia
        // the column is only as wide as its widest part (the bubble hugs its text or picture) and never wider than 78% of the row; the row puts it left or right
        val item = CappedColumn(c).apply { orientation = LinearLayout.VERTICAL; gravity = if (mine) Gravity.END else Gravity.START }
        if (!mine && m.sender.isNotBlank()) item.addView(Ui.text(c, m.sender, 14f, muted, true).apply { setPadding(Ui.dp(c, 4), 0, 0, Ui.dp(c, 4)) }, LinearLayout.LayoutParams(-2, -2))
        val bubble = Ui.col(c).apply {
            if (!sticker) { background = bubbleShape(c, mine); setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8)) }
        }
        if (!m.deleted && (m.quoteText.isNotBlank() || m.quoteId.isNotEmpty())) quoteBlock(m, all)?.let { bubble.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        if (m.deleted) bubble.addView(Ui.text(c, "This message was deleted", 16f, Ui.MUTED).apply { setTypeface(typeface, Typeface.ITALIC) }, LinearLayout.LayoutParams(-2, -2))
        else media(m)?.let { bubble.addView(it, Ui.params(w = it.layoutParams?.width ?: -2, h = it.layoutParams?.height ?: -2, c = c, top = if (bubble.childCount > 0) 6 else 0)) }
        if (!m.deleted) {
            val text = Rich.caption(m)
            val mediaOff = m.kind in setOf("image", "sticker", "gif", "video") && !Fc.settings.showMedia
            val shown = if (mediaOff) Rich.marker(m) + (if (text.isNotBlank()) " $text" else "") else text
            if (shown.isNotBlank()) bubble.addView(Ui.text(c, shown, 17f, Ui.INK), Ui.params(w = -2, c = c, top = if (bubble.childCount > 0) 6 else 0))
        }
        bubble.isLongClickable = true
        bubble.setOnLongClickListener { if (!m.deleted) actions(m); true }
        item.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        if (m.reactions.isNotEmpty() && !m.deleted) {
            val rr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dp(c, 4), Ui.dp(c, 4), 0, 0) }
            for ((e, n) in Rich.groupReactions(m.reactions)) rr.addView(Ui.text(c, if (n > 1) "$e $n" else e, 15f, Ui.INK).apply {
                setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4)); background = Ui.shape(c, Ui.CARD, 14, Ui.BORDER)
            }, Ui.params(w = -2).also { it.rightMargin = Ui.dp(c, 6) })
            item.addView(rr, LinearLayout.LayoutParams(-2, -2))
        }
        // the time sits under the child's bubble; "edited" under anyone's
        val foot = (if (mine && !m.deleted) Ui.timeText(m.date) else "") + (if (m.edited && !m.deleted) (if (mine) " \u00B7 edited" else "edited") else "")
        if (foot.isNotEmpty()) item.addView(Ui.text(c, foot, 13f, muted).apply { setPadding(Ui.dp(c, 4), Ui.dp(c, 3), Ui.dp(c, 4), 0) }, LinearLayout.LayoutParams(-2, -2))
        return LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (mine) Gravity.END else Gravity.START; addView(item, LinearLayout.LayoutParams(-2, -2)) }
    }

    /** 20 dp corners with a small 6 dp corner on the side the message comes from: bottom left for others, bottom right for the child. */
    private fun bubbleShape(c: android.content.Context, mine: Boolean): android.graphics.drawable.GradientDrawable {
        val big = Ui.dp(c, 20).toFloat(); val small = Ui.dp(c, 6).toFloat()
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(if (mine) Ui.MINE else Ui.CARD)
            cornerRadii = if (mine) floatArrayOf(big, big, big, big, small, small, big, big) else floatArrayOf(big, big, big, big, big, big, small, small)
        }
    }

    private fun quoteBlock(m: WaMsg, all: List<WaMsg>): View? {
        val c = this
        val orig = all.firstOrNull { it.mid.isNotEmpty() && it.mid == m.quoteId }
        val who = when { orig != null -> if (orig.mine) Fc.settings.childName else orig.sender.ifBlank { m.quoteSender }; else -> m.quoteSender }
        val text = if (orig != null) Rich.snippet(orig) else m.quoteText
        if (text.isBlank() && who.isBlank()) return null
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.shape(c, 0x14000000, 10); setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6))
            if (who.isNotBlank()) addView(Ui.text(c, who, 13f, Ui.ACCENT, true))
            addView(Ui.text(c, text.ifBlank { "Message" }, 15f, Ui.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
    }

    /** The picture, sticker, GIF or video of a message, or null for plain text. */
    private fun media(m: WaMsg): View? {
        val c = this
        if (m.kind !in setOf("image", "sticker", "gif", "video") || !Fc.settings.showMedia) return null
        val have = m.mediaPath.isNotEmpty() && java.io.File(m.mediaPath).exists()
        val w = Ui.dp(c, if (m.kind == "sticker") 150 else 220)
        val ratio = if (m.width > 0 && m.height > 0) (m.height.toFloat() / m.width).coerceIn(0.5f, 1.6f) else if (m.kind == "sticker") 1f else 0.75f
        val h = (w * ratio).toInt()
        return when (m.kind) {
            "sticker", "image" -> ImageView(c).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = if (m.kind == "sticker") "Sticker" else "Photo"; layoutParams = LinearLayout.LayoutParams(w, h)
                if (!have) { background = Ui.shape(c, Ui.THEIRS, 12); isClickable = true; setOnClickListener { fetchThen(m) { } } }
                else if (m.kind == "sticker" || m.mime.contains("webp") || m.mime.contains("gif")) { val d = Media.drawable(m.mediaPath); setImageDrawable(d); Media.start(d) }
                else { loadInto(this, m.mediaPath, 1080); isClickable = true; setOnClickListener { openViewer(m) } }
            }
            "gif" -> if (!have) ImageView(c).apply { background = Ui.shape(c, Ui.THEIRS, 12); layoutParams = LinearLayout.LayoutParams(w, h); isClickable = true; setOnClickListener { fetchThen(m) { } } }
            else VideoView(c).apply {
                layoutParams = LinearLayout.LayoutParams(w, h); setVideoPath(m.mediaPath); contentDescription = "GIF"
                setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f); start() }
            }
            else -> videoCard(m, have, w, h)
        }
    }

    private fun videoCard(m: WaMsg, have: Boolean, w: Int, h: Int): View {
        val c = this
        val card = android.widget.FrameLayout(c).apply { layoutParams = LinearLayout.LayoutParams(w, h); background = Ui.shape(c, 0xFF2A3640.toInt(), 12); isClickable = true }
        val thumb = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(thumb, android.widget.FrameLayout.LayoutParams(-1, -1))
        if (have) loader.execute { val f = Media.videoFrame(m.mediaPath); if (f != null) runOnUiThread { thumb.setImageBitmap(f) } }
        card.addView(IconView(c, "play", 0xFFFFFFFF.toInt()), android.widget.FrameLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56), Gravity.CENTER))
        val label = if (have) "Video · ${Rich.duration(m.seconds)}" else "Tap to download · ${Rich.size(m.mediaSize)}"
        card.addView(Ui.text(c, label, 14f, 0xFFFFFFFF.toInt(), true).apply { setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 6)); background = ColorDrawable(0x88000000.toInt()) }, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        card.setOnClickListener { if (have) openViewer(m) else fetchThen(m) { openViewer(it) } }
        card.contentDescription = label
        return card
    }

    private fun fetchThen(m: WaMsg, then: (WaMsg) -> Unit) {
        error.visibility = View.GONE
        Thread {
            val (path, err) = Fc.fetchMedia(chat, m)
            runOnUiThread { if (err.isNotEmpty()) { error.text = err; error.visibility = View.VISIBLE } else then(m.copy(mediaPath = path)) }
        }.start()
    }

    private fun openViewer(m: WaMsg) {
        startActivity(Intent(this, ViewerActivity::class.java).putExtra("path", m.mediaPath).putExtra("kind", m.kind)
            .putExtra("who", if (m.mine) Fc.settings.childName else m.sender).putExtra("sub", "$title · ${Ui.timeText(m.date)}").putExtra("caption", Rich.caption(m)))
    }

    // ---- long press: react, reply, edit, delete
    private fun actions(m: WaMsg) {
        val c = this
        val d = Dialog(c)
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        val card = Ui.card(c).apply { setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 12)) }
        card.addView(Ui.text(c, if (m.mine) Fc.settings.childName else m.sender.ifBlank { "Message" }, 15f, Ui.MUTED, true))
        card.addView(Ui.text(c, Rich.snippet(m), 17f, Ui.INK).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.params(c = c, top = 4))
        val emojis = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (e in listOf("👍", "❤️", "😂", "😮", "😢", "🙏")) emojis.addView(Ui.text(c, e, 28f).apply {
            gravity = Gravity.CENTER; isClickable = true; contentDescription = "React $e"
            setOnClickListener { d.dismiss(); background { Fc.react(chat, m, e) } }
        }, Ui.params(0, Ui.dp(c, 56), weight = 1f))
        card.addView(emojis, Ui.params(c = c, top = 12))
        card.addView(Ui.text(c, "Everyone else sees a reply: “${Fc.settings.childName}: 👍”", 14f, Ui.MUTED), Ui.params(c = c, top = 4))
        card.addView(Ui.divider(c), Ui.params(c = c, top = 8))
        card.addView(Ui.row(c, "Reply") { d.dismiss(); startReply(m) })
        if (Rich.canEdit(m, System.currentTimeMillis())) { card.addView(Ui.divider(c)); card.addView(Ui.row(c, "Edit", "only your own, for a few minutes") { d.dismiss(); startEdit(m) }) }
        if (Rich.canDelete(m)) { card.addView(Ui.divider(c)); card.addView(Ui.row(c, "Delete for everyone", "only your own", danger = true) { d.dismiss(); confirmDelete(m) }) }
        d.setContentView(card, android.view.ViewGroup.LayoutParams(Ui.dp(c, 320), -2))
        d.show()
    }

    private fun confirmDelete(m: WaMsg) {
        android.app.AlertDialog.Builder(this).setTitle("Delete for everyone?").setMessage("This message disappears for everyone in the group.")
            .setPositiveButton("Delete") { _, _ -> background { Fc.delete(chat, m) } }.setNegativeButton("Cancel", null).show()
    }

    private fun startReply(m: WaMsg) { clearMode(); replyTo = m; bannerText.text = "Replying to ${if (m.mine) Fc.settings.childName else m.sender.ifBlank { "message" }}: ${Rich.snippet(m)}"; banner.visibility = View.VISIBLE; box.requestFocus() }
    private fun startEdit(m: WaMsg) { clearMode(); editing = m; bannerText.text = "Editing your message"; banner.visibility = View.VISIBLE; box.setText(Rich.caption(m)); box.setSelection(box.text.length); box.requestFocus() }
    private fun clearMode() { val wasEditing = editing != null; replyTo = null; editing = null; banner.visibility = View.GONE; if (wasEditing) box.setText("") }

    /** Runs a send on a thread; shows the reason inline when it fails. */
    private fun background(block: () -> String) {
        error.visibility = View.GONE
        Thread { val r = try { block() } catch (e: Throwable) { Rich.sendError(e) }; if (r.isNotEmpty()) runOnUiThread { error.text = r; error.visibility = View.VISIBLE } }.start()
    }

    private fun sendNow() {
        val t = box.text.toString().trim()
        if (t.isEmpty()) return
        val e = editing; val q = replyTo
        box.setText(""); replyTo = null; editing = null; banner.visibility = View.GONE
        if (e != null) background { Fc.edit(chat, e, t).also { if (it.isNotEmpty()) runOnUiThread { box.setText(t) } } }
        else background { Fc.send(chat, t, q).also { if (it.isNotEmpty()) runOnUiThread { box.setText(t) } } }
    }

    // ---- stickers
    private fun buildStickerPanel(): LinearLayout {
        val c = this
        val panel = Ui.col(c).apply { setBackgroundColor(Ui.GROUND); setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 4)) }
        val tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val grid = GridLayout(c).apply { columnCount = 4 }
        val scrollG = ScrollView(c).apply { addView(grid) }
        fun show(pack: Stickers.Pack) {
            grid.removeAllViews()
            for (a in Stickers.list(c, pack.id)) {
                val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Sticker"; isClickable = true; setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6))
                    try { setImageDrawable(android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(c.assets, a))) } catch (_: Throwable) { }
                    setOnClickListener { stickerPanel.visibility = View.GONE; background { Fc.sendSticker(chat, a) } } }
                grid.addView(iv, GridLayout.LayoutParams().apply { width = Ui.dp(c, 80); height = Ui.dp(c, 80) })
            }
        }
        for (p in Stickers.packs) tabs.addView(Ui.text(c, p.name, 16f, Ui.ACCENT, true).apply { gravity = Gravity.CENTER; minHeight = Ui.dp(c, 48); setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0); isClickable = true; setOnClickListener { show(p) } })
        panel.addView(tabs); panel.addView(scrollG, Ui.params(h = Ui.dp(c, 180)))
        panel.addView(Ui.text(c, "Sends “Sent by ${Fc.settings.childName}” and then the sticker, so everyone knows who sent it.", 14f, Ui.MUTED), Ui.params(c = c, top = 4))
        show(Stickers.packs[0])
        return panel
    }

    private fun toggleStickers() { stickerPanel.visibility = if (stickerPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

    // ---- photos and videos (only when the parent has allowed them)
    private fun attachSheet() {
        val c = this
        val d = Dialog(c); d.window?.setBackgroundDrawable(ColorDrawable(0))
        val card = Ui.card(c).apply { setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12)) }
        fun item(label: String, icon: String, go: () -> Unit) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dp(c, 56); isClickable = true
            addView(IconView(c, icon, Ui.ACCENT), Ui.params(Ui.dp(c, 32), Ui.dp(c, 32))); addView(Ui.text(c, label, 18f, Ui.INK).apply { setPadding(Ui.dp(c, 16), 0, 0, 0) }); setOnClickListener { d.dismiss(); go() } }
        card.addView(item("Photos", "photo") { pick(false) }); card.addView(Ui.divider(c))
        card.addView(item("Videos", "video") { pick(true) }); card.addView(Ui.divider(c))
        card.addView(item("Camera", "photo") { camera() })
        card.addView(Ui.text(c, "You choose a picture or video from this phone. Your name is added to the caption, and where a photo was taken is removed before it is sent.", 14f, Ui.MUTED), Ui.params(c = c, top = 8))
        d.setContentView(card, android.view.ViewGroup.LayoutParams(Ui.dp(c, 320), -2)); d.show()
    }

    private fun pick(video: Boolean) {
        error.visibility = View.GONE
        val type = if (video) "video/*" else "image/*"
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType(type) else Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, if (video) REQ_VIDEO else REQ_PHOTO) } catch (_: Throwable) { showError("No picture chooser was found on this phone.") }
    }

    private fun camera() {
        // the system camera app takes the picture (this app needs no camera permission); it writes into one private file this app hands it
        showCameraChoice()
    }

    private fun showCameraChoice() {
        val c = this
        val d = Dialog(c); d.window?.setBackgroundDrawable(ColorDrawable(0))
        val card = Ui.card(c)
        card.addView(Ui.row(c, "Take a photo") { d.dismiss(); capture(false) }); card.addView(Ui.divider(c))
        card.addView(Ui.row(c, "Record a video") { d.dismiss(); capture(true) })
        d.setContentView(card, android.view.ViewGroup.LayoutParams(Ui.dp(c, 300), -2)); d.show()
    }

    private fun capture(video: Boolean) {
        val name = "cap-${System.nanoTime()}" + if (video) ".mp4" else ".jpg"
        val uri = CaptureProvider.uriFor(name)
        val i = Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("", uri) }
        if (video) i.putExtra(MediaStore.EXTRA_DURATION_LIMIT, Rich.MAX_VIDEO_SECONDS).putExtra(MediaStore.EXTRA_SIZE_LIMIT, Rich.MAX_VIDEO_BYTES)
        pendingCapture = name to video
        try { startActivityForResult(i, REQ_CAPTURE) } catch (_: Throwable) { pendingCapture = null; showError("No camera app was found on this phone.") }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) { pendingCapture = null; return }
        val (uri, video) = when (req) {
            REQ_PHOTO -> data?.data to false
            REQ_VIDEO -> data?.data to true
            REQ_CAPTURE -> pendingCapture?.let { CaptureProvider.uriFor(it.first) to it.second } ?: (null to false)
            else -> null to false
        }
        pendingCapture = null
        if (uri == null) return
        startActivity(Intent(this, SendActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("chat", chat).putExtra("title", title).putExtra("video", video))
    }

    private fun showError(t: String) { error.text = t; error.visibility = View.VISIBLE }

    companion object { private const val REQ_PHOTO = 11; private const val REQ_VIDEO = 12; private const val REQ_CAPTURE = 13 }
}
