package io.github.durkimat.familychat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Setup, one question per screen ("Step N of 6"). The steps and their rules are in [SetupFlow]; this only draws them. */
class SetupActivity : Activity() {
    private lateinit var flow: SetupFlow
    private var onChange: (() -> Unit)? = null
    private val redraw: () -> Unit = { onChange?.invoke() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fc.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        flow = SetupFlow(Fc.settings)
    }

    override fun onResume() { super.onResume(); Fc.listeners.add(redraw); render() }
    override fun onPause() { Fc.listeners.remove(redraw); super.onPause() }
    override fun onBackPressed() { /* setup has no way back: each step is saved as it is finished */ }

    private fun render() {
        onChange = null
        when (flow.step) {
            SetupFlow.Step.WELCOME -> welcome()
            SetupFlow.Step.PIN -> pin()
            SetupFlow.Step.NAMES -> names()
            SetupFlow.Step.LINK -> link()
            SetupFlow.Step.ADMIN_GROUP -> adminGroup()
            SetupFlow.Step.GROUPS -> groups()
            SetupFlow.Step.DONE -> { startActivity(Intent(this, MainActivity::class.java)); finish() }
        }
    }

    private fun page(title: String, intro: String? = null): LinearLayout {
        val c = this
        return Ui.pad(c).apply {
            addView(Ui.text(c, "Step ${flow.number} of 6", 15f, Ui.MUTED, true))
            addView(Ui.title(c, title), Ui.params(c = c, top = 8))
            if (intro != null) addView(Ui.text(c, intro, 17f), Ui.params(c = c, top = 12))
        }
    }

    private fun show(body: View, vararg buttons: View) = setContentView(Ui.screen(this, body, Ui.footer(this, *buttons)))

    private fun welcome() {
        val c = this
        val body = page("Set up Family Chat", "Family Chat lets a child chat in the WhatsApp groups you choose, using your account. You stay in charge with a PIN, and you can change the groups later by sending a message.")
        body.addView(Ui.warning(c, "Please know the risks", AboutText.SETUP_RISK), Ui.params(c = c, top = 16))
        val next = Ui.button(c, "Continue") { flow.acceptRisk(); render() }
        Ui.enable(next, false)
        val box = CheckBox(c).apply { text = "I understand, and I want to link my own account."; textSize = 17f; setTextColor(Ui.INK); minHeight = Ui.dp(c, 56); buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, on -> Ui.enable(next, on) } }
        body.addView(box, Ui.params(c = c, top = 16))
        show(body, next)
    }

    private fun pin() {
        val c = this
        val body = page("Choose an admin PIN", "You will need it to change anything. 4 to 8 digits.")
        val t = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val a = Ui.input(c, "PIN", t); val b = Ui.input(c, "Type it again", t)
        body.addView(a, Ui.params(c = c, top = 16)); body.addView(b, Ui.params(c = c, top = 12))
        val err = Ui.text(c, "", 16f, Ui.DANGER); body.addView(err, Ui.params(c = c, top = 8))
        show(body, Ui.button(c, "Continue") {
            val x = a.text.toString(); val y = b.text.toString()
            if (!PinStore.valid(x)) err.text = "A PIN is 4 to 8 digits." else if (x != y) err.text = "The two PINs are different." else { flow.setPin(x, y); render() }
        })
    }

    private fun names() {
        val c = this
        val body = page("Who is this for?")
        val child = Ui.input(c, "Child's name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val me = Ui.input(c, "Your name on their screen", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        body.addView(Ui.text(c, "Child's name", 17f, Ui.INK, true), Ui.params(c = c, top = 16)); body.addView(child, Ui.params(c = c, top = 6))
        body.addView(Ui.text(c, "Your name on their screen", 17f, Ui.INK, true), Ui.params(c = c, top = 16)); body.addView(me, Ui.params(c = c, top = 6))
        body.addView(Ui.text(c, "Your own messages from your phone show under this name, not \"Me\". You type it once.", 15f, Ui.MUTED), Ui.params(c = c, top = 8))
        val err = Ui.text(c, "", 16f, Ui.DANGER); body.addView(err, Ui.params(c = c, top = 8))
        show(body, Ui.button(c, "Continue") { if (flow.setNames(child.text.toString(), me.text.toString())) render() else err.text = "Please type both names (up to 30 letters)." })
    }

    private fun link() {
        val c = this
        val body = page("Link your WhatsApp")
        body.addView(Ui.text(c, "Your WhatsApp number", 17f, Ui.INK, true), Ui.params(c = c, top = 16))
        val num = Ui.input(c, "+44 7700 900123", InputType.TYPE_CLASS_PHONE); body.addView(num, Ui.params(c = c, top = 6))
        val get = Ui.button(c, "Get my link code", primary = false) { }
        body.addView(get, Ui.params(c = c, top = 12))
        val codeCard = Ui.card(c).apply { visibility = View.GONE; gravity = Gravity.CENTER_HORIZONTAL }
        val code = Ui.text(c, "", 36f, Ui.ACCENT, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.08f }
        codeCard.addView(Ui.text(c, "Your link code", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER }); codeCard.addView(code, Ui.params(c = c, top = 6))
        body.addView(codeCard, Ui.params(c = c, top = 16))
        val steps = Ui.text(c, "1. On your own phone, open WhatsApp and go to Settings, then Linked devices.\n2. Tap Link a device, then Link with phone number instead.\n3. Type the code above.", 17f)
        body.addView(steps, Ui.params(c = c, top = 16))
        val status = Ui.text(c, "", 16f, Ui.MUTED); body.addView(status, Ui.params(c = c, top = 12))
        val next = Ui.button(c, "It says linked. Continue") { render() }
        Ui.enable(next, false)
        get.setOnClickListener {
            val digits = num.text.toString().filter { it.isDigit() }
            if (digits.length < 7) { status.text = "Please type your number with the country code."; return@setOnClickListener }
            status.text = "Asking WhatsApp for a code…"
            Thread { val e = Fc.pair(digits); runOnUiThread { if (e.isNotEmpty()) status.text = "Could not start: $e" } }.start()
        }
        onChange = {
            val pc = Fc.pairCode
            if (pc != null) { code.text = if (pc.length == 8 && !pc.contains('-')) pc.substring(0, 4) + "-" + pc.substring(4) else pc; codeCard.visibility = View.VISIBLE; status.text = "" }
            val linked = Fc.settings.linked || Fc.status == "paired"
            Ui.enable(next, linked)
            if (linked) { status.text = "Linked."; status.setTextColor(Ui.ACCENT) }
        }
        show(body, next)
        Thread { Fc.open(); runOnUiThread { onChange?.invoke() } }.start()
    }

    private fun adminGroup() {
        val c = this
        val name = flow.adminGroupName()
        val body = page("Make the admin group", "This is a private group where you send instructions to Family Chat. ${Fc.settings.childName} never sees it.")
        body.addView(Ui.text(c, "1. In WhatsApp, make a new group with one person you trust.\n2. Name it $name.\n3. Everyone in this group can send instructions, so only add adults you trust. Never add ${Fc.settings.childName}'s own number.\n4. You can remove the person you started the group with afterwards, if you like.", 17f), Ui.params(c = c, top = 16))
        body.addView(Ui.text(c, "Groups found starting “${io.github.durkimat.wacore.WaCommands.ADMIN_PREFIX}”", 17f, Ui.INK, true), Ui.params(c = c, top = 20))
        val card = Ui.card(c); body.addView(card, Ui.params(c = c, top = 8))
        var picked: String? = null
        val use = Ui.button(c, "Use this group") {
            val id = picked ?: return@button
            flow.bindAdmin(id); Thread { Fc.applyConfig(); render0() }.start()
        }
        Ui.enable(use, false)
        fun fill(found: List<Pair<String, String>>?) {
            card.removeAllViews()
            if (found == null) { card.addView(Ui.text(c, "Looking…", 17f, Ui.MUTED)); return }
            if (found.isEmpty()) { card.addView(Ui.text(c, "None found yet. Make the group, then tap Look again.", 17f, Ui.MUTED)); return }
            val rg = RadioGroup(c)
            for ((id, n) in found) rg.addView(RadioButton(c).apply {
                text = if (found.count { it.second.equals(n, true) } > 1) "$n (ends ${id.takeLast(4)})" else n; textSize = 17f; setTextColor(Ui.INK); minHeight = Ui.dp(c, 56)
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT); tag = id
            })
            rg.setOnCheckedChangeListener { g, i -> picked = g.findViewById<View>(i)?.tag as? String; Ui.enable(use, picked != null) }
            card.addView(rg)
            if (found.size == 1) (rg.getChildAt(0) as RadioButton).isChecked = true
        }
        fun look() {
            fill(null); picked = null; Ui.enable(use, false)
            Thread {
                val found = try { Fc.groups(); flow.adminCandidates(io.github.durkimat.familychat.Group.format(Fc.settings.knownGroups)) } catch (_: Throwable) { emptyList() }
                runOnUiThread { fill(found) }
            }.start()
        }
        val again = Ui.button(c, "Look again", primary = false) { look() }
        show(body, use, again)
        look()
    }

    private fun render0() = runOnUiThread { render() }

    private fun groups() {
        val c = this; val s = Fc.settings
        val body = page("Which groups can ${s.childName} use?", "${s.childName} sees everything anyone writes in these groups, and can write in them. Start with just the ones you need. You can add more later.")
        body.addView(Ui.text(c, "When you finish, Android will ask to let Family Chat show notifications, so ${s.childName} sees new messages. You can say no and still use the app.", 15f, Ui.MUTED), Ui.params(c = c, top = 8))
        val model = PickerModel(s.knownGroups.filter { it.id != s.adminGroupId })
        val picker = GroupPickerView(c, model) { }
        body.addView(picker, Ui.params(c = c, top = 16))
        picker.setLoading()
        Thread { val l = Fc.groups().filter { it.id != s.adminGroupId }; runOnUiThread { picker.setGroups(l, failed = l.isEmpty()) } }.start()
        show(body, Ui.button(c, "Finish and give it to ${s.childName}") {
            flow.finish(model.chosenGroups())
            requestNotifications()
            Thread { Fc.applyConfig(); Fc.sendAdmin(io.github.durkimat.wacore.WaAcks.ready()) }.start()
            ConnectionService.start(this)
            render()
        })
    }

    private fun requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
