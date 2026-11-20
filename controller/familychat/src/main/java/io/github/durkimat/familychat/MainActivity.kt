package io.github.durkimat.familychat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout

/** Screen 1: the chats. One card per allowed group; the admin group is never here (it is not in the allowed list and the store only keeps allowed chats). */
class MainActivity : Activity() {
    private val redraw: () -> Unit = { draw() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fc.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (!Fc.settings.setupDone) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        ConnectionService.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (!Fc.settings.setupDone) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        Fc.listeners.add(redraw); draw()
        Thread { Fc.open(); Fc.connect() }.start()
    }

    override fun onPause() { Fc.listeners.remove(redraw); super.onPause() }

    private fun draw() {
        val c = this
        val s = Fc.settings
        val rows = Fc.store().chats().associateBy { it.number }
        val body = Ui.pad(c)
        if (Fc.status == "disconnected" || Fc.status == "logged_out" || Fc.status == "error")
            body.addView(Ui.text(c, "Not connected. Trying again…", 15f, Ui.MUTED), Ui.params(c = c, top = 0))
        val cards = s.allowedGroups.map { g -> g to rows["g${g.id}"] }
            .sortedWith(compareByDescending<Pair<Group, io.github.durkimat.wacore.WaStore.ChatRow?>> { it.second?.last?.date ?: 0L }.thenBy { it.first.name.lowercase() })
        for ((g, r) in cards) {
            val name = r?.name?.takeIf { it.isNotBlank() && it != "g${g.id}" } ?: g.name
            val line = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Ui.shape(c, Ui.CARD, 16, Ui.BORDER)
                setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12)); minimumHeight = Ui.dp(c, 72); isClickable = true
                setOnClickListener { startActivity(Intent(c, ConversationActivity::class.java).putExtra("id", g.id).putExtra("name", name)) }
            }
            line.addView(Ui.circle(c, name.take(1).uppercase()))
            val mid = Ui.col(c)
            val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(Ui.text(c, name, 18f, Ui.INK, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.params(0, weight = 1f))
            r?.last?.let { top.addView(Ui.text(c, Ui.timeText(it.date), 14f, Ui.MUTED)) }
            mid.addView(top)
            val last = r?.last
            val preview = if (last == null) "No messages yet" else (if (last.mine) "${s.childName}: " else if (last.sender.isNotBlank()) "${last.sender}: " else "") + last.body
            mid.addView(Ui.text(c, preview, 16f, Ui.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.params(c = c, top = 4))
            line.addView(mid, Ui.params(0, weight = 1f).also { it.leftMargin = Ui.dp(c, 12) })
            if ((r?.unread ?: 0) > 0) line.addView(Ui.text(c, r!!.unread.toString(), 14f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER; minWidth = Ui.dp(c, 24); minHeight = Ui.dp(c, 24); setPadding(Ui.dp(c, 7), Ui.dp(c, 3), Ui.dp(c, 7), Ui.dp(c, 3)); background = Ui.shape(c, Ui.ACCENT, 12)
            }, Ui.params(w = -2).also { it.leftMargin = Ui.dp(c, 8) })
            body.addView(line, Ui.params(c = c, top = 12))
        }
        body.addView(Ui.text(c, "Only the groups your parent has chosen are shown here.", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER }, Ui.params(c = c, top = 20))
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 4))
            addView(Ui.title(c, "Family Chat"), Ui.params(0, weight = 1f))
            addView(IconView(c, "lock").apply { contentDescription = "Admin"; isClickable = true; setOnClickListener { startActivity(Intent(c, PinActivity::class.java)) } }, Ui.params(Ui.dp(c, 48), Ui.dp(c, 48)))
        }
        setContentView(Ui.screen(c, body, header = header))
    }
}
