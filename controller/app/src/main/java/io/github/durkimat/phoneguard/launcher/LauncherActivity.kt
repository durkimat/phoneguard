package io.github.durkimat.phoneguard.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.view.KeyEvent
import android.view.WindowManager
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.VOICE_ASSIST
import io.github.durkimat.phoneguard.WEB_SEARCH
import io.github.durkimat.phoneguard.whatsapp.WhatsApp

/**
 * A keypad-driven Home screen. It owns no behaviour of its own: [LauncherModel] decides what a key
 * does and a [LauncherSkin] draws the result. Touch is ignored.
 */
class LauncherActivity : Activity() {
    companion object {
        @Volatile private var words: WordList? = null
        const val EXTRA_DIAL = "dial"
    }

    private val skin: LauncherSkin = NokiaSkin()
    private val model = LauncherModel()
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 20_000) } }

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        model.theme = prefs().getString("theme", Themes.DEFAULT) ?: Themes.DEFAULT
        model.font = prefs().getString("font", Fonts.DEFAULT) ?: Fonts.DEFAULT
        WhatsApp.onChange(waChanged)
        model.predictiveOn = prefs().getBoolean("predictive", true)
        model.speakerShown = prefs().getBoolean("speaker", true)
        model.waNameStyle = prefs().getString("wa_names", NameStyle.DEFAULT) ?: NameStyle.DEFAULT
        loadWords()
        setContentView(skin.create(this))
        refresh()
        pendingDial = intent?.getStringExtra(EXTRA_DIAL)
        if (intent?.action == VOICE_ASSIST || intent?.action == WEB_SEARCH) leftKeyPending = true
    }

    /** The left key under the screen arrives as a voice-assist request (see HardKeys). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_DIAL)) pendingDial = intent.getStringExtra(EXTRA_DIAL)
        // onResume follows and resets to the home screen, so remember the key and apply it after that
        if (intent.action == VOICE_ASSIST || intent.action == WEB_SEARCH) leftKeyPending = true
    }

    private var pendingDial: String? = null
    private var leftKeyPending = false
    private var cameBack = true

    override fun onStop() { cameBack = true; super.onStop() }

    override fun onResume() {
        super.onResume()
        // coming back from another app shows the home screen; a key press while we are in front must not reset it
        if (cameBack) { model.goHome(); cameBack = false }
        refresh()
        pendingDial?.let { model.startDial(it); pendingDial = null; refresh() }
        if (leftKeyPending) { leftKeyPending = false; perform(model.onKey(Key.SOFT_LEFT, -1)) }
        registerReceiver(changes, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }.also { f -> f.addAction(Intent.ACTION_TIME_CHANGED) }, Context.RECEIVER_EXPORTED)
        ui.postDelayed(tick, 20_000)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        try { unregisterReceiver(changes) } catch (_: Exception) { }
        super.onPause()
    }

    /** Whether the word list knows a word (so a capital on an unknown word at the start of a message is read as a name). */
    private fun knownWord(w: String): Boolean = words?.exact(WordList.keyOf(w))?.any { it.equals(w, true) } ?: true

    /** The word list is read off the main thread, so the launcher opens at once and gains predictive text a moment later. */
    private fun loadWords() {
        if (words != null) { model.predictor = words; return }
        Thread {
            try {
                val list = WordList.parse(assets.open("words/words-en-gb.txt").bufferedReader().use { it.readText() })
                words = list
                val learned = io.github.durkimat.phoneguard.whatsapp.LearnedStore.get(this)
                // the names of the people in the phone's Contacts are suggested from the start
                for (ct in LauncherData.contacts(this)) for (part in ct.name.split(' ', '-')) learned.seed(part)
                val predictor = LearningPredictor(list, learned)
                ui.post { model.predictor = predictor; refresh() }
            } catch (e: Exception) { Log.log(this, "launcher: word list failed: ${e.javaClass.simpleName}") }
        }.start()
    }

    private fun prefs() = createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private val waChanged: () -> Unit = { runOnUiThread { refresh() } }

    override fun onDestroy() { WhatsApp.offChange(waChanged); super.onDestroy() }

    private val hideNotice = Runnable { model.clearNotice(); refresh() }

    private fun refresh() {
        // a notice ("Message sent") goes by itself after a moment
        ui.removeCallbacks(hideNotice)
        if (model.notice != null) ui.postDelayed(hideNotice, 1_500)
        model.setItems(LauncherData.items(this))
        if (model.screen == Screen.HOME || model.screen == Screen.MENU || model.contacts.isEmpty()) model.contacts = LauncherData.contacts(this)
        if (model.screen != Screen.CALLLOG) model.calls = LauncherData.calls(this, model.contacts)
        // not while reading or writing, so a message arriving cannot shift what is on the screen
        if (model.screen != Screen.THREAD && model.screen != Screen.WRITE) model.convos = LauncherData.convos(this, model.contacts)
        // WhatsApp is offered unless the mode switches it off; "linked" says whether an account is attached
        model.waLinked = WhatsApp.linked(this)
        model.waEnabled = WhatsApp.policy(this) != "off" && !LauncherData.useRealWhatsApp(this)
        model.waCode = WhatsApp.pairCode
        if (model.screen == Screen.WAPAIR && model.waLinked) { model.waCode = null }
        if (model.waLinked) {
            model.waStatus = WhatsApp.status
            model.waUnread = WhatsApp.store(this).unreadTotal()
            if (model.screen != Screen.THREAD && model.screen != Screen.WRITE) model.waConvos = WhatsApp.convos(this, model.contacts)
        }
        skin.render(model, LauncherData.info(this))
    }

    private fun key(code: Int): Pair<Key, Int>? = KeyMap.of(code)

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val k = key(ev.keyCode) ?: return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(model.onKey(k.first, k.second))
        return true // consume the key up as well so Back never leaves the launcher
    }

    private fun perform(a: Action) {
        when (a) {
            Action.None -> {}
            Action.Redraw -> refresh()
            is Action.PairWa -> Thread {
                val r = WhatsApp.pair(this, a.number)
                ui.post { if (r.startsWith("error")) model.notify("Could not start: " + r.removePrefix("error: ").take(40)); refresh() }
            }.start()
            is Action.SaveNameStyle -> { prefs().edit().putString("wa_names", a.id).apply(); refresh() }
            Action.ResyncWa -> Thread {
                val r = WhatsApp.resync(this)
                ui.post { model.notify(if (r.startsWith("error")) "Re-sync failed: " + r.removePrefix("error: ").take(28) else "Re-synced"); refresh() }
            }.start()
            Action.UnlinkWa -> Thread {
                val r = WhatsApp.logout(this)
                ui.post { model.notify(if (r.startsWith("error")) "Could not unlink: " + r.removePrefix("error: ").take(30) else "Unlinked"); refresh() }
            }.start()
            Action.WaOpen -> { WhatsApp.touch(this); refresh() }
            is Action.WaRead -> { WhatsApp.store(this).markRead(a.chat); refresh() }
            is Action.SendWa -> Thread {
                val r = WhatsApp.sendNow(this, a.chat, a.body)
                if (r == "sent") io.github.durkimat.phoneguard.whatsapp.LearnedStore.learn(this, a.body, ::knownWord)
                ui.post { if (r != "sent") model.notify("Not sent: $r"); refresh() }
            }.start()
            is Action.SaveSpeaker -> { prefs().edit().putBoolean("speaker", a.shown).apply(); refresh() }
            is Action.SavePredictive -> { prefs().edit().putBoolean("predictive", a.on).apply(); refresh() }
            is Action.SaveFont -> { prefs().edit().putString("font", a.id).apply(); refresh() }
            is Action.SaveTheme -> { prefs().edit().putString("theme", a.id).apply(); refresh() }
            is Action.Launch -> launch(a.pkg)
            is Action.PlaceCall -> placeCall(a.number)
            // "Call" on a contact or in the call log opens our own dial screen with the number typed; one more press rings
            is Action.Call -> { model.startDial(a.number); refresh() }
            is Action.Send -> {
                val r = LauncherData.sendSms(this, a.to, a.body)
                if (r == "Message sent") io.github.durkimat.phoneguard.whatsapp.LearnedStore.learn(this, a.body, ::knownWord)
                model.notify(r); refresh()
            }
            Action.ForgetWords -> { io.github.durkimat.phoneguard.whatsapp.LearnedStore.forget(this); model.notify("Forgotten"); refresh() }
            Action.CallKey -> start(Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI).setType(CallLog.Calls.CONTENT_TYPE))
            else -> {} // the call actions belong to the call screen
        }
    }

    private fun placeCall(number: String) {
        try {
            getSystemService(android.telecom.TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), null)
        } catch (e: Exception) {
            Log.log(this, "launcher: could not place call: ${e.javaClass.simpleName}")
            model.notify("Cannot call"); refresh()
        }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return Log.log(this, "launcher: no launch intent for $pkg")
        start(i)
    }

    private fun start(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.log(this, "launcher: could not start ${i.action}: ${e.javaClass.simpleName}") }
    }
}
