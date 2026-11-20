package io.github.durkimat.familychat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Screens 4 to 7 live in this one activity, so the child cannot reach one of them without the PIN: it closes the moment it is left.
 * Menu, Add or remove groups (the shared picker), the admin group explanation, About and risks, Change PIN.
 */
class AdminActivity : Activity() {
    private enum class Page { MENU, GROUPS, ADMIN_GROUP, ABOUT, PIN }
    private var page = Page.MENU
    private var leaving = false

    override fun onCreate(b: Bundle?) { super.onCreate(b); Fc.init(this); show(Page.MENU) }

    override fun onStop() { if (!leaving && !isChangingConfigurations) finish(); super.onStop() }
    override fun onBackPressed() { if (page == Page.MENU) finish() else show(Page.MENU) }

    private fun show(p: Page) {
        page = p
        setContentView(when (p) { Page.MENU -> menu(); Page.GROUPS -> groups(); Page.ADMIN_GROUP -> adminGroup(); Page.ABOUT -> about(); Page.PIN -> changePin() })
    }

    private fun header(title: String, right: String, onRight: () -> Unit): View {
        val c = this
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), Ui.dp(c, 4))
            addView(Ui.title(c, title), Ui.params(0, weight = 1f))
            addView(Ui.text(c, right, 18f, Ui.ACCENT, true).apply { minHeight = Ui.dp(c, 48); gravity = android.view.Gravity.CENTER_VERTICAL; isClickable = true; setOnClickListener { onRight() } })
        }
    }

    // ---- screen 4
    private fun menu(): View {
        val c = this; val s = Fc.settings
        val body = Ui.pad(c)
        val groups = Ui.card(c)
        groups.addView(Ui.text(c, "Groups ${s.childName} can use (${s.allowedGroups.size})", 18f, Ui.INK, true))
        if (s.allowedGroups.isEmpty()) groups.addView(Ui.text(c, "None yet", 17f, Ui.MUTED), Ui.params(c = c, top = 8))
        for (g in s.allowedGroups.sortedBy { it.name.lowercase() }) groups.addView(Ui.text(c, g.name, 17f), Ui.params(c = c, top = 8))
        groups.addView(Ui.divider(c), Ui.params(c = c, top = 12))
        groups.addView(Ui.row(c, "Add or remove groups", chevron = true) { show(Page.GROUPS) })
        body.addView(groups, Ui.params())
        val opts = Ui.card(c)
        opts.addView(Ui.text(c, "Chat options", 18f, Ui.INK, true))
        fun toggle(label: String, on: Boolean, flip: () -> Unit) { opts.addView(Ui.divider(c), Ui.params(c = c, top = 4)); opts.addView(Ui.row(c, label, if (on) "On" else "Off", chevron = false) { flip(); Fc.applyConfig(); show(Page.MENU) }) }
        toggle("Show pictures, GIFs and stickers", s.showMedia) { s.showMedia = !s.showMedia }
        toggle("Tell WhatsApp when read", s.sendReadReceipts) { s.sendReadReceipts = !s.sendReadReceipts }
        toggle("Allow sending photos and videos", s.photosAllowed) { s.photosAllowed = !s.photosAllowed }
        toggle("Download videos only on Wi-Fi", s.wifiOnlyVideos) { s.wifiOnlyVideos = !s.wifiOnlyVideos }
        body.addView(opts, Ui.params(c = c, top = 16))
        val settings = Ui.card(c)
        settings.addView(Ui.row(c, "Child's name", s.childName)); settings.addView(Ui.divider(c))
        settings.addView(Ui.row(c, "Your name", s.parentName)); settings.addView(Ui.divider(c))
        settings.addView(Ui.row(c, "Admin group", WaNames.adminName(s), chevron = true) { show(Page.ADMIN_GROUP) }); settings.addView(Ui.divider(c))
        val exempt = ConnectionService.batteryExempt(c)
        settings.addView(Ui.row(c, "Stay connected", if (exempt) "Allowed" else "Tap to allow", chevron = !exempt) { if (!exempt) { leaving = true; startActivityForResult(ConnectionService.batteryExemptionIntent(c), 1) } }); settings.addView(Ui.divider(c))
        settings.addView(Ui.row(c, "Change PIN", chevron = true) { show(Page.PIN) }); settings.addView(Ui.divider(c))
        settings.addView(Ui.row(c, "About and risks", chevron = true) { show(Page.ABOUT) })
        body.addView(settings, Ui.params(c = c, top = 16))
        val un = Ui.card(c).apply { addView(Ui.row(c, "Unlink this device", danger = true) { confirmUnlink() }) }
        body.addView(un, Ui.params(c = c, top = 16))
        return Ui.screen(c, body, header = header("Admin", "Close") { finish() })
    }

    override fun onActivityResult(r: Int, res: Int, d: Intent?) { leaving = false; if (page == Page.MENU) show(Page.MENU) }

    private fun confirmUnlink() {
        AlertDialog.Builder(this).setTitle("Unlink this device?")
            .setMessage("Family Chat stops working and everything it kept on this phone is removed. Your WhatsApp account is not affected.")
            .setPositiveButton("Unlink") { _, _ ->
                Thread { Fc.unlink(); runOnUiThread { ConnectionService.stop(this); leaving = true; startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() } }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- screen 7
    private fun groups(): View {
        val c = this; val s = Fc.settings
        val model = PickerModel(s.knownGroups.filter { it.id != s.adminGroupId }, s.allowedGroups)
        val picker = GroupPickerView(c, model) { chosen -> s.allowedGroups = chosen; Fc.applyConfig() } // changes apply straight away
        val body = Ui.pad(c)
        body.addView(Ui.title(c, "Which groups can ${s.childName} use?").apply { textSize = 24f })
        body.addView(Ui.text(c, "Tick the groups ${s.childName} can use. Changes apply straight away.", 17f, Ui.MUTED), Ui.params(c = c, top = 8))
        body.addView(picker, Ui.params(c = c, top = 16))
        picker.setLoading()
        Thread {
            val l = Fc.groups().filter { it.id != s.adminGroupId }
            runOnUiThread { picker.setGroups(l, failed = l.isEmpty()) }
        }.start()
        return Ui.screen(c, body, Ui.footer(c, Ui.button(c, "Done") { show(Page.MENU) }))
    }

    // ---- screen 5, as help: what the parent sees in their own WhatsApp
    private fun adminGroup(): View {
        val c = this; val s = Fc.settings
        val body = Ui.pad(c)
        body.addView(Ui.text(c, "In the parent's own WhatsApp, never on ${s.childName}'s screen", 15f, Ui.MUTED))
        body.addView(Ui.title(c, WaNames.adminName(s)).apply { textSize = 24f }, Ui.params(c = c, top = 6))
        body.addView(Ui.text(c, "Send an instruction in this group from your own phone. Family Chat answers in the group, each reply starting with ».", 17f), Ui.params(c = c, top = 12))
        val ex = Ui.card(c)
        fun you(t: String) = Ui.text(c, t, 17f).apply { setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8)); background = Ui.shape(c, Ui.ACCENT_SOFT, 12) }
        fun reply(t: String) = Ui.text(c, "» $t", 17f).apply { setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8)); background = Ui.shape(c, Ui.THEIRS, 12) }
        ex.addView(you("add group Cousins")); ex.addView(reply("Added: Cousins"), Ui.params(c = c, top = 6))
        ex.addView(you("groups"), Ui.params(c = c, top = 12)); ex.addView(reply("${s.childName} can use: Family, Cousins"), Ui.params(c = c, top = 6))
        body.addView(ex, Ui.params(c = c, top = 16))
        val cmds = Ui.card(c)
        cmds.addView(Ui.text(c, "Commands", 17f, Ui.INK, true))
        for (line in io.github.durkimat.wacore.WaAcks.commandLines(true)) cmds.addView(Ui.text(c, line, 17f, Ui.INK), Ui.params(c = c, top = 6))
        body.addView(cmds, Ui.params(c = c, top = 16))
        return Ui.screen(c, body, Ui.footer(c, Ui.button(c, "Back", primary = false) { show(Page.MENU) }))
    }

    // ---- screen 6
    private fun about(): View {
        val c = this
        val body = Ui.pad(c)
        body.addView(Ui.warning(c, "Please know the risks", AboutText.RISK))
        val ver = Ui.card(c).apply { addView(Ui.text(c, "Version", 17f, Ui.INK, true)); addView(Ui.text(c, "Family Chat " + AboutText.version(c), 17f), Ui.params(c = c, top = 4)) }
        body.addView(ver, Ui.params(c = c, top = 16))
        val lic = Ui.card(c).apply { addView(Ui.text(c, "Open-source licences", 17f, Ui.INK, true)); addView(Ui.text(c, AboutText.LICENCES, 17f), Ui.params(c = c, top = 4)) }
        body.addView(lic, Ui.params(c = c, top = 16))
        return Ui.screen(c, body, header = header("About", "Back") { show(Page.MENU) })
    }

    // ---- change PIN
    private fun changePin(): View {
        val c = this; val s = Fc.settings
        val body = Ui.pad(c)
        body.addView(Ui.title(c, "Change PIN").apply { textSize = 24f })
        body.addView(Ui.text(c, "4 to 8 digits.", 17f, Ui.MUTED), Ui.params(c = c, top = 8))
        val t = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val a = Ui.input(c, "New PIN", t); val b = Ui.input(c, "New PIN again", t)
        body.addView(a, Ui.params(c = c, top = 16)); body.addView(b, Ui.params(c = c, top = 12))
        val err = Ui.text(c, "", 16f, Ui.DANGER); body.addView(err, Ui.params(c = c, top = 8))
        val save = Ui.button(c, "Save PIN") {
            val x = a.text.toString(); val y = b.text.toString()
            if (!PinStore.valid(x)) err.text = "A PIN is 4 to 8 digits." else if (x != y) err.text = "The two PINs are different." else { s.pin = PinStore.create(x); show(Page.MENU) }
        }
        return Ui.screen(c, body, Ui.footer(c, save, Ui.button(c, "Cancel", primary = false) { show(Page.MENU) }))
    }
}

object WaNames { fun adminName(s: Settings) = io.github.durkimat.wacore.WaCommands.adminGroupName(s.childName) }
