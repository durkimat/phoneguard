package io.github.durkimat.familychat

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.durkimat.wacore.WaLabels
import java.io.File

/**
 * Screens P1 to P3: a chosen or captured photo or video, a caption, "Everyone will see: ..." and the send. A photo is turned upright, scaled and saved
 * again as a new JPEG (no place or camera details survive); a video is shortened to the limits, re-encoded and stripped (see [VideoPrep]).
 * Refused unless the parent has allowed photos and videos.
 */
class SendActivity : Activity() {
    private var busy = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fc.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val c = this
        val uri: Uri? = intent.data
        val chat = intent.getStringExtra("chat") ?: ""
        val video = intent.getBooleanExtra("video", false)
        if (uri == null || !Fc.settings.photosAllowed || Fc.settings.allowedGroups.none { "g${it.id}" == chat }) { finish(); return }
        val child = Fc.settings.childName
        val body = Ui.pad(c)
        body.addView(Ui.text(c, "To: ${intent.getStringExtra("title") ?: ""}", 18f, Ui.INK, true))
        val pic = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.shape(c, Ui.THEIRS, 14); contentDescription = if (video) "Video" else "Photo" }
        body.addView(pic, Ui.params(h = Ui.dp(c, 240), c = c, top = 12))
        Thread {
            val bmp = if (video) try { android.media.MediaMetadataRetriever().use { it.setDataSource(c, uri); it.getFrameAtTime(500_000) } } catch (_: Throwable) { null }
            else try { c.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) } } catch (_: Throwable) { null }
            if (bmp != null) runOnUiThread { pic.setImageBitmap(bmp) }
        }.start()
        val caption = Ui.input(c, "Caption")
        body.addView(caption, Ui.params(c = c, top = 12))
        val see = Ui.text(c, "", 16f, Ui.INK)
        body.addView(see, Ui.params(c = c, top = 8))
        body.addView(Ui.text(c, if (video) "Where the video was recorded is removed before sending. Videos up to 5 minutes." else "Where the photo was taken is removed before sending.", 14f, Ui.MUTED), Ui.params(c = c, top = 4))
        val status = Ui.text(c, "", 16f, Ui.ACCENT, true); body.addView(status, Ui.params(c = c, top = 12))
        val err = Ui.text(c, "", 16f, Ui.DANGER); body.addView(err, Ui.params(c = c, top = 4))
        fun upd() { see.text = "Everyone will see: “${WaLabels.caption(child, caption.text.toString())}”" }
        upd()
        caption.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { upd() }; override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {} })
        lateinit var send: TextView
        send = Ui.button(c, "Send") {
            if (busy) return@button
            busy = true; Ui.enable(send, false); err.text = ""
            val text = caption.text.toString()
            if (video) {
                status.text = "Preparing video…"
                VideoPrep.run(c, uri, Media.dir(c, "outgoing"), { p -> status.text = "Sending video… $p%" }) { d ->
                    if (d.file == null) { fail(err, status, send, d.error ?: "That video could not be sent.") }
                    else Thread { val r = Fc.sendVideo(chat, d.file, text, d.seconds, d.width, d.height); runOnUiThread { if (r.isEmpty()) finish() else fail(err, status, send, r) } }.start()
                }
            } else {
                status.text = "Sending…"
                Thread {
                    val out = File(Media.dir(c, "outgoing"), "send-${System.nanoTime()}.jpg")
                    val p = Media.preparePhoto(c, uri, out)
                    val r = if (p == null) "That photo could not be sent." else Fc.sendPhoto(chat, p.first, text, p.second, p.third)
                    runOnUiThread { if (r.isEmpty()) finish() else fail(err, status, send, r) }
                }.start()
            }
        }
        setContentView(Ui.screen(c, body, Ui.footer(c, send, Ui.button(c, "Cancel", primary = false) { if (!busy) finish() })))
    }

    private fun fail(err: TextView, status: TextView, send: TextView, msg: String) { busy = false; Ui.enable(send, true); status.text = ""; err.text = msg }
    override fun onBackPressed() { if (!busy) super.onBackPressed() }
}
