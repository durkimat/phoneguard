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
        /** Set while the Music screen is in front and the music plays on another Spotify device: the volume keys then change that device (returns its new level and short name). */
        @Volatile var remoteVolume: ((Boolean) -> Pair<Int, String>?)? = null
        @Volatile var inFront = false
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
        // the hang-up key (and Home) arrive as a home request: from any screen of ours that means the idle screen
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !intent.hasExtra(EXTRA_DIAL) && intent.action == Intent.ACTION_MAIN) { model.goHome(); refresh() }
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
        inFront = true
        try { getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(netWatch) } catch (_: Exception) { }
        remoteVolume = { up -> remoteVolumeStep(up) }
        // coming back from another app shows the home screen; a key press while we are in front must not reset it
        if (cameBack) { model.goHome(); cameBack = false }
        refresh()
        pendingDial?.let { model.startDial(it); pendingDial = null; refresh() }
        if (leftKeyPending) { leftKeyPending = false; perform(model.onKey(Key.SOFT_LEFT, -1)) }
        registerReceiver(changes, IntentFilter().apply {
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED); addAction(android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED); addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }.also { f -> f.addAction(Intent.ACTION_TIME_CHANGED) }, Context.RECEIVER_EXPORTED)
        ui.postDelayed(tick, 20_000)
    }

    override fun onPause() {
        inFront = false
        try { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(netWatch) } catch (_: Exception) { }
        remoteVolume = null
        if (model.screen in netScreens) Thread { io.github.durkimat.phoneguard.LocationLock.giveBack(this) }.start() // leaving the app: the lent location switch goes back
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
    /**
     * Once a second on the Music screen: only the player is looked at (off the main thread) and the screen is redrawn with the
     * home information from the last full refresh. A full refresh queries the call log, messages and contacts, which made the
     * scrolling titles stick every second.
     */
    @Volatile private var lastInfo: HomeInfo? = null
    @Volatile private var musicBusy = false
    /** Wi-Fi and Bluetooth lists: looked at every few seconds while one of those screens is open, then redrawn without a full refresh. */
    private val netScreens = setOf(Screen.WIFI, Screen.WIFINET, Screen.BT, Screen.BTDEV)
    @Volatile private var netBusy = false
    private val netTick = object : Runnable {
        override fun run() {
            if (model.screen !in netScreens) { io.github.durkimat.phoneguard.net.BluetoothControl.stopSearch(this@LauncherActivity); Thread { io.github.durkimat.phoneguard.LocationLock.giveBack(this@LauncherActivity) }.start(); return }
            if (!netBusy) Thread { loadNet() }.start()
            ui.postDelayed(this, 3500)
        }
    }
    private fun startNetTick() { ui.removeCallbacks(netTick); ui.post(netTick) }

    private fun loadNet() {
        netBusy = true
        try {
            val wifi = model.screen == Screen.WIFI || model.screen == Screen.WIFINET
            if (wifi) {
                val c = io.github.durkimat.phoneguard.net.WifiControl
                io.github.durkimat.phoneguard.LocationLock.borrow(this)
                c.scan(this)
                val on = c.enabled(this); val nets = if (on) c.list(this) else emptyList()
                ui.post { model.wifiOn = on; model.wifiNets = nets; model.wifiSel = model.wifiSel?.let { s -> nets.firstOrNull { it.ssid == s.ssid } ?: s }; lastInfo?.let { skin.render(model, it) } }
            } else {
                val c = io.github.durkimat.phoneguard.net.BluetoothControl
                val on = c.enabled(this); val devs = if (on) c.list(this) else emptyList()
                ui.post { model.btOn = on; model.btDevs = devs; model.btSel = model.btSel?.let { s -> devs.firstOrNull { it.address == s.address } ?: s }; lastInfo?.let { skin.render(model, it) } }
            }
        } finally { netBusy = false }
    }

    private fun netNotice(m: String) { ui.post { if (m.isNotEmpty()) { model.notify(m); ui.removeCallbacks(hideNotice); ui.postDelayed(hideNotice, 1_500) }; lastInfo?.let { skin.render(model, it) } } }

    private val musicTick = Runnable {
        if (model.screen != Screen.MUSIC) return@Runnable
        if (!musicBusy) {
            musicBusy = true
            Thread {
                try {
                    val now = nowPlaying()
                    runOnUiThread {
                        if (model.screen == Screen.MUSIC) {
                            model.music = now
                            model.sleepLabel = io.github.durkimat.phoneguard.music.SleepTimer.label()
                            lastInfo?.let { skin.render(model, it) }
                        }
                    }
                } finally { musicBusy = false }
            }.start()
        }
        ui.postDelayed(this.musicTickRef(), 1000)
    }
    private fun musicTickRef(): Runnable = musicTick
    private val sp = io.github.durkimat.phoneguard.music.Spotify
    @Volatile private var spPlayer: io.github.durkimat.phoneguard.music.SpPlayer? = null
    private var spPolledAt = 0L
    @Volatile private var spPolling = false

    /** The phone's own name as Spotify may list it, used to point out "this phone" among the devices. */
    private fun thisPhone(d: io.github.durkimat.phoneguard.music.SpDevice): Boolean {
        val names = listOfNotNull(android.provider.Settings.Global.getString(contentResolver, "device_name"), android.os.Build.MODEL, android.os.Build.DEVICE).map { it.lowercase() }
        return d.name.lowercase() in names || (d.type.equals("Smartphone", true) && names.any { d.name.lowercase().contains(it) })
    }

    /** What the Now playing screen shows: Spotify's view of the account when signed in (it knows where playback is), else the music app's own session. */
    private fun nowPlaying(): io.github.durkimat.phoneguard.music.MusicInfo {
        val local = io.github.durkimat.phoneguard.music.MusicController.info(this)
        if (!sp.signedIn(this)) return local
        val now = System.currentTimeMillis()
        if (!spPolling && now - spPolledAt > 3000) {
            spPolling = true; spPolledAt = now
            Thread { try { spPlayer = sp.player(this) } finally { spPolling = false } }.start()
        }
        val p = spPlayer ?: return local
        val mine = thisPhone(io.github.durkimat.phoneguard.music.SpDevice(p.deviceId, p.deviceName, p.deviceType, true, p.volume))
        return io.github.durkimat.phoneguard.music.MusicInfo(true, "Spotify", p.title, p.artist, p.album, p.playing, p.position(now), p.durationMs, if (mine) local.volumePct else p.volume, p.deviceName, mine, p.shuffle, p.repeat)
    }
    /** The Wi-Fi symbol follows the connection as it comes and goes. */
    private val netWatch = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { runOnUiThread { refresh() } }
        override fun onLost(network: android.net.Network) { runOnUiThread { refresh() } }
    }
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
        model.spConfigured = io.github.durkimat.phoneguard.music.Spotify.configured(this)
        model.spSignedIn = io.github.durkimat.phoneguard.music.Spotify.signedIn(this)
        if (model.screen == Screen.MUSIC) {
            model.spSignedIn = io.github.durkimat.phoneguard.music.Spotify.signedIn(this)
            model.music = nowPlaying()
            model.musicApp = io.github.durkimat.phoneguard.music.MusicController.spotifyInstalled(this)
            ui.removeCallbacks(musicTick); ui.postDelayed(musicTick, 1000)
        }
        if (model.screen == Screen.RUNNING) model.running = io.github.durkimat.phoneguard.IdleReaper.running(this)
        if (model.screen == Screen.BATTERY) model.battery = LauncherData.batteryInfo(this)
        WeatherStore.refreshIfDue(this) { runOnUiThread { refresh() } }
        val all = LauncherData.items(this)
        val pinned = Store.config(this)?.pinned ?: emptyList()
        model.apps = LauncherData.otherApps(all, pinned)
        model.logItems = LauncherData.logsAndSettings(all)
        model.setItems(LauncherData.menu(all, pinned, androidApps = LauncherData.touchAllowed(this)))
        if (model.screen == Screen.HOME || model.screen == Screen.MENU || model.contacts.isEmpty()) model.contacts = LauncherData.contacts(this)
        if (model.screen != Screen.CALLLOG) model.calls = LauncherData.calls(this, model.contacts)
        // not while reading or writing, so a message arriving cannot shift what is on the screen
        if (model.screen != Screen.THREAD && model.screen != Screen.WRITE) model.convos = LauncherData.convos(this, model.contacts)
        // WhatsApp is offered unless the mode switches it off; "linked" says whether an account is attached
        model.sleepLabel = io.github.durkimat.phoneguard.music.SleepTimer.label()
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
        model.wifiShown = info.wifi >= 0
        lastInfo = info
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
            Action.MusicPlayPause -> spOrLocal({ if (model.music.playing) sp.pause(this) else sp.resume(this) }, { io.github.durkimat.phoneguard.music.MusicController.playPause(this) })
            Action.MusicNext -> spOrLocal({ sp.next(this) }, { io.github.durkimat.phoneguard.music.MusicController.next(this) })
            Action.MusicPrevious -> spOrLocal({ sp.previous(this) }, { io.github.durkimat.phoneguard.music.MusicController.previous(this) })
            is Action.MusicVolume -> {
                // on another device the volume is Spotify's for that device; on this phone it is the phone's music volume
                if (sp.signedIn(this) && !model.music.thisPhone) Thread { sp.volume(this, model.music.volumePct + (if (a.up) 10 else -10)); spPolledAt = 0; ui.post { refresh() } }.start()
                else { io.github.durkimat.phoneguard.music.MusicController.volume(this, a.up); refresh() }
            }
            is Action.SoundTest -> io.github.durkimat.phoneguard.SoundTest.play(this, a.route)
            Action.SpShuffle -> Thread { sp.shuffle(this, !model.music.shuffle); spPolledAt = 0; ui.postDelayed({ refresh() }, 500) }.start()
            Action.SpRepeat -> Thread { sp.repeat(this, when (model.music.repeat) { "off" -> "context"; "context" -> "track"; else -> "off" }); spPolledAt = 0; ui.postDelayed({ refresh() }, 500) }.start()
            Action.WifiLoad -> { netNotice(""); startNetTick() }
            Action.WifiToggle -> Thread { val on = !model.wifiOn; io.github.durkimat.phoneguard.net.WifiControl.setEnabled(this, on); Thread.sleep(1200); loadNet() }.start()
            is Action.WifiConnect -> Thread { val ok = io.github.durkimat.phoneguard.net.WifiControl.connect(this, a.id); netNotice(if (ok) "Connecting..." else "Could not connect"); Thread.sleep(2500); loadNet() }.start()
            is Action.WifiForget -> Thread { val ok = io.github.durkimat.phoneguard.net.WifiControl.forget(this, a.id); netNotice(if (ok) "Forgotten" else "Could not forget"); Thread.sleep(500); loadNet() }.start()
            is Action.WifiJoin -> Thread { val ok = io.github.durkimat.phoneguard.net.WifiControl.join(this, a.ssid, a.password, a.caps, a.id); netNotice(if (ok) "Joining..." else "Could not join"); Thread.sleep(3000); loadNet() }.start()
            Action.BtLoad -> { netNotice(""); startNetTick() }
            Action.BtSearch -> Thread { io.github.durkimat.phoneguard.net.BluetoothControl.search(this); netNotice("Searching..."); loadNet() }.start()
            Action.BtToggle -> Thread { val m = io.github.durkimat.phoneguard.net.BluetoothControl.setEnabled(this, !model.btOn); if (m.isNotEmpty()) netNotice(m); Thread.sleep(1500); loadNet() }.start()
            is Action.BtPair -> Thread { io.github.durkimat.phoneguard.keys.F2Service.cover(true, "Pairing..."); val ok = io.github.durkimat.phoneguard.net.BluetoothControl.pair(this, a.address); if (!ok) io.github.durkimat.phoneguard.keys.F2Service.cover(false); netNotice(if (ok) "Pairing..." else "Could not pair"); Thread.sleep(3000); loadNet() }.start()
            is Action.BtConnect -> Thread { val ok = io.github.durkimat.phoneguard.net.BluetoothControl.setConnected(this, a.address, a.connect); netNotice(if (ok) (if (a.connect) "Connected" else "Disconnected") else "Android refused"); loadNet() }.start()
            is Action.BtForget -> Thread { val ok = io.github.durkimat.phoneguard.net.BluetoothControl.forget(this, a.address); netNotice(if (ok) "Forgotten" else "Android refused"); Thread.sleep(500); loadNet() }.start()
            is Action.KillApp -> Thread { io.github.durkimat.phoneguard.IdleReaper.stop(this, a.pkg); ui.postDelayed({ refresh() }, 300) }.start()
            Action.SleepStep -> { io.github.durkimat.phoneguard.music.SleepTimer.step(this); refresh() }
            Action.SpSignIn -> start(sp.signInIntent(this))
            Action.SpSignOut -> { sp.signOut(this); spPlayer = null; model.spSignedIn = false; model.music = io.github.durkimat.phoneguard.music.MusicController.info(this); refresh() }
            is Action.SpLoad -> Thread {
                if (a.kind == "playlists") { val l = sp.playlists(this); ui.post { model.spPlaylists = l ?: emptyList(); model.spStatus = if (l == null) "Could not load" else "No playlists"; refresh() } }
                else {
                    val l = sp.devices(this)
                    ui.post { model.spIsThisPhone = { d -> thisPhone(d) }; model.spDevices = (l ?: emptyList()).sortedByDescending { thisPhone(it) }; model.spStatus = if (l == null) "Could not load" else "No devices: open Spotify on this phone"; refresh() }
                }
            }.start()
            is Action.SpSearch -> Thread { val l = sp.search(this, a.query); ui.post { model.spResults = l ?: emptyList(); model.spStatus = if (l == null) "Could not search" else "Nothing found"; refresh() } }.start()
            is Action.SpPlay -> Thread { playOnTarget(a.uri); spPolledAt = 0; ui.postDelayed({ refresh() }, 800) }.start()
            is Action.SpTransfer -> Thread { sp.transfer(this, a.id); getSharedPreferences("music", MODE_PRIVATE).edit().putString("target", a.id).apply(); spPolledAt = 0; ui.postDelayed({ refresh() }, 800) }.start()

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
            Action.CallKey -> { model.startDial(""); refresh() } // the green key anywhere in our screens: our dial pad, never the stock dialler
            else -> {} // the call actions belong to the call screen
        }
    }

    private fun remoteVolumeStep(up: Boolean): Pair<Int, String>? {
        val m = model.music
        if (model.screen != Screen.MUSIC || !sp.signedIn(this) || !m.active || m.thisPhone) return null
        val pct = (m.volumePct + (if (up) 10 else -10)).coerceIn(0, 100)
        model.music = m.copy(volumePct = pct)
        Thread { sp.volume(this, pct); spPolledAt = 0 }.start()
        return pct to m.device.uppercase().take(8)
    }

    private fun spOrLocal(api: () -> Unit, local: () -> Unit) {
        if (sp.signedIn(this) && spPlayer != null) Thread { api(); spPolledAt = 0; ui.postDelayed({ refresh() }, 600) }.start()
        else { local(); ui.postDelayed({ refresh() }, 500) }
    }

    /** Start a playlist, album or song on the chosen device: the one picked in "Play on" if it is still there, else this phone, else wherever it is playing. */
    private fun playOnTarget(uri: String) {
        val pickedId = getSharedPreferences("music", MODE_PRIVATE).getString("target", null)
        var list = sp.devices(this) ?: emptyList()
        var target = list.firstOrNull { it.id == pickedId } ?: list.firstOrNull { thisPhone(it) }
        if (target == null) {
            // This phone is not on the list until Spotify is running here, and Spotify cannot be started invisibly (it refuses
            // other apps' service connections). Open it, bring our screens back after a second, then wait for the phone to appear.
            val covered = io.github.durkimat.phoneguard.keys.F2Service.cover(true)
            if (covered) Thread.sleep(250) else ui.post { model.notify("Starting Spotify..."); refresh() }
            launch(io.github.durkimat.phoneguard.music.MusicController.SPOTIFY_PKG)
            Thread.sleep(1200)
            ui.post { start(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            var waited = 0
            while (waited < 9000) {
                Thread.sleep(1000); waited += 1000
                list = sp.devices(this) ?: emptyList()
                target = list.firstOrNull { thisPhone(it) }
                if (target != null) break
            }
            io.github.durkimat.phoneguard.keys.F2Service.cover(false)
            Log.log(this, "spotify cold start: " + (if (target != null) "phone listed after ${waited / 1000}s" else "phone did not appear"))
            if (target == null) target = list.firstOrNull { it.active }
        }
        Log.log(this, "spotify play: devices=[" + list.joinToString { it.name + "/" + it.type + (if (it.active) "*" else "") } + "] target=" + (target?.name ?: "none"))
        sp.play(this, uri, target?.id)
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
