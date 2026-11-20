package io.github.durkimat.familychat

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Screen 2: one group. Others on the left under their name (the parent's own messages under the parent's name, never "Me"); the child's on the right in teal. */
class ConversationActivity : Activity() {
    private lateinit var chat: String
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var error: TextView
    private lateinit var box: EditText
    private val redraw: () -> Unit = { drawMessages() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fc.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val c = this
        chat = "g" + (intent.getStringExtra("id") ?: "")
        // only an allowed group opens; anything else goes back
        if (Fc.settings.allowedGroups.none { "g${it.id}" == chat }) { finish(); return }
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 20), Ui.dp(c, 8))
            addView(IconView(c, "back", Ui.INK).apply { contentDescription = "Back to chats"; isClickable = true; setOnClickListener { finish() } }, Ui.params(Ui.dp(c, 48), Ui.dp(c, 48)))
            addView(Ui.text(c, intent.getStringExtra("name") ?: "", 22f, Ui.INK, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.params(0, weight = 1f))
        }
        list = Ui.col(c).apply { setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8)) }
        scroll = ScrollView(c).apply { addView(list) }
        error = Ui.text(c, "", 15f, Ui.DANGER).apply { setPadding(Ui.dp(c, 20), Ui.dp(c, 4), Ui.dp(c, 20), 0); visibility = android.view.View.GONE }
        box = Ui.input(c, "Write as ${Fc.settings.childName}").apply { setSingleLine(false); maxLines = 4; contentDescription = "Message" }
        val send = LinearLayout(c).apply { background = Ui.shape(c, Ui.ACCENT); gravity = Gravity.CENTER; isClickable = true; contentDescription = "Send"; addView(IconView(c, "send", 0xFFFFFFFF.toInt()), Ui.params(Ui.dp(c, 28), Ui.dp(c, 28))); setOnClickListener { sendNow() } }
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 12)); setBackgroundColor(Ui.GROUND)
            addView(box, Ui.params(0, weight = 1f)); addView(send, Ui.params(Ui.dp(c, 56), Ui.dp(c, 56)).also { it.leftMargin = Ui.dp(c, 8) })
        }
        val root = Ui.col(c).apply { setBackgroundColor(Ui.GROUND) }
        root.addView(header, Ui.params()); root.addView(scroll, Ui.params(h = 0, weight = 1f)); root.addView(error, Ui.params()); root.addView(bar, Ui.params())
        root.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); Fc.listeners.add(redraw); drawMessages(); ConnectionService.start(this); Thread { Fc.connect() }.start() }
    override fun onPause() { Fc.listeners.remove(redraw); super.onPause() }

    private fun drawMessages() {
        if (!::list.isInitialized) return
        val c = this
        Fc.store().markRead(chat)
        list.removeAllViews()
        for (m in Fc.store().messages(chat)) {
            val mine = m.mine
            val bubble = Ui.col(c).apply {
                background = Ui.shape(c, if (mine) Ui.ACCENT else Ui.THEIRS, 16); setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
                addView(Ui.text(c, m.body, 17f, if (mine) 0xFFFFFFFF.toInt() else Ui.INK))
                if (mine) addView(Ui.text(c, Ui.timeText(m.date).let { if (it.contains(":")) it else it }, 13f, 0xFFD9EFEE.toInt()).apply { gravity = Gravity.END }, Ui.params(c = c, top = 4))
            }
            val item = Ui.col(c)
            if (!mine && m.sender.isNotBlank()) item.addView(Ui.text(c, m.sender, 14f, Ui.MUTED, true).apply { setPadding(Ui.dp(c, 4), 0, 0, Ui.dp(c, 4)) })
            item.addView(bubble)
            val lp = Ui.params(w = -2, c = c, top = 10).also { it.gravity = if (mine) Gravity.END else Gravity.START }
            lp.width = -2
            item.layoutParams = lp
            list.addView(item, lp)
        }
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun sendNow() {
        val t = box.text.toString().trim()
        if (t.isEmpty()) return
        box.setText("")
        error.visibility = android.view.View.GONE
        Thread {
            val r = Fc.send(chat, t)
            if (r.isNotEmpty()) runOnUiThread { error.text = r; error.visibility = android.view.View.VISIBLE; box.setText(t) }
        }.start()
    }
}
