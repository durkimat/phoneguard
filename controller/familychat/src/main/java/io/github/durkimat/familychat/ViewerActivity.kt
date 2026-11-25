package io.github.durkimat.familychat

import io.github.durkimat.media.Media
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.VideoView

/** A picture or video full screen (the viewer): who sent it, where and when, and its caption. Files are read from the app's private folder. */
class ViewerActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val c = this
        val path = intent.getStringExtra("path") ?: ""
        val kind = intent.getStringExtra("kind") ?: "image"
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B1116.toInt()) }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 8))
            addView(IconView(c, "back", 0xFFFFFFFF.toInt()).apply { contentDescription = "Back"; isClickable = true; setOnClickListener { finish() } }, Ui.params(Ui.dp(c, 48), Ui.dp(c, 48)))
            addView(Ui.col(c).apply { addView(Ui.text(c, intent.getStringExtra("who") ?: "", 18f, 0xFFFFFFFF.toInt(), true)); addView(Ui.text(c, intent.getStringExtra("sub") ?: "", 14f, 0xFFB8C4CE.toInt())) }, Ui.params(0, weight = 1f)) }
        root.addView(head, Ui.params())
        if (kind == "video" || kind == "gif") {
            val v = VideoView(c); val mc = MediaController(c); mc.setAnchorView(v); v.setMediaController(mc); v.setVideoPath(path)
            v.setOnPreparedListener { if (kind == "gif") it.isLooping = true; v.start() }
            root.addView(v, Ui.params(h = 0, weight = 1f).also { it.gravity = Gravity.CENTER })
        } else {
            val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Picture" }
            val d = Media.drawable(path); if (d != null) { iv.setImageDrawable(d); Media.start(d) } else Media.bitmap(path, 2048)?.let { iv.setImageBitmap(it) }
            root.addView(iv, Ui.params(h = 0, weight = 1f))
        }
        val cap = intent.getStringExtra("caption") ?: ""
        if (cap.isNotBlank()) root.addView(Ui.text(c, cap, 17f, 0xFFFFFFFF.toInt()).apply { setPadding(Ui.dp(c, 20), Ui.dp(c, 12), Ui.dp(c, 20), Ui.dp(c, 16)) }, Ui.params())
        Ui.fitInsets(root)
        setContentView(root)
    }
}
