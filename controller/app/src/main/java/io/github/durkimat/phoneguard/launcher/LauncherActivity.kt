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

    private fun newSkin(kind: String): PaintedSkin = (if (kind == "mac") MacSkin() else NokiaSkin()).also { s -> s.onTap = { x, y -> perform(model.tap(x, y)) } }
    private var skinKind = "nokia"
    private var skin: PaintedSkin = newSkin(skinKind)
    private var contentSet = false
    /** The look settings a parent is holding from HA; the phone's own choice for these is kept but not shown. */
    private var lockedLook: Set<String> = emptySet()
    private val model = LauncherModel()
    private val ui = Handler(Looper.getMainLooper())
    private val calendar by lazy { io.github.durkimat.phoneguard.calendar.AndroidCalendar(this) }
    private val tick = object : Runnable { override fun run() { invalidate(D.INFO or (if (model.screen == Screen.RUNNING || model.screen == Screen.BATTERY) D.SCREEN else 0)); ui.postDelayed(this, 60_000) } }

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { invalidate(D.INFO or (if (i.action == Intent.ACTION_TIME_TICK || i.action?.startsWith("android.intent.action.PACKAGE") == true) D.APPS else 0)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // if a swipe from the top does reveal the system bar, let it wear the theme's colours instead of a dark strip
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        applyLook()
        WhatsApp.onChange(waChanged)
        io.github.durkimat.phoneguard.music.Radio.onChange(radioChanged)
        try {
            contentResolver.registerContentObserver(android.provider.Telephony.Sms.CONTENT_URI, true, sms)
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLog)
            contentResolver.registerContentObserver(android.provider.ContactsContract.Contacts.CONTENT_URI, true, contactsObs)
        } catch (e: Exception) { Log.log(this, "launcher observers: ${e.javaClass.simpleName}") }
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
        setContentView(skin.create(this)); contentSet = true
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
        dirty.getAndUpdate { it or D.INFO or D.APPS } // the mode, packages and connections may have changed while another app was in front
        refresh()
        pendingDial?.let { model.startDial(it); pendingDial = null; refresh() }
        // debug builds only: `am start -S -n ... --es pg_mail_demo 1` sets up the made-up mailbox so the mail screens can be tried without an account
        if (io.github.durkimat.phoneguard.BuildConfig.DEBUG && intent?.getStringExtra("pg_mail_demo") != null) {
            intent.removeExtra("pg_mail_demo")
            Thread { io.github.durkimat.phoneguard.mail.Mail.setup(this, io.github.durkimat.phoneguard.mail.MailAccount("demo@demo.local", imapHost = "demo", smtpHost = "demo"), ""); ui.post { mailPush(); refresh() } }.start()
        }
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

    /** Works out the look to show (the phone's own choices, a parent's laid over them) and, if that needs another skin, swaps it in. */
    private fun applyLook() {
        val parent = io.github.durkimat.phoneguard.Store.config(this)?.look
        val l = Look.resolve(Look.own(this), parent)
        lockedLook = Look.locked(parent)
        model.theme = l.theme; model.font = l.font; model.menuStyle = l.menuStyle; model.accent = l.accent; model.style = l.style
        if (l.skin != skinKind) {
            skinKind = l.skin; skin = newSkin(skinKind)
            if (contentSet) { setContentView(skin.create(this)); lastInfo?.let { skin.render(model, it) } }
        }
        applyBarColour()
    }

    private fun applyBarColour() {
        val t = Themes.byId(model.theme)
        window.statusBarColor = t.lcd; window.navigationBarColor = t.lcd
        window.decorView.systemUiVisibility = if (android.graphics.Color.luminance(t.lcd) > 0.5f) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
    }

    /** The choice was saved, but a parent is holding that setting, so say why the screen did not change. */
    private fun heldNote(key: String) { if (key in lockedLook) model.notify("Set by a parent") }

    private fun prefs() = createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private val waChanged: () -> Unit = { invalidate(D.WA or D.INFO) }

    private val torchChanged: () -> Unit = { ui.post { redraw() } }
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
            val prov = model.provider
            Thread {
                try {
                    val now = nowPlaying(prov)
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
    private val ctxNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** After play or pause the state we expect, held for a few seconds: Spotify still reports the old state for a moment and the symbol would flip back and forth. */
    private var playHold: Pair<Boolean, Long>? = null

    private fun nowPlaying(p: Provider): io.github.durkimat.phoneguard.music.MusicInfo {
        if (p == Provider.RADIO) return radioInfo() ?: io.github.durkimat.phoneguard.music.MusicInfo.NONE.copy(volumePct = io.github.durkimat.phoneguard.music.MusicController.volumePct(this), source = "radio")
        var m = spotifyOrLocal()
        seekHold?.let { (to, at) ->
            val now = System.currentTimeMillis()
            if (now - at < 3000) m = m.copy(positionMs = (to + (if (m.playing) now - at else 0L)).coerceAtMost(if (m.durationMs > 0) m.durationMs else Long.MAX_VALUE)) else seekHold = null
        }
        val h = playHold ?: return m
        if (System.currentTimeMillis() > h.second || m.playing == h.first) { if (m.playing == h.first) playHold = null; return m }
        return m.copy(playing = h.first)
    }

    /** The radio's state as Now playing shows it, or null when no station has been chosen. */
    private fun radioInfo(): io.github.durkimat.phoneguard.music.MusicInfo? {
        if (!io.github.durkimat.phoneguard.music.Radio.active) return null
        val st = io.github.durkimat.phoneguard.music.Radio.state
        val connecting = st.buffering && !st.playing
        return io.github.durkimat.phoneguard.music.MusicInfo(true, "Radio", st.station, if (st.error.isNotEmpty()) st.error else if (connecting) "Connecting..." else st.nowPlaying, "", st.playing || connecting,
            if (st.playing) System.currentTimeMillis() - st.startedAt else 0L, 0L, io.github.durkimat.phoneguard.music.MusicController.volumePct(this), source = "radio", buffering = connecting)
    }

    /** What is playing, and each provider's state for the Music menu (0 idle, 1 paused, 2 playing), from what is already known (nothing is polled here). */
    private fun providerStates(): Pair<Provider?, Map<Provider, Int>> {
        val radio = if (io.github.durkimat.phoneguard.music.Radio.playing) 2 else if (io.github.durkimat.phoneguard.music.Radio.active) 1 else 0
        val sp = try {
            val local = io.github.durkimat.phoneguard.music.MusicController.info(this)
            val p = spPlayer
            if (local.playing || p?.playing == true) 2 else if (local.active || p != null) 1 else 0
        } catch (_: Exception) { 0 }
        return (if (radio == 2) Provider.RADIO else if (sp == 2) Provider.SPOTIFY else null) to mapOf(Provider.RADIO to radio, Provider.SPOTIFY to sp)
    }

    private fun spotifyOrLocal(): io.github.durkimat.phoneguard.music.MusicInfo {
        val local = io.github.durkimat.phoneguard.music.MusicController.info(this)
        if (!sp.signedIn(this)) return local
        val now = System.currentTimeMillis()
        if (!spPolling && now - spPolledAt > 3000) {
            spPolling = true; spPolledAt = now
            Thread { try { spPlayer = sp.player(this) } finally { spPolling = false }; if (model.screen == Screen.MUSICMENU) ui.post { invalidate(D.INFO) } }.start()
        }
        val p = spPlayer ?: return local
        val mine = thisPhone(io.github.durkimat.phoneguard.music.SpDevice(p.deviceId, p.deviceName, p.deviceType, true, p.volume))
        val kind = p.context.split(":").getOrNull(1).orEmpty()
        val name = ctxNames[p.context]
        if (p.context.isNotEmpty() && name == null && ctxNames.putIfAbsent(p.context, "") == null) Thread { ctxNames[p.context] = sp.contextName(this, p.context) }.start()
        val from = if (!name.isNullOrEmpty() && kind in setOf("playlist", "artist")) kind.replaceFirstChar { it.uppercase() } + ": " + name else ""
        return io.github.durkimat.phoneguard.music.MusicInfo(true, "Spotify", p.title, p.artist, p.album, p.playing, p.position(now), p.durationMs, if (mine) local.volumePct else p.volume, p.deviceName, mine, p.shuffle, p.repeat, from)
    }
    /** The Wi-Fi symbol follows the connection as it comes and goes. */
    private val netWatch = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { invalidate(D.INFO) }
        override fun onLost(network: android.net.Network) { invalidate(D.INFO) }
    }
    private val notesChanged: () -> Unit = { invalidate(D.INFO) }

    override fun onDestroy() { try { contentResolver.unregisterContentObserver(sms); contentResolver.unregisterContentObserver(callLog); contentResolver.unregisterContentObserver(contactsObs) } catch (_: Exception) { }; loader.shutdown(); io.github.durkimat.phoneguard.music.Radio.offChange(radioChanged); WhatsApp.offChange(waChanged); io.github.durkimat.phoneguard.keys.Torch.offChange(torchChanged); io.github.durkimat.phoneguard.notes.Notes.offChange(notesChanged); super.onDestroy() }

    private val gameStep = object : Runnable {
        override fun run() {
            gameScheduled = false
            if (model.gameTick() == Action.Redraw) { saveBest(); redraw() }
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

    private val hideNotice = Runnable { model.clearNotice(); redraw() }

    /*
     * Data for the screens is read once and kept: reading contacts, messages, the call log, WhatsApp and the app list took a quarter of a
     * second on the main thread, and every key press and every timer did it again. Now each group is read on a background thread, and
     * only when it has changed (a content observer, WhatsApp, a package or the clock says so). Redrawing never reads anything.
     */
    private object D { const val INFO = 1; const val CONTACTS = 2; const val CALLS = 4; const val CONVOS = 8; const val WA = 16; const val APPS = 32; const val SCREEN = 64; const val ALL = 127 }
    private val dirty = java.util.concurrent.atomic.AtomicInteger(D.ALL)
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "launcher-data").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var loading = false
    private var loadedScreen: Screen? = null
    private val reloadSoon = Runnable { load() }

    /** Something changed: read these groups again soon (a burst of changes becomes one read). Safe from any thread. */
    private fun invalidate(bits: Int) {
        dirty.getAndUpdate { it or bits }
        if (inFront) { ui.removeCallbacks(reloadSoon); ui.postDelayed(reloadSoon, 150) }
    }

    /** Draws the screen from what is held. Reads nothing. */
    private fun redraw() {
        ui.removeCallbacks(hideNotice)
        scheduleGame()
        if (model.notice != null) ui.postDelayed(hideNotice, 1_500)
        lastInfo?.let { skin.render(model, it) }
    }

    /** Draws now, and reads again whatever has changed (in the background). */
    private fun refresh() {
        redraw()
        if (model.screen != loadedScreen) dirty.getAndUpdate { it or D.SCREEN }
        if (model.screen == Screen.MUSIC) { ui.removeCallbacks(musicTick); ui.postDelayed(musicTick, 1000) }
        load()
    }

    private class Loaded {
        var info: HomeInfo? = null; var weather: WeatherData? = null; var androidSettings = false; var notes: List<io.github.durkimat.phoneguard.notes.NoteItem> = emptyList()
        var spConfigured = false; var spSignedIn = false; var sleepLabel: String? = null; var spectrum = false
        var waLinked = false; var waEnabled = false; var waCode: String? = null
        var radioNames: List<String> = emptyList(); var radioMode = "any"
        var playingProvider: Provider? = null; var provStatus: Map<Provider, Int> = emptyMap()
        var contacts: List<Contact>? = null; var calls: List<CallEntry>? = null; var convos: List<Convo>? = null
        var waStatus: String? = null; var waUnread = 0; var waConvos: List<Convo>? = null
        var all: List<MenuItem>? = null; var pinned: List<String> = emptyList()
        var music: io.github.durkimat.phoneguard.music.MusicInfo? = null; var musicApp = false
        var running: List<RunningApp>? = null; var battery: BatteryInfo? = null
    }

    private fun load() {
        if (loading) return
        var bits = dirty.getAndSet(0)
        if (bits == 0) return
        if (bits and D.CONTACTS != 0) bits = bits or D.CALLS or D.CONVOS or D.WA // names come from the contacts
        loading = true
        val screen = model.screen
        val provider = model.provider
        loadedScreen = screen
        val known = model.contacts
        val bitsNow = bits
        loader.execute {
            val r = Loaded()
            try {
                if (bitsNow and D.INFO != 0) {
                    r.weather = WeatherStore.current(this)
                    r.androidSettings = LauncherData.touchAllowed(this)
                    r.notes = io.github.durkimat.phoneguard.notes.Notes.all()
                    r.spConfigured = io.github.durkimat.phoneguard.music.Spotify.configured(this)
                    r.spSignedIn = io.github.durkimat.phoneguard.music.Spotify.signedIn(this)
                    r.sleepLabel = io.github.durkimat.phoneguard.music.SleepTimer.label()
                    r.spectrum = prefs().getBoolean("spectrum", false)
                    r.waLinked = WhatsApp.linked(this)
                    r.waEnabled = WhatsApp.policy(this) != "off" && !LauncherData.useRealWhatsApp(this)
                    r.waCode = WhatsApp.pairCode
                    r.radioNames = io.github.durkimat.phoneguard.music.Radio.stations(this).map { it.name }
                    providerStates().let { (playing, status) -> r.playingProvider = playing; r.provStatus = status }
                    r.radioMode = io.github.durkimat.phoneguard.core.Engine.effective(Store.config(this), Store.boost(this), io.github.durkimat.phoneguard.Clock.now()).radio
                    r.info = LauncherData.info(this)
                    WeatherStore.refreshIfDue(this) { ui.post { invalidate(D.INFO) } }
                }
                if (bitsNow and D.APPS != 0) { r.all = LauncherData.items(this); r.pinned = Store.config(this)?.pinned ?: emptyList() }
                if (bitsNow and D.CONTACTS != 0) r.contacts = LauncherData.contacts(this)
                val people = r.contacts ?: known
                if (bitsNow and D.CALLS != 0) r.calls = LauncherData.calls(this, people)
                if (bitsNow and D.CONVOS != 0) r.convos = LauncherData.convos(this, people)
                if (bitsNow and D.WA != 0 && WhatsApp.linked(this)) {
                    r.waStatus = WhatsApp.status; r.waUnread = WhatsApp.store(this).unreadTotal(); r.waConvos = WhatsApp.convos(this, people)
                }
                if (bitsNow and D.SCREEN != 0) when (screen) {
                    Screen.MUSIC -> { r.music = nowPlaying(provider); r.musicApp = io.github.durkimat.phoneguard.music.MusicController.spotifyInstalled(this); r.spSignedIn = io.github.durkimat.phoneguard.music.Spotify.signedIn(this) }
                    Screen.MUSICMENU -> spotifyOrLocal() // looks again at Spotify, so the symbol next to it is right
                    Screen.RUNNING -> r.running = io.github.durkimat.phoneguard.IdleReaper.running(this)
                    Screen.BATTERY -> r.battery = LauncherData.batteryInfo(this)
                    else -> {}
                }
            } catch (e: Exception) { Log.log(this, "launcher data: ${e.javaClass.simpleName}") }
            ui.post { apply(r, bitsNow, screen); loading = false; if (dirty.get() != 0) load() }
        }
    }

    /** Takes what the background read found into the model (main thread) and draws. */
    private fun apply(r: Loaded, bits: Int, screen: Screen) {
        val reading = model.screen == Screen.THREAD || model.screen == Screen.WRITE // not while reading or writing, so a message arriving cannot shift what is on the screen
        r.info?.let {
            model.weather = r.weather; model.androidSettings = r.androidSettings; model.notes = r.notes
            model.spConfigured = r.spConfigured; if (screen != Screen.MUSIC) model.spSignedIn = r.spSignedIn
            model.sleepLabel = r.sleepLabel ?: ""; model.spectrum = r.spectrum
            model.waLinked = r.waLinked; model.waEnabled = r.waEnabled
            model.radioNames = r.radioNames; model.radioMode = r.radioMode
            model.playingProvider = r.playingProvider; model.provStatus = r.provStatus
            model.waCode = if (model.screen == Screen.WAPAIR && r.waLinked) null else r.waCode
            model.badges = it.badges; model.wifiShown = it.wifi >= 0; model.btShown = it.bt >= 0; model.dataShown = it.data >= 0
            model.connWifi = it.wifi; model.connBt = it.bt; model.connData = it.data
            lastInfo = it
        }
        r.all?.let { all ->
            model.apps = LauncherData.otherApps(all, r.pinned)
            model.logItems = LauncherData.logsAndSettings(all)
            model.setItems(LauncherData.menu(all, r.pinned, androidApps = model.androidSettings))
        }
        r.contacts?.let { model.contacts = it }
        r.calls?.let { if (model.screen != Screen.CALLLOG) model.calls = it else dirty.getAndUpdate { d -> d or D.CALLS } }
        r.convos?.let { if (!reading) model.convos = it else dirty.getAndUpdate { d -> d or D.CONVOS } }
        if (bits and D.WA != 0) {
            if (r.waLinked || model.waLinked) { r.waStatus?.let { model.waStatus = it }; model.waUnread = r.waUnread }
            r.waConvos?.let { if (!reading) model.waConvos = it else dirty.getAndUpdate { d -> d or D.WA } }
        }
        if (model.screen == screen) {
            r.music?.let { model.music = it; model.musicApp = r.musicApp; model.spSignedIn = r.spSignedIn }
            r.running?.let { model.running = it }
            r.battery?.let { model.battery = it }
        }
        model.radioIndex = io.github.durkimat.phoneguard.music.Radio.state.index
        redraw()
    }

    private val radioChanged: () -> Unit = { invalidate(D.SCREEN or D.INFO) }
    private val sms = object : android.database.ContentObserver(ui) { override fun onChange(selfChange: Boolean) { invalidate(D.CONVOS or D.INFO) } }
    private val callLog = object : android.database.ContentObserver(ui) { override fun onChange(selfChange: Boolean) { invalidate(D.CALLS or D.INFO) } }
    private val contactsObs = object : android.database.ContentObserver(ui) { override fun onChange(selfChange: Boolean) { invalidate(D.CONTACTS or D.INFO) } }

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
        // Now playing: a tap on left or right (or 4 and 6) skips the track when the key comes up; holding it winds back or forward instead
        if (model.screen == Screen.MUSIC && model.music.active && model.provider != Provider.RADIO && (k.first == Key.LEFT || k.first == Key.RIGHT || (k.first == Key.DIGIT && (k.second == 4 || k.second == 6)))) {
            val dir = if (k.first == Key.RIGHT || k.second == 6) 1 else -1
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) { seekDir = dir; seekFired = false; seekSteps = 0; ui.removeCallbacks(seekStep); ui.postDelayed(seekStep, 500) }
            else if (ev.action == KeyEvent.ACTION_UP) { ui.removeCallbacks(seekStep); if (!seekFired) perform(if (dir > 0) Action.MusicNext else Action.MusicPrevious); seekFired = false }
            return true
        }
        // radio Now playing: a short press of 1 to 9 plays that preset; holding it stores the playing station there (where the mode allows any station)
        if (model.screen == Screen.MUSIC && model.music.active && model.provider == Provider.RADIO && model.radioMode == "any" && k.first == Key.DIGIT && k.second in 1..9) {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) { presetDigit = k.second; presetStored = false; presetDown = true; ui.removeCallbacks(storePreset); ui.postDelayed(storePreset, 400) }
            else if (ev.action == KeyEvent.ACTION_UP && presetDown) { presetDown = false; ui.removeCallbacks(storePreset); if (!presetStored) perform(model.onKey(k.first, k.second)); presetStored = false }
            return true
        }
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
            val before = model.screen
            val was = model.provider
            val a = model.onKey(k.first, k.second)
            if (model.provider != was) pauseSource(was) // a different music source was chosen: the old one stops, so only one thing is ever playing
            // moving the highlight up or down in a list changes nothing that needs reading again (contacts, messages, apps, music...): redraw at once from what is held
            val info = lastInfo
            if (a == Action.Redraw && (k.first == Key.UP || k.first == Key.DOWN || (model.layout == Layout.GRID && (k.first == Key.LEFT || k.first == Key.RIGHT))) && model.screen == before && before in moveOnly && info != null) skin.render(model, info)
            else perform(a)
        }
        else if (ev.action == KeyEvent.ACTION_UP) perform(model.onRelease(k.first, k.second))
        return true // consume the key up as well so Back never leaves the launcher
    }

    /** Lists where a press of Up or Down only moves the highlight, so no data needs reloading. */
    private val moveOnly = setOf(Screen.MENU, Screen.SETTINGS, Screen.CONTACTS, Screen.CALLLOG, Screen.INBOX, Screen.APPS, Screen.GAMES, Screen.WHATSAPP, Screen.MUSICMENU, Screen.PLAYLISTS, Screen.DEVICES, Screen.SEARCHRES, Screen.WIFI, Screen.BT, Screen.THEME, Screen.FONT, Screen.LOGSET, Screen.RUNNING, Screen.NOTES, Screen.RADIO, Screen.RADIOMENU, Screen.SPOTIFYMENU, Screen.RADIOBROWSE, Screen.RADIORES, Screen.CONNECTIONS)

    private var presetDigit = 0
    private var presetStored = false
    private var presetDown = false
    private val storePreset = Runnable { presetStored = true; perform(Action.RadioStore(presetDigit)) }

    private var seekDir = 0
    private var seekFired = false
    private var seekSteps = 0
    /** While left or right is held on Now playing: every second the song moves 10 s (30 s after five steps) until the key is let go. */
    private val seekStep: Runnable = object : Runnable {
        override fun run() {
            if (model.screen != Screen.MUSIC || !model.music.active) return
            seekFired = true; seekSteps++
            val m = model.music
            val step = if (seekSteps > 5) 30_000L else 10_000L
            val limit = if (m.durationMs > 0) m.durationMs - 1000 else Long.MAX_VALUE
            val target = (m.positionMs + seekDir * step).coerceIn(0L, limit.coerceAtLeast(0))
            seekHold = target to System.currentTimeMillis()
            model.music = m.copy(positionMs = target); lastInfo?.let { skin.render(model, it) }
            spOrLocal({ sp.seek(this@LauncherActivity, target) }, { io.github.durkimat.phoneguard.music.MusicController.seekTo(this@LauncherActivity, target) })
            ui.postDelayed(this, 1000)
        }
    }
    /** Where the last wind went and when: for a few seconds the position is taken from here, because Spotify still reports the old place for a moment. */
    private var seekHold: Pair<Long, Long>? = null

    private fun perform(a: Action) {
        when (a) {
            Action.None -> {}
            Action.Redraw -> { saveBest(); refresh() }
            is Action.PairWa -> Thread {
                val r = WhatsApp.pair(this, a.number)
                ui.post { if (r.startsWith("error")) model.notify("Could not start: " + r.removePrefix("error: ").take(40)); invalidate(D.WA or D.INFO); refresh() }
            }.start()
            is Action.SaveNameStyle -> { prefs().edit().putString("wa_names", a.id).apply(); refresh() }
            Action.ResyncWa -> Thread {
                val r = WhatsApp.resync(this)
                ui.post { model.notify(if (r.startsWith("error")) "Re-sync failed: " + r.removePrefix("error: ").take(28) else "Re-synced"); invalidate(D.WA or D.INFO); refresh() }
            }.start()
            Action.UnlinkWa -> Thread {
                val r = WhatsApp.logout(this)
                ui.post { model.notify(if (r.startsWith("error")) "Could not unlink: " + r.removePrefix("error: ").take(30) else "Unlinked"); invalidate(D.WA or D.INFO); refresh() }
            }.start()
            Action.WaOpen -> { WhatsApp.touch(this); invalidate(D.WA or D.INFO); refresh() }
            is Action.WaRead -> { WhatsApp.readChat(this, a.chat); WhatsApp.store(this).markRead(a.chat); invalidate(D.WA or D.INFO); refresh() }
            is Action.SendWa -> Thread {
                val r = WhatsApp.sendNow(this, a.chat, a.body)
                if (r == "sent") io.github.durkimat.phoneguard.whatsapp.LearnedStore.learn(this, a.body, ::knownWord)
                ui.post { if (r != "sent") model.notify("Not sent: $r"); invalidate(D.WA or D.INFO); refresh() }
            }.start()
            is Action.SaveSpeaker -> { prefs().edit().putBoolean("speaker", a.shown).apply(); refresh() }
            is Action.SavePredictive -> { prefs().edit().putBoolean("predictive", a.on).apply(); refresh() }
            is Action.SaveOption -> {
                prefs().edit().also { if (a.id.isEmpty()) it.remove("opt_" + a.key) else it.putString("opt_" + a.key, a.id) }.apply()
                applyLook(); heldNote(a.key); refresh()
            }
            is Action.SavePreset -> {
                Presets.byId(a.id)?.let { pr ->
                    prefs().edit().putString("theme", pr.theme).putString("menu_style", pr.menuStyle).also { e -> Choices.keys.forEach { k -> pr.style[k]?.let { e.putString("opt_$k", it) } ?: e.remove("opt_$k") } }.apply()
                    applyLook(); if (lockedLook.isNotEmpty()) model.notify("Set by a parent")
                    val shown = model.theme; Thread { LockWallpaper.apply(this, shown) }.start()
                }
                refresh()
            }
            is Action.SaveFont -> { prefs().edit().putString("font", a.id).apply(); applyLook(); heldNote("font"); refresh() }
            is Action.SaveMenuStyle -> { prefs().edit().putString("menu_style", a.id).apply(); applyLook(); heldNote("menu_style"); refresh() }
            is Action.SaveTheme -> { prefs().edit().putString("theme", a.id).apply(); applyLook(); heldNote("theme"); val shown = model.theme; Thread { LockWallpaper.apply(this, shown) }.start(); refresh() }
            is Action.OpenNote -> {
                val n = io.github.durkimat.phoneguard.notes.Notes.all().firstOrNull { it.key == a.key }
                try { n?.intent?.send() } catch (e: Exception) { Log.log(this, "open note: ${e.javaClass.simpleName}") }
                io.github.durkimat.phoneguard.notes.NoteService.instance?.clearKey(a.key)
                model.goHome(); refresh()
            }
            is Action.ClearNote -> { io.github.durkimat.phoneguard.notes.NoteService.instance?.clearKey(a.key); refresh() }
            Action.ClearNotes -> { io.github.durkimat.phoneguard.notes.NoteService.instance?.clearEverything(); refresh() }
            is Action.RadioLoad -> Thread {
                val rb = io.github.durkimat.phoneguard.music.RadioBrowser
                val l = when (a.kind) { "top" -> rb.top(a.arg); "tag" -> rb.byTag(a.arg); else -> rb.byCountry(a.arg) }
                ui.post { model.radioRes = l ?: emptyList(); model.radioStatus = if (l == null) "Could not load" else "Nothing found"; refresh() }
            }.start()
            is Action.RadioSearch -> Thread {
                val l = io.github.durkimat.phoneguard.music.RadioBrowser.search(a.query)
                ui.post { model.radioRes = l ?: emptyList(); model.radioStatus = if (l == null) "Could not search" else "Nothing found"; refresh() }
            }.start()
            is Action.RadioPlayUrl -> { model.provider = Provider.RADIO; io.github.durkimat.phoneguard.music.Radio.playUrl(this, a.name, a.url); model.music = model.music.copy(active = true, source = "radio", playing = true, buffering = true); invalidate(D.SCREEN); refresh() }
            is Action.RadioFav -> { val added = io.github.durkimat.phoneguard.music.RadioFavs.toggle(this, a.name, a.url); model.notify(if (added) "Added to presets" else "Removed from presets"); invalidate(D.INFO); refresh() }
            is Action.RadioStore -> io.github.durkimat.phoneguard.music.Radio.state.let { st ->
                if (st.url.isNotEmpty()) { io.github.durkimat.phoneguard.music.RadioFavs.setSlot(this, a.slot, st.station, st.url); io.github.durkimat.phoneguard.music.Radio.reindex(this); model.notify("Saved as preset ${a.slot}"); invalidate(D.INFO or D.SCREEN); refresh() }
            }
            Action.RadioFavCurrent -> io.github.durkimat.phoneguard.music.Radio.state.let { st ->
                if (st.url.isNotEmpty()) {
                    if (!io.github.durkimat.phoneguard.music.RadioFavs.has(this, st.url) && io.github.durkimat.phoneguard.music.Radio.stations(this).any { it.url == st.url }) model.notify("Already in the list")
                    else { val added = io.github.durkimat.phoneguard.music.RadioFavs.toggle(this, st.station, st.url); model.notify(if (added) "Added to the list" else "Removed from the list"); invalidate(D.INFO or D.SCREEN) }
                    refresh()
                }
            }
            is Action.RadioPlay -> { model.provider = Provider.RADIO; io.github.durkimat.phoneguard.music.Radio.play(this, a.index); model.music = model.music.copy(active = true, source = "radio", playing = true, buffering = true); invalidate(D.SCREEN); refresh() }
            Action.MusicPlayPause -> if (model.provider == Provider.RADIO) { val on = io.github.durkimat.phoneguard.music.Radio.playing; model.music = model.music.copy(playing = !on, buffering = false); redraw(); io.github.durkimat.phoneguard.music.Radio.toggle(this) } else {
                val was = model.music.playing
                playHold = !was to System.currentTimeMillis() + 5000
                model.music = model.music.copy(playing = !was); lastInfo?.let { skin.render(model, it) } // show it at once; the player confirms in a moment
                spOrLocal({ if (was) sp.pause(this) else sp.resume(this) }, { io.github.durkimat.phoneguard.music.MusicController.playPause(this) })
            }
            Action.MusicNext -> if (model.provider == Provider.RADIO) io.github.durkimat.phoneguard.music.Radio.next(this) else spOrLocal({ sp.next(this) }, { io.github.durkimat.phoneguard.music.MusicController.next(this) })
            Action.MusicPrevious -> if (model.provider == Provider.RADIO) io.github.durkimat.phoneguard.music.Radio.previous(this) else spOrLocal({ sp.previous(this) }, { io.github.durkimat.phoneguard.music.MusicController.previous(this) })
            is Action.MusicVolume -> {
                // on another device the volume is Spotify's for that device; on this phone it is the phone's music volume
                if (sp.signedIn(this) && !model.music.thisPhone) Thread { sp.volume(this, model.music.volumePct + (if (a.up) 10 else -10)); spPolledAt = 0; ui.post { invalidate(D.SCREEN); refresh() } }.start()
                else { io.github.durkimat.phoneguard.music.MusicController.volume(this, a.up); model.music = model.music.copy(volumePct = io.github.durkimat.phoneguard.music.MusicController.volumePct(this)); lastInfo?.let { skin.render(model, it) } } // a light redraw: the full refresh reloads messages and calls and made the analyser stutter
            }
            is Action.SoundTest -> io.github.durkimat.phoneguard.SoundTest.play(this, a.route)
            Action.SpShuffle -> Thread { sp.shuffle(this, !model.music.shuffle); spPolledAt = 0; ui.postDelayed({ invalidate(D.SCREEN); refresh() }, 500) }.start()
            Action.SpRepeat -> Thread { sp.repeat(this, when (model.music.repeat) { "off" -> "context"; "context" -> "track"; else -> "off" }); spPolledAt = 0; ui.postDelayed({ invalidate(D.SCREEN); refresh() }, 500) }.start()
            Action.DataToggle -> Thread { io.github.durkimat.phoneguard.net.RadioManager.userSet(this, "data", model.connData == 2); ui.postDelayed({ invalidate(D.INFO) }, 800) }.start()
            Action.WifiLoad -> { netNotice(""); startNetTick() }
            Action.WifiToggle -> Thread { val on = !model.wifiOn; io.github.durkimat.phoneguard.net.WifiControl.setEnabled(this, on); Thread.sleep(1200); loadNet() }.start()
            is Action.WifiConnect -> Thread { val ok = io.github.durkimat.phoneguard.net.WifiControl.connect(this, a.id); netNotice(if (ok) "Connecting..." else "Could not connect"); Thread.sleep(2500); loadNet() }.start()
            is Action.WifiForget -> Thread { val ok = io.github.durkimat.phoneguard.net.WifiControl.forget(this, a.id); netNotice(if (ok) "Forgotten" else "Could not forget"); Thread.sleep(500); loadNet() }.start()
            is Action.WifiJoin -> Thread { val ok = io.github.durkimat.phoneguard.net.WifiControl.join(this, a.ssid, a.password, a.caps, a.id); netNotice(if (ok) "Joining..." else "Could not join"); Thread.sleep(3000); loadNet() }.start()
            Action.BtLoad -> { netNotice(""); startNetTick() }
            Action.BtSearch -> Thread { io.github.durkimat.phoneguard.net.BluetoothControl.search(this); netNotice("Searching..."); loadNet() }.start()
            Action.BtToggle -> Thread { val m = io.github.durkimat.phoneguard.net.BluetoothControl.setEnabled(this, !model.btOn); if (m.isNotEmpty()) netNotice(m); Thread.sleep(1500); loadNet() }.start()
            is Action.BtPair -> Thread { io.github.durkimat.phoneguard.keys.F2Service.cover(true, "Pairing..."); val ok = io.github.durkimat.phoneguard.net.BluetoothControl.pair(this, a.address); if (!ok) io.github.durkimat.phoneguard.keys.F2Service.cover(false); netNotice(if (ok) "Pairing..." else "Could not pair"); Thread.sleep(3000); loadNet() }.start()
            is Action.BtConnect -> Thread {
                netNotice(if (a.connect) "Connecting..." else "Disconnecting...")
                val why = io.github.durkimat.phoneguard.net.BluetoothControl.connectAndVerify(this, a.address, a.connect)
                netNotice(why ?: (if (a.connect) "Connected" else "Disconnected")); loadNet()
            }.start()
            is Action.BtForget -> Thread { val ok = io.github.durkimat.phoneguard.net.BluetoothControl.forget(this, a.address); netNotice(if (ok) "Forgotten" else "Android refused"); Thread.sleep(500); loadNet() }.start()
            is Action.KillApp -> Thread { io.github.durkimat.phoneguard.IdleReaper.stop(this, a.pkg); ui.postDelayed({ invalidate(D.SCREEN); refresh() }, 300) }.start()
            Action.SpectrumToggle -> { val on = !model.spectrum; model.spectrum = on; prefs().edit().putBoolean("spectrum", on).apply(); refresh() }
            Action.SleepStep -> { io.github.durkimat.phoneguard.music.SleepTimer.step(this); refresh() }
            Action.SpSignIn -> start(sp.signInIntent(this))
            Action.SpSignOut -> { sp.signOut(this); spPlayer = null; model.spSignedIn = false; model.music = io.github.durkimat.phoneguard.music.MusicController.info(this); invalidate(D.INFO); refresh() }
            is Action.SpLoad -> Thread {
                if (a.kind == "playlists") { val l = sp.playlists(this); ui.post { model.spPlaylists = l ?: emptyList(); model.spStatus = if (l == null) "Could not load" else "No playlists"; refresh() } }
                else {
                    var l = sp.devices(this)
                    if (l != null && l.none { thisPhone(it) }) l = wakeSpotify(l) // this phone only shows once Spotify is running on it
                    ui.post { model.spIsThisPhone = { d -> thisPhone(d) }; model.spDevices = (l ?: emptyList()).sortedByDescending { thisPhone(it) }; model.spStatus = if (l == null) "Could not load" else "No devices: open Spotify on this phone"; refresh() }
                }
            }.start()
            is Action.SpSearch -> Thread { val l = sp.search(this, a.query); ui.post { model.spResults = l ?: emptyList(); model.spStatus = if (l == null) "Could not search" else "Nothing found"; refresh() } }.start()
            is Action.SpPlay -> Thread { io.github.durkimat.phoneguard.music.Radio.release(this); playOnTarget(a.uri); spPolledAt = 0; ui.postDelayed({ invalidate(D.SCREEN); refresh() }, 800) }.start()
            is Action.SpTransfer -> Thread { io.github.durkimat.phoneguard.music.Radio.release(this); sp.transfer(this, a.id); getSharedPreferences("music", MODE_PRIVATE).edit().putString("target", a.id).apply(); spPolledAt = 0; ui.postDelayed({ invalidate(D.SCREEN); refresh() }, 800) }.start()

            Action.OpenCamera -> start(Intent(this, io.github.durkimat.phoneguard.camera.CameraActivity::class.java))
            is Action.Launch -> launch(a.pkg)
            is Action.PlaceCall -> placeCall(a.number)
            // "Call" on a contact or in the call log opens our own dial screen with the number typed; one more press rings
            is Action.Call -> { model.startDial(a.number); refresh() }
            is Action.Send -> {
                val r = LauncherData.sendSms(this, a.to, a.body)
                if (r == "Message sent") io.github.durkimat.phoneguard.whatsapp.LearnedStore.learn(this, a.body, ::knownWord)
                model.notify(r); invalidate(D.CONVOS or D.INFO); refresh()
            }
            is Action.MailSync -> mailSync(a.force)
            is Action.MailFetch -> Thread { val t = io.github.durkimat.phoneguard.mail.Mail.body(this, a.sent, a.uid); ui.post { model.setMailBody(a.uid, t); mailPush(); refresh() } }.start()
            is Action.MailSend -> Thread {
                val e = io.github.durkimat.phoneguard.mail.Mail.send(this, a.to, a.subject, a.body)
                ui.post { model.notify(if (e.isEmpty()) "Message sent" else "Not sent: " + e.take(30)); if (e.isEmpty()) mailSync(true) else refresh() }
            }.start()
            is Action.MailSeen -> Thread { io.github.durkimat.phoneguard.mail.Mail.setSeen(this, a.uid, a.seen); ui.post { mailPush(); refresh() } }.start()
            is Action.MailDelete -> Thread { val e = io.github.durkimat.phoneguard.mail.Mail.delete(this, a.sent, a.uid); ui.post { if (e.isNotEmpty()) model.notify("Not deleted: " + e.take(24)); mailPush(); refresh() } }.start()
            is Action.MailSetup -> Thread {
                val e = io.github.durkimat.phoneguard.mail.Mail.setup(this, a.account, a.password)
                ui.post { model.mailStatus = ""; mailPush(); model.notify(if (e.isEmpty()) "Signed in" else e.take(60)); if (e.isEmpty()) mailSync(true) else refresh() }
            }.start()
            Action.MailRemove -> { io.github.durkimat.phoneguard.mail.Mail.remove(this); mailPush(); refresh() }
            is Action.CalLoad -> Thread {
                val ev = calendar.events(a.from, a.to)
                var cals = calendar.calendars().filter { it.writable }
                if (cals.isEmpty() && checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) { calendar.ensureWritable(); cals = calendar.calendars().filter { it.writable } }
                ui.post { model.calEvents = ev; model.calWritable = cals; refresh() }
            }.start()
            is Action.CalSave -> Thread { try { calendar.save(a.event) } catch (e: Exception) { ui.post { model.notify("Not saved") } }; calReload() }.start()
            is Action.CalDelete -> Thread { try { calendar.delete(a.id) } catch (_: Exception) { }; calReload() }.start()
            Action.ForgetWords -> { io.github.durkimat.phoneguard.whatsapp.LearnedStore.forget(this); model.notify("Forgotten"); refresh() }
            Action.CallKey -> { model.startDial(""); refresh() } // the green key anywhere in our screens: our dial pad, never the stock dialler
            else -> {} // the call actions belong to the call screen
        }
    }

    private var mailBusy = false
    /** Puts what the phone has kept of the mail (small files) into the model. */
    private fun mailPush() {
        val m = io.github.durkimat.phoneguard.mail.Mail
        model.mailConfig = m.account(this); model.mailInbox = m.inbox(this); model.mailSent = m.sent(this)
    }
    /** Shows the kept mail at once, then checks the server if it has been a while (or [force]) and nothing else is checking. */
    private fun mailSync(force: Boolean) {
        mailPush()
        val m = io.github.durkimat.phoneguard.mail.Mail
        if (m.account(this) == null || mailBusy || (!force && !m.stale(this))) { refresh(); return }
        mailBusy = true; model.mailStatus = "Checking..."; refresh()
        Thread {
            val e = m.sync(this)
            ui.post { mailBusy = false; mailPush(); model.mailStatus = e; if (force) model.notify(if (e.isEmpty()) "Mail checked" else e.take(40)); refresh() }
        }.start()
    }

    /** Reads the calendar again for the window the model last asked for, and redraws. */
    private fun calReload() {
        val (from, to) = model.calRange
        val ev = calendar.events(from, to)
        ui.post { model.calEvents = ev; refresh() }
    }

    private fun remoteVolumeStep(up: Boolean): Pair<Int, String>? {
        val m = model.music
        if (model.screen != Screen.MUSIC || !sp.signedIn(this) || !m.active || m.thisPhone) return null
        val pct = (m.volumePct + (if (up) 10 else -10)).coerceIn(0, 100)
        model.music = m.copy(volumePct = pct)
        Thread { sp.volume(this, pct); spPolledAt = 0 }.start()
        return pct to m.device.uppercase().take(8)
    }

    /** Pauses a music source that is playing (the radio lets go of its stream, Spotify is told to pause); does nothing if it is not playing. */
    private fun pauseSource(p: Provider) {
        if (p == Provider.RADIO) { if (io.github.durkimat.phoneguard.music.Radio.playing) io.github.durkimat.phoneguard.music.Radio.stop(this); return }
        if (providerStates().second[Provider.SPOTIFY] != 2) return
        spOrLocal({ sp.pause(this) }, { io.github.durkimat.phoneguard.music.MusicController.playPause(this) })
    }

    private fun spOrLocal(api: () -> Unit, local: () -> Unit) {
        if (sp.signedIn(this) && spPlayer != null) Thread { api(); spPolledAt = 0; ui.postDelayed({ invalidate(D.SCREEN); refresh() }, 600) }.start()
        else { local(); ui.postDelayed({ invalidate(D.SCREEN); refresh() }, 500) }
    }

    /** Start a playlist, album or song on the chosen device: the one picked in "Play on" if it is still there, else this phone, else wherever it is playing. */
    private fun playOnTarget(uri: String) {
        val pickedId = getSharedPreferences("music", MODE_PRIVATE).getString("target", null)
        var list = sp.devices(this) ?: emptyList()
        var target = list.firstOrNull { it.id == pickedId } ?: list.firstOrNull { thisPhone(it) }
        if (target == null) {
            list = wakeSpotify(list)
            target = list.firstOrNull { thisPhone(it) } ?: list.firstOrNull { it.active }
        }
        Log.log(this, "spotify play: devices=[" + list.joinToString { it.name + "/" + it.type + (if (it.active) "*" else "") } + "] target=" + (target?.name ?: "none"))
        sp.play(this, uri, target?.id)
    }

    /**
     * This phone is not on Spotify's device list until Spotify is running here, and Spotify cannot be started invisibly (it refuses
     * other apps' service connections). Open it, bring our screens back after a second, then wait up to 9 s for the phone to appear.
     * Returns the newest device list.
     */
    private fun wakeSpotify(before: List<io.github.durkimat.phoneguard.music.SpDevice>): List<io.github.durkimat.phoneguard.music.SpDevice> {
        var list = before
        val covered = io.github.durkimat.phoneguard.keys.F2Service.cover(true)
        if (covered) Thread.sleep(250) else ui.post { model.notify("Starting Spotify..."); refresh() }
        launch(io.github.durkimat.phoneguard.music.MusicController.SPOTIFY_PKG)
        Thread.sleep(1200)
        ui.post { start(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        var waited = 0
        var found = false
        while (waited < 9000 && !found) {
            Thread.sleep(1000); waited += 1000
            list = sp.devices(this) ?: emptyList()
            found = list.any { thisPhone(it) }
        }
        io.github.durkimat.phoneguard.keys.F2Service.cover(false)
        Log.log(this, "spotify cold start: " + (if (found) "phone listed after ${waited / 1000}s" else "phone did not appear"))
        return list
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
