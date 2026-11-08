package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.Store
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
import android.view.View
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

    private val skin = NokiaSkin().also { s -> s.onTap = { x, y -> perform(model.tap(x, y)) } }
    private val model = LauncherModel()
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 20_000) } }

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // if a swipe from the top does reveal the system bar, let it wear the theme's colours instead of a dark strip
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        applyBarColour()
        model.theme = prefs().getString("theme", Themes.DEFAULT) ?: Themes.DEFAULT
        model.font = prefs().getString("font", Fonts.DEFAULT) ?: Fonts.DEFAULT
        WhatsApp.onChange(waChanged)
        io.github.durkimat.phoneguard.keys.Torch.onChange(torchChanged)
        io.github.durkimat.phoneguard.notes.Notes.onChange(notesChanged)
        model.predictiveOn = prefs().getBoolean("predictive", true)
        model.speakerShown = prefs().getBoolean("speaker", true)
        model.waNameStyle = prefs().getString("wa_names", NameStyle.DEFAULT) ?: NameStyle.DEFAULT
        model.snakeBest = prefs().getInt("snake_best", 0)
        model.shooterBest = prefs().getInt("shooter_best", 0)
        model.pairsBest = prefs().getInt("pairs_best", 0)
        Thread { LockWallpaper.apply(this, model.theme) }.start()
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
    private var lastF2 = 0L
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

    private fun applyBarColour() {
        val t = Themes.byId(prefs().getString("theme", Themes.DEFAULT))
        window.statusBarColor = t.lcd; window.navigationBarColor = t.lcd
        window.decorView.systemUiVisibility = if (android.graphics.Color.luminance(t.lcd) > 0.5f) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
    }

    private fun prefs() = createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private val waChanged: () -> Unit = { runOnUiThread { refresh() } }

    private val torchChanged: () -> Unit = { refresh() }
    private val notesChanged: () -> Unit = { runOnUiThread { refresh() } }

    override fun onDestroy() { WhatsApp.offChange(waChanged); io.github.durkimat.phoneguard.keys.Torch.offChange(torchChanged); io.github.durkimat.phoneguard.notes.Notes.offChange(notesChanged); super.onDestroy() }

    private val gameStep = object : Runnable {
        override fun run() {
            gameScheduled = false
            if (model.gameTick() == Action.Redraw) { saveBest(); refresh() }
        }
    }
    private var gameScheduled = false

    /** While snake is running, step it on a timer; the timer stops by itself when the game is paused, over or left. */
    private fun scheduleGame() {
        val d = model.gameDelayMs()
        if (d != null && !gameScheduled) { gameScheduled = true; ui.postDelayed(gameStep, d) }
    }

    private fun saveBest() {
        val p = prefs()
        if (model.snakeBest > p.getInt("snake_best", 0)) p.edit().putInt("snake_best", model.snakeBest).apply()
        if (model.shooterBest > p.getInt("shooter_best", 0)) p.edit().putInt("shooter_best", model.shooterBest).apply()
        if (model.pairsBest > 0 && (p.getInt("pairs_best", 0) == 0 || model.pairsBest < p.getInt("pairs_best", 0))) p.edit().putInt("pairs_best", model.pairsBest).apply()
    }

    private val hideNotice = Runnable { model.clearNotice(); refresh() }

    private fun refresh() {
        // a notice ("Message sent") goes by itself after a moment
        ui.removeCallbacks(hideNotice)
        scheduleGame()
        if (model.notice != null) ui.postDelayed(hideNotice, 1_500)
        model.weather = WeatherStore.current(this)
        model.androidSettings = LauncherData.touchAllowed(this)
        model.notes = io.github.durkimat.phoneguard.notes.Notes.all()
        if (model.screen == Screen.BATTERY) model.battery = LauncherData.batteryInfo(this)
        WeatherStore.refreshIfDue(this) { runOnUiThread { refresh() } }
        val all = LauncherData.items(this)
        val pinned = Store.config(this)?.pinned ?: emptyList()
        model.apps = LauncherData.otherApps(all, pinned)
        model.setItems(LauncherData.menu(all, pinned, androidApps = LauncherData.touchAllowed(this)))
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
        val info = LauncherData.info(this)
        model.badges = info.badges
        skin.render(model, info)
    }

    private fun key(code: Int): Pair<Key, Int>? = KeyMap.of(code)

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        // the side button of the J36 sends F2 (from the pmic key input, next to power): two quick presses open the camera, unless a call is on or the mode has no camera
        if (ev.keyCode == KeyEvent.KEYCODE_F2) {
            // a double press opens the camera: one press alone is ignored, so a knock on the button does nothing
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastF2 in 40..700 && model.screen != Screen.CALL && LauncherData.cameraAllowed(this)) { lastF2 = 0; perform(Action.OpenCamera) } else lastF2 = now
            }
            return true
        }
        val k = key(ev.keyCode) ?: return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(model.onKey(k.first, k.second))
        else if (ev.action == KeyEvent.ACTION_UP) perform(model.onRelease(k.first, k.second))
        return true // consume the key up as well so Back never leaves the launcher
    }

    private fun perform(a: Action) {
        when (a) {
            Action.None -> {}
            Action.Redraw -> { saveBest(); refresh() }
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
            is Action.SaveTheme -> { prefs().edit().putString("theme", a.id).apply(); applyBarColour(); Thread { LockWallpaper.apply(this, a.id) }.start(); refresh() }
            is Action.OpenNote -> {
                val n = io.github.durkimat.phoneguard.notes.Notes.all().firstOrNull { it.key == a.key }
                try { n?.intent?.send() } catch (e: Exception) { Log.log(this, "open note: ${e.javaClass.simpleName}") }
                io.github.durkimat.phoneguard.notes.NoteService.instance?.clearKey(a.key)
                model.goHome(); refresh()
            }
            is Action.ClearNote -> { io.github.durkimat.phoneguard.notes.NoteService.instance?.clearKey(a.key); refresh() }
            Action.ClearNotes -> { io.github.durkimat.phoneguard.notes.NoteService.instance?.clearEverything(); refresh() }
            Action.OpenCamera -> start(Intent(this, io.github.durkimat.phoneguard.camera.CameraActivity::class.java))
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
