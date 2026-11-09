package io.github.durkimat.phoneguard.launcher

/**
 * The launcher's behaviour, with no drawing and no Android classes, so it is unit tested and any skin
 * can sit on top of it. A skin shows [screen], [items] and [selected], and tells the model which key
 * was pressed. The model says what should happen next.
 */
enum class Screen { HOME, MENU, SETTINGS, THEME, FONT, CONTACTS, CONTACT, MESSAGES, INBOX, THREAD, WRITE, TYPING, CALC, CALLLOG, DIAL, CALL, CALLMENU, SPEAKER, WHATSAPP, WAPAIR, WASET, CHAT, WANAMES, APPS, GAMES, SNAKE, PAIRS, SHOOTER, SEEDS, GAMEHELP, WEATHER, BATTERY, NOTES, NOTE, MUSIC, MUSICMENU, PLAYLISTS, DEVICES, SEARCHRES, LOGSET, SOUNDTEST }

data class Contact(val name: String, val numbers: List<String>)

enum class CallPhase { RINGING, DIALING, ACTIVE, ENDED }

/** The call on screen. [startedAt] is when it was answered (epoch ms), [seconds] its length once ended. */
data class CallInfo(
    val number: String, val name: String?, val phase: CallPhase,
    val startedAt: Long = 0, val seconds: Int = 0, val muted: Boolean = false, val speaker: Boolean = false,
)

/** One call in the log: [type] is 1 received, 2 dialled, 3 missed. */
data class CallEntry(val number: String, val name: String, val type: Int, val date: Long)

/** One text message. [mine] is true for messages this phone sent. */
/** [sender] is who wrote it in a group (empty otherwise); the text does not repeat it. */
data class Msg(val body: String, val date: Long, val mine: Boolean, val sender: String = "")

/** A conversation with one number, oldest message first. */
data class Convo(val address: String, val name: String, val msgs: List<Msg>, val unread: Int)

/** A colour scheme: LCD background and ink. Plain ints so the model stays free of Android classes. */
data class Theme(val id: String, val name: String, val lcd: Int, val ink: Int)

object Themes {
    val all = listOf(
        Theme("green", "Green", 0xFF9DC25A.toInt(), 0xFF1A2A08.toInt()),
        Theme("grey", "Grey", 0xFFCFD3CC.toInt(), 0xFF1A1A1A.toInt()),
        Theme("amber", "Amber", 0xFF1A1200.toInt(), 0xFFFFB52E.toInt()),
        Theme("orange", "Orange", 0xFFF2A33A.toInt(), 0xFF3A1A00.toInt()),
    )
    const val DEFAULT = "green"
    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }
}

/** A typeface choice. The skin maps the id to a real typeface. */
data class FontChoice(val id: String, val name: String)

object Fonts {
    val all = listOf(FontChoice("mono", "Monospace"), FontChoice("sans", "Sans-serif"), FontChoice("vt323", "Pixel VT323"), FontChoice("pixelify", "Pixelify"))
    const val DEFAULT = "mono"
    fun byId(id: String?): FontChoice = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }
}

/** The entry the launcher adds to its own menu; it opens the launcher's Settings, not an app. */
const val SETTINGS_PKG = "pg:settings"
const val CAMERA_PKG = "pg:camera"
const val LOGSET_PKG = "pg:logset"
const val MUSIC_PKG = "pg:music"
const val NOTES_PKG = "pg:notes"
const val WEATHER_PKG = "pg:weather"
/** Rows that fit between the title bar and the soft keys. */
const val LIST_VISIBLE = 7
const val GAMES_PKG = "pg:games"
const val APPS_PKG = "pg:apps"
const val CALC_PKG = "pg:calculator"
const val CALLLOG_PKG = "pg:calllog"

/** The keys a keypad phone has, however the hardware reports them. */
enum class Key { UP, DOWN, LEFT, RIGHT, OK, SOFT_LEFT, SOFT_RIGHT, CALL, END, STAR, POUND, DIGIT }

data class MenuItem(val pkg: String, val label: String)

sealed class Action {
    /** Nothing to do, or the key is not ours. */
    object None : Action()
    /** The screen changed: redraw. */
    object Redraw : Action()
    data class Launch(val pkg: String) : Action()
    /** Open the call log or dialler (the green key). */
    object CallKey : Action()
    /** The user chose a theme: the activity saves it and redraws. */
    data class SaveTheme(val id: String) : Action()
    data class SaveFont(val id: String) : Action()
    data class SavePredictive(val on: Boolean) : Action()
    data class SaveSpeaker(val shown: Boolean) : Action()
    /** Start linking WhatsApp: this is the account's phone number (digits, with country code). */
    data class PairWa(val number: String) : Action()
    /** Unlink WhatsApp from the account. */
    object UnlinkWa : Action()
    /** Refresh WhatsApp names and ask for older messages, without unlinking. */
    object ResyncWa : Action()
    /** Forget the words learned from what the owner typed. */
    object ForgetWords : Action()
    /** The owner chose how senders' names are shortened in chat previews. */
    data class SaveNameStyle(val id: String) : Action()
    /** The WhatsApp list was opened: the activity connects the bridge on demand. */
    object WaOpen : Action()
    /** A WhatsApp chat was opened: mark it read. */
    data class WaRead(val chat: String) : Action()
    /** Send a WhatsApp message to a chat (a number, or "g" + a group id). */
    data class SendWa(val chat: String, val body: String) : Action()
    /** Start a call to this number. */
    data class Call(val number: String) : Action()
    object Answer : Action()
    object Reject : Action()
    object EndCall : Action()
    object ToggleSpeaker : Action()
    object ToggleMute : Action()
    /** Send a key tone during a call. */
    data class Dtmf(val tone: Char) : Action()
    /** Call volume up (+1) or down (-1). */
    data class CallVolume(val delta: Int) : Action()
    /** The call screen is finished with. */
    object CloseCall : Action()
    /** Open the themed camera. */
    object OpenCamera : Action()
    object MusicPlayPause : Action()
    object MusicNext : Action()
    object MusicPrevious : Action()
    data class MusicVolume(val up: Boolean) : Action()
    object MusicOpenApp : Action()
    object SpSignIn : Action()
    object SpSignOut : Action()
    data class SpLoad(val kind: String) : Action()
    data class SpSearch(val query: String) : Action()
    data class SpPlay(val uri: String) : Action()
    data class SpTransfer(val id: String, val name: String) : Action()
    object SpShuffle : Action()
    object SpRepeat : Action()
    /** Step the sleep timer: off, 15, 30, 60 minutes. */
    object SleepStep : Action()
    /** Play a test tone: 0 loudspeaker, 1 call speaker, 2 earpiece. */
    data class SoundTest(val route: Int) : Action()
    /** Open the app a notification came from, and clear it. */
    data class OpenNote(val key: String) : Action()
    data class ClearNote(val key: String) : Action()
    object ClearNotes : Action()
    /** Place a call to the number just dialled. */
    data class PlaceCall(val number: String) : Action()
    /** Send this text message. */
    data class Send(val to: String, val body: String) : Action()
}

class LauncherModel(
    items: List<MenuItem> = emptyList(),
    val contactsApp: String? = "com.android.contacts",
    val messagesApp: String? = "com.android.messaging",
    /** The stock Phone app: with our app as the phone's dialler it cannot work, so its menu entry opens our dial screen. */
    val dialerApp: String? = "com.android.dialer",
) {
    var screen = Screen.HOME
        private set
    var items: List<MenuItem> = items
        private set
    var selected = 0
        private set

    /** The allowed apps that are not in the main menu, shown under "Android Apps". */
    var apps: List<MenuItem> = emptyList()

    fun setItems(list: List<MenuItem>) {
        items = list
        // only the app menu is as long as this list: trimming the selection on any other screen pulled it back to
        // the menu's length (seven rows) every time the launcher redrew, so longer lists opened the wrong entry
        if (screen == Screen.MENU) selected = selected.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    /** The id of the theme in use. The activity sets it from storage at start. */
    var theme: String = Themes.DEFAULT

    var font: String = Fonts.DEFAULT

    /** The phone's contacts, sorted by name. The activity fills this in. */
    var contacts: List<Contact> = emptyList()
    private var contact: Contact? = null
    private var contactsFrom = Screen.HOME

    /** Whether the Settings list offers Android's own settings (only where touch works: Android's screens are made for it). */
    var androidSettings = false
    private val settingsRows: List<String> get() = listOf("Theme", "Font", "Predictive text", "Speakerphone", "Sound test") + (if (waEnabled) listOf("WhatsApp") else emptyList()) + (if (androidSettings) listOf("Android settings") else emptyList()) + listOf("Battery")

    // ---- Messages state
    /** Conversations, newest first. The activity fills this in. */
    var convos: List<Convo> = emptyList()
    /** WhatsApp: whether the account is linked, the chats (newest first), the count unread and the bridge's state. */
    var waEnabled = false
    /** Whether an account is linked. Not linked: the WhatsApp entry offers to link one. */
    var waLinked = false
    /** How a sender's name is shortened in the one-line chat previews (see [NameStyle]). */
    var waNameStyle: String = NameStyle.DEFAULT
    /** The pairing code to type into WhatsApp on the owner's other phone, once the bridge has one. */
    var waCode: String? = null
    var waPairNumber = ""
        private set
    var waConvos: List<Convo> = emptyList()
    var waUnread = 0
    var waStatus = ""
    /** A short line under the WhatsApp list when the bridge is not connected. */
    val waNote: String get() = when (waStatus) {
        "connected" -> ""
        "connecting" -> "Connecting..."
        "not linked" -> "Not linked yet"
        "paired" -> "Linked"
        "logged_out" -> "Link was removed"
        "error" -> "Not available"
        else -> "Not connected"
    }
    private var threadWa = false
    var writeWa = false
        private set
    /** What to show after "To:" instead of a number (a name), when known. */
    var writeLabel = ""
        private set
    /** Recent calls, newest first. The activity fills this in. */
    var calls: List<CallEntry> = emptyList()
    val calc = Calculator()
    val snake = Snake()
    /** The latest weather, or null when none is set up or it is out of date. */
    var weather: WeatherData? = null
    /** The symbols shown on the home screen, left to right; the skin draws them and a tap on one opens what it stands for. */
    var badges: List<Badge> = emptyList()
    /** True when a list was opened by tapping a symbol on the home screen, so Back goes home. */
    private var homeEntry = false
    private var weatherFrom = Screen.MENU
    val pairs = Pairs()
    val shooter = Shooter()
    val seeds = Seeds()
    /** The fewest moves to finish pairs (0 = not played) and the best shooter score; the activity saves them. */
    var pairsBest = 0
    var shooterBest = 0
    /** The best snake score on this phone; the activity saves it. */
    var snakeBest = 0
    /** The call being shown, set by the call screen's activity. */
    var call: CallInfo? = null
        private set
    fun setCall(info: CallInfo?) {
        call = info
        if (info == null) { goHome(); return }
        if (screen != Screen.CALLMENU || info.phase != CallPhase.ACTIVE) screen = Screen.CALL
    }
    /** Opens the dial screen with a number already typed (a tel: link, or a button in another app). */
    fun startDial(number: String) { dialNumber = number.filter { it.isDigit() || it in "*#+" }.take(24); screen = Screen.DIAL }
    var dialNumber = ""
        private set
    /** The contact whose number matches what has been dialled, shown under it like a Nokia. */
    val dialName: String? get() {
        if (dialNumber.length < 5) return null
        val d = dialNumber.filter { it.isDigit() }.takeLast(9)
        return contacts.firstOrNull { c -> c.numbers.any { n -> n.filter { it.isDigit() }.takeLast(9) == d } }?.name
    }
    private var callsFrom = Screen.HOME
    /** Splits text into the lines that fit the screen. The skin sets this, because only it knows the font. */
    var wrapper: (String) -> List<String> = { listOf(it) }
    var pageLines = 11
    var scroll = 0
        private set
    private var convo: Convo? = null
    /** The conversation being read, its message on show and how many it has: for the skin to draw. */
    val openConvo: Convo? get() = convo
    val shownMsg: Msg? get() = convo?.msgs?.getOrNull(selected)
    /** The text of the message being read (for tests and the skin). */
    fun convoText(): String = shownMsg?.body ?: ""
    private val messagesRows: List<String> get() =
        if (waEnabled) listOf("Inbox", "WhatsApp" + if (waLinked && waUnread > 0) " ($waUnread)" else "", "Write message") else listOf("Inbox", "Write message")

    var writeTo = ""
        private set
    var writeText = ""
        private set
    var cursor = 0
        private set
    /** 0 is the To field, 1 the message. */
    var writeField = 1
        private set
    private var writeMode = 0

    // ---- Predictive text (T9)
    /** The dictionary; null until it has loaded, and then the T9 modes are not offered. */
    var predictor: Predictor? = null
    /** The user's choice in Settings. */
    var predictiveOn = true
    /** Whether calls offer a speaker. Hidden on a phone with no loudspeaker. */
    var speakerShown = true
    private var seq = ""
    private var cands: List<String> = emptyList()
    private var candIdx = 0
    private var compStart = 0
    private var compLen = 0
    private val modes: List<String> get() =
        if (predictor != null && predictiveOn) listOf("T9 Abc", "T9 abc", "T9 ABC", "Abc", "abc", "ABC", "123") else listOf("Abc", "abc", "ABC", "123")
    /** The characters being typed as a word, which the skin underlines until the word is accepted. */
    val composingRange: IntRange? get() = if (seq.isEmpty()) null else compStart until (compStart + compLen)
    private val t9: Boolean get() = modeName.startsWith("T9")
    private var writeBack = Screen.MESSAGES
    private var picking = false
    private var lastDigit = -1
    private var lastAt = 0L
    private var tapIdx = 0
    val modeName: String get() = modes[writeMode.coerceIn(0, modes.size - 1)]

    /** A short message shown over the screen until the next key ("Message sent"). */
    var notice: String? = null
        private set
    fun notify(text: String) { notice = text }
    fun clearNotice() { notice = null }

    fun goHome() { screen = Screen.HOME; selected = 0; homeEntry = false }

    val title: String get() = when (screen) {
        Screen.SETTINGS -> "Settings"
        Screen.THEME -> "Theme"
        Screen.FONT -> "Font"
        Screen.CONTACTS -> "Contacts"
        Screen.CONTACT -> contact?.name ?: "Contact"
        Screen.MESSAGES -> "Messages"
        Screen.INBOX -> "Inbox"
        Screen.WHATSAPP -> "WhatsApp"
        Screen.THREAD, Screen.CHAT -> convo?.name ?: "Messages"
        Screen.WRITE -> "Write message"
        Screen.TYPING -> "Predictive text"
        Screen.SPEAKER -> "Speakerphone"
        Screen.WAPAIR -> "Link WhatsApp"
        Screen.WASET -> "WhatsApp"
        Screen.WANAMES -> "Names in lists"
        Screen.APPS -> "Android Apps"
        Screen.GAMES -> "Classic Games"
        Screen.SNAKE -> "Snake"
        Screen.PAIRS -> "Pairs"
        Screen.SHOOTER -> "Blaster"
        Screen.SEEDS -> "Seeds"
        Screen.GAMEHELP -> gameNames[helpGame]
        Screen.WEATHER -> "Weather"
        Screen.BATTERY -> "Battery"
        Screen.MUSIC -> "Now playing"
        Screen.LOGSET -> "Logs & Settings"
        Screen.SOUNDTEST -> "Sound test"
        Screen.MUSICMENU -> "Music"
        Screen.PLAYLISTS -> "Playlists"
        Screen.DEVICES -> "Play on"
        Screen.SEARCHRES -> "Results"
        Screen.NOTES -> "Notifications"
        Screen.NOTE -> "Notification"
        Screen.CALC -> "Calculator"
        Screen.CALLLOG -> "Call log"
        Screen.DIAL -> "Call"
        Screen.CALLMENU -> "Options"
        Screen.CALL -> "Call"
        else -> "Menu"
    }

    /** The two labels under the screen, which the two keys beneath it and D-pad Left/Right carry out. */
    val softKeys: Pair<String, String> get() = when (screen) {
        Screen.HOME -> "Menu" to (if (items.any { it.pkg == MUSIC_PKG }) "Music" else "Contacts")
        Screen.CALC -> "Equals" to "Clear"
        Screen.CALLLOG -> (if (callsFrom == Screen.DIAL) "Contacts" else "Call") to "Back"
        Screen.CONTACTS -> (if (contactsFrom == Screen.DIAL && !picking) "Dial" else "Select") to "Back"
        Screen.SNAKE -> (when (snake.state) { Snake.State.READY -> "Start"; Snake.State.RUNNING -> "Pause"; Snake.State.PAUSED -> "Resume"; Snake.State.OVER -> "Again" }) to "Back"
        Screen.SHOOTER -> (when (shooter.state) { Shooter.State.READY -> "Start"; Shooter.State.RUNNING -> "Pause"; Shooter.State.PAUSED -> "Resume"; Shooter.State.OVER -> "Again" }) to "Back"
        Screen.PAIRS -> (if (pairs.won) "Again" else "Turn") to "Back"
        Screen.SEEDS -> (if (seeds.over) "Again" else "Sow") to "Back"
        Screen.GAMEHELP -> "Play" to "Back"
        Screen.WEATHER -> "" to "Back"
        Screen.BATTERY -> "" to "Back"
        Screen.MUSICMENU, Screen.PLAYLISTS, Screen.SEARCHRES, Screen.DEVICES, Screen.LOGSET -> "Select" to "Back"
        Screen.SOUNDTEST -> "Play" to "Back"
        Screen.MUSIC -> (if (music.active) (if (music.playing) "Pause" else "Play") else "Open") to "Back"
        Screen.NOTES -> "Open" to "Back"
        Screen.NOTE -> "Open" to "Back"
        Screen.DIAL -> "Calls" to (if (dialNumber.isEmpty()) "Back" else "Clear")
        Screen.CALLMENU -> "Select" to "Back"
        Screen.CALL -> when (call?.phase) {
            CallPhase.RINGING -> "Answer" to "Reject"
            CallPhase.DIALING -> (if (speakerShown) "Speaker" else "") to "End"
            CallPhase.ACTIVE -> "Options" to "End"
            else -> "" to ""
        }
        Screen.INBOX -> "Open" to "Back"
        Screen.WHATSAPP -> (if (waLinked) "Open" else "Link") to "Back"
        Screen.WAPAIR -> (if (waLinked) "" else if (waCode != null) "New code" else "Get code") to (if (waCode != null || waLinked) "Back" else "Clear")
        Screen.THREAD -> "Reply" to "Back"
        Screen.CHAT -> (if (selected == 0) "Reply" else "Read") to "Back"
        Screen.WRITE -> (if (writeField == 0) (if (writeTo.isEmpty()) "Contacts" else "Next") else "Send") to "Clear"
        else -> "Select" to "Back"
    }

    /** The lines of whichever list is showing. */
    val rows: List<String> get() = when (screen) {
        Screen.MENU -> items.map { it.label }
        Screen.APPS -> apps.map { it.label }
        Screen.GAMES -> listOf("Snake", "Pairs", "Blaster", "Seeds")
        Screen.SNAKE, Screen.PAIRS, Screen.SHOOTER, Screen.SEEDS, Screen.GAMEHELP, Screen.WEATHER, Screen.BATTERY, Screen.NOTE, Screen.MUSIC -> emptyList()
        Screen.MUSICMENU -> musicMenuRows
        Screen.LOGSET -> logItems.map { it.label }
        Screen.SOUNDTEST -> io.github.durkimat.phoneguard.SoundTest.routes
        Screen.PLAYLISTS -> spPlaylists.map { it.label }
        Screen.SEARCHRES -> spResults.map { it.label }
        Screen.DEVICES -> spDevices.map { (if (it.active) "> " else "  ") + it.name + (if (spIsThisPhone(it)) " (this phone)" else "") }
        Screen.NOTES -> notes.map { n -> n.app + ": " + (if (n.title.isNotEmpty()) n.title else n.text) } + (if (notes.isEmpty()) emptyList() else listOf("Clear all"))
        Screen.SETTINGS -> settingsRows
        Screen.THEME -> Themes.all.map { it.name + if (it.id == theme) " *" else "" }
        Screen.FONT -> Fonts.all.map { it.name + if (it.id == font) " *" else "" }
        Screen.CONTACTS -> contacts.map { it.name }
        Screen.CONTACT -> (contact?.numbers ?: emptyList()).map { "Call $it" } + "Message"
        Screen.CALLLOG -> calls.map { (when (it.type) { 2 -> "> "; 3 -> "! "; else -> "< " }) + it.name }
        Screen.CALC, Screen.DIAL, Screen.CALL, Screen.WAPAIR -> emptyList()
        Screen.CALLMENU -> (if (speakerShown) listOf(if (call?.speaker == true) "Speaker off" else "Speaker on") else emptyList()) + (if (call?.muted == true) "Unmute" else "Mute")
        Screen.WASET -> if (waLinked) listOf("Re-sync names and messages", "Names in lists", "Unlink account") else listOf("Link account")
        Screen.WANAMES -> NameStyle.all.map { it.second + if (it.first == waNameStyle) " *" else "" }
        Screen.SPEAKER -> listOf("Show" + if (speakerShown) " *" else "", "Hide" + if (!speakerShown) " *" else "")
        Screen.TYPING -> listOf("On" + if (predictiveOn) " *" else "", "Off" + if (!predictiveOn) " *" else "", "Forget learned words")
        Screen.MESSAGES -> messagesRows
        Screen.INBOX -> convos.map { (if (it.unread > 0) "* " else "") + it.name }
        Screen.WHATSAPP -> if (!waLinked) listOf("Link account") else waConvos.map { (if (it.unread > 0) "* " else "") + it.name }
        // the messages of the open chat, newest first, behind a "Reply" entry; each is a one-line preview
        Screen.CHAT -> listOf("Reply") + (convo?.msgs ?: emptyList()).asReversed().map {
            val who = if (!it.mine && it.sender.isNotBlank()) NameStyle.short(it.sender, waNameStyle) + ": " else ""
            (if (it.mine) "> " else "< ") + who + it.body.lineSequence().firstOrNull().orEmpty()
        }
        Screen.THREAD, Screen.WRITE, Screen.HOME -> emptyList()
    }

    /** `digit` is only used with [Key.DIGIT]. */
    fun onKey(key: Key, digit: Int = -1, nowMs: Long = System.currentTimeMillis()): Action {
        notice = null
        return dispatch(key, digit, nowMs)
    }

    /** The lists whose numbered entries are chosen by pressing that number, like a Nokia menu. */
    private val numbered = setOf(Screen.SETTINGS, Screen.THEME, Screen.FONT, Screen.TYPING, Screen.SPEAKER, Screen.INBOX, Screen.WHATSAPP, Screen.WASET, Screen.WANAMES, Screen.CHAT, Screen.CONTACTS, Screen.CONTACT, Screen.CALLLOG, Screen.CALLMENU, Screen.APPS, Screen.GAMES, Screen.MUSICMENU, Screen.PLAYLISTS, Screen.SEARCHRES, Screen.DEVICES, Screen.LOGSET, Screen.SOUNDTEST)

    private fun dispatch(key: Key, digit: Int, nowMs: Long): Action {
        if (key == Key.DIGIT && screen in numbered && digit in 1..minOf(9, rows.size)) { selected = digit - 1; return route(Key.OK, -1, nowMs) }
        return route(key, digit, nowMs)
    }

    private fun route(key: Key, digit: Int, nowMs: Long): Action = when (screen) {
        Screen.HOME -> home(key, digit)
        Screen.MENU -> menu(key, digit)
        Screen.APPS -> appList(key)
        Screen.GAMES -> gameList(key)
        Screen.GAMEHELP -> gameHelp(key, digit)
        Screen.WEATHER -> weatherKey(key)
        Screen.BATTERY -> batteryKey(key)
        Screen.MUSIC -> musicKey(key, digit)
        Screen.MUSICMENU -> musicMenuKey(key)
        Screen.LOGSET -> logSetKey(key)
        Screen.SOUNDTEST -> soundTestKey(key)
        Screen.PLAYLISTS, Screen.SEARCHRES, Screen.DEVICES -> spListKey(key)
        Screen.NOTES -> notesKey(key)
        Screen.NOTE -> noteDetailKey(key, digit)
        Screen.SNAKE -> snakeKey(key, digit)
        Screen.PAIRS -> pairsKey(key, digit)
        Screen.SHOOTER -> shooterKey(key, digit)
        Screen.SEEDS -> seedsKey(key, digit)
        Screen.SETTINGS -> settings(key, digit)
        Screen.THEME -> themes(key, digit)
        Screen.FONT -> fonts(key, digit)
        Screen.CONTACTS -> contactList(key, digit)
        Screen.CONTACT -> contactDetail(key, digit)
        Screen.MESSAGES -> messagesMenu(key, digit)
        Screen.INBOX -> inbox(key, digit)
        Screen.WHATSAPP -> whatsapp(key, digit)
        Screen.WAPAIR -> waPair(key, digit)
        Screen.WANAMES -> waNames(key, digit)
        Screen.WASET -> waSettings(key, digit)
        Screen.THREAD -> thread(key, digit)
        Screen.CHAT -> chat(key, digit)
        Screen.WRITE -> write(key, digit, nowMs)
        Screen.TYPING -> typing(key, digit)
        Screen.SPEAKER -> speakerSetting(key, digit)
        Screen.CALC -> calculator(key, digit)
        Screen.CALLLOG -> callLog(key, digit)
        Screen.DIAL -> dial(key, digit)
        Screen.CALL -> callScreen(key, digit)
        Screen.CALLMENU -> callMenu(key, digit)
    }

    private fun callScreen(key: Key, digit: Int): Action {
        val c = call ?: return Action.None
        return when (c.phase) {
            CallPhase.RINGING -> when (key) {
                Key.OK, Key.LEFT, Key.SOFT_LEFT, Key.CALL -> Action.Answer
                Key.RIGHT, Key.SOFT_RIGHT, Key.END -> Action.Reject
                else -> Action.None
            }
            CallPhase.DIALING -> when (key) {
                Key.LEFT, Key.SOFT_LEFT -> if (speakerShown) Action.ToggleSpeaker else Action.None
                Key.RIGHT, Key.SOFT_RIGHT, Key.END, Key.OK -> Action.EndCall
                else -> Action.None
            }
            CallPhase.ACTIVE -> when (key) {
                Key.OK, Key.LEFT, Key.SOFT_LEFT -> { screen = Screen.CALLMENU; selected = 0; Action.Redraw }
                Key.RIGHT, Key.SOFT_RIGHT, Key.END -> Action.EndCall
                Key.DIGIT -> if (digit in 0..9) Action.Dtmf('0' + digit) else Action.None
                Key.STAR -> Action.Dtmf('*')
                Key.POUND -> Action.Dtmf('#')
                Key.UP -> Action.CallVolume(1)
                Key.DOWN -> Action.CallVolume(-1)
                else -> Action.None
            }
            CallPhase.ENDED -> Action.CloseCall
        }
    }

    private fun callMenu(key: Key, digit: Int): Action = move(key, rows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { val a = if (speakerShown && selected == 0) Action.ToggleSpeaker else Action.ToggleMute; screen = Screen.CALL; a }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.CALL; Action.Redraw }
        Key.END -> Action.EndCall
        else -> Action.None
    }

    private fun dial(key: Key, digit: Int): Action {
        when (key) {
            Key.DIGIT -> if (digit in 0..9 && dialNumber.length < 24) dialNumber += digit
            Key.STAR -> when { dialNumber.endsWith("*") -> dialNumber = dialNumber.dropLast(1) + "+"; dialNumber.endsWith("+") -> dialNumber = dialNumber.dropLast(1) + "*"; dialNumber.length < 24 -> dialNumber += "*" }
            Key.POUND -> if (dialNumber.length < 24) dialNumber += "#"
            Key.OK -> return if (dialNumber.isEmpty()) Action.None else Action.PlaceCall(dialNumber)
            Key.CALL -> return if (dialNumber.isEmpty()) openCallLog(Screen.DIAL) else Action.PlaceCall(dialNumber) // the dial key twice: straight to the calls
            Key.LEFT, Key.SOFT_LEFT -> return openCallLog(Screen.DIAL) // left cycles: dial pad, calls, contacts, dial pad
            Key.RIGHT, Key.SOFT_RIGHT -> return if (dialNumber.isEmpty()) exitDialler() else { dialNumber = dialNumber.dropLast(1); Action.Redraw }
            Key.END -> { dialNumber = ""; goHome() }
            else -> return Action.None
        }
        return Action.Redraw
    }

    /** Back out of the dialler (pad, calls or contacts) to where it was opened from. */
    private fun exitDialler(): Action {
        if (dialFrom == Screen.MENU) { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == dialerApp }.coerceAtLeast(0) } else goHome()
        dialNumber = ""
        return Action.Redraw
    }

    private fun openCallLog(from: Screen): Action { callsFrom = from; screen = Screen.CALLLOG; selected = 0; return Action.Redraw }

    private fun callLog(key: Key, digit: Int): Action = move(key, calls.size) ?: when (key) {
        Key.LEFT, Key.SOFT_LEFT -> if (callsFrom == Screen.DIAL) openContacts(Screen.DIAL) else calls.getOrNull(selected)?.let { Action.Call(it.number) } ?: Action.None
        Key.OK, Key.CALL -> calls.getOrNull(selected)?.let { Action.Call(it.number) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { if (callsFrom == Screen.DIAL) exitDialler() else if (callsFrom == Screen.LOGSET) { screen = Screen.LOGSET; selected = logItems.indexOfFirst { it.pkg == CALLLOG_PKG }.coerceAtLeast(0) } else if (callsFrom == Screen.MENU) { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == CALLLOG_PKG }.coerceAtLeast(0) } else goHome(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun calculator(key: Key, digit: Int): Action {
        when (key) {
            Key.DIGIT -> calc.digit(digit)
            Key.POUND -> calc.point()
            Key.STAR -> calc.cycleOperator()
            Key.UP -> calc.operator('+')
            Key.DOWN -> calc.operator('-')
            Key.OK, Key.LEFT, Key.SOFT_LEFT -> calc.equals()
            Key.RIGHT, Key.SOFT_RIGHT -> if (!calc.clear()) { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == CALC_PKG }.coerceAtLeast(0) }
            Key.END -> { calc.allClear(); goHome() }
            else -> return Action.None
        }
        return Action.Redraw
    }

    private fun speakerSetting(key: Key, digit: Int): Action = move(key, 2) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { speakerShown = selected == 0; screen = Screen.SETTINGS; selected = 3; Action.SaveSpeaker(speakerShown) }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SETTINGS; selected = 3; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun typing(key: Key, digit: Int): Action = move(key, 3) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (selected == 2) { screen = Screen.SETTINGS; selected = 2; Action.ForgetWords } else { predictiveOn = selected == 0; writeMode = 0; screen = Screen.SETTINGS; selected = 2; Action.SavePredictive(predictiveOn) }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SETTINGS; selected = 2; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    // ---- Messages

    private fun openMessages(): Action { screen = Screen.MESSAGES; selected = 0; return Action.Redraw }

    private fun messagesMenu(key: Key, digit: Int): Action = move(key, messagesRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> messageEntry(selected)
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == messagesApp }.coerceAtLeast(0); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        Key.DIGIT -> if (digit in 1..messagesRows.size) { selected = digit - 1; messageEntry(digit - 1) } else Action.None
        else -> Action.None
    }

    /** Row 0 is the SMS inbox; with WhatsApp linked row 1 is WhatsApp and the last row writes a text. */
    private fun messageEntry(row: Int): Action = when {
        row == 0 -> { screen = Screen.INBOX; selected = 0; Action.Redraw }
        waEnabled && row == 1 -> { screen = Screen.WHATSAPP; selected = 0; threadWa = true; if (waLinked) Action.WaOpen else Action.Redraw }
        else -> { threadWa = false; startWrite("", Screen.MESSAGES, wa = false) }
    }

    private fun startPair(back: Screen): Action { waPairNumber = ""; waCode = null; waPairBack = back; screen = Screen.WAPAIR; return Action.Redraw }
    private var waPairBack = Screen.WHATSAPP

    private fun waNames(key: Key, digit: Int): Action = move(key, NameStyle.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { waNameStyle = NameStyle.all[selected].first; screen = Screen.WASET; selected = 1; Action.SaveNameStyle(waNameStyle) }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.WASET; selected = 1; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun waSettings(key: Key, digit: Int): Action = move(key, if (waLinked) 3 else 1) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when {
            !waLinked -> startPair(Screen.WASET)
            selected == 0 -> { notice = "Re-syncing..."; Action.ResyncWa }
            selected == 1 -> { screen = Screen.WANAMES; selected = NameStyle.all.indexOfFirst { it.first == waNameStyle }.coerceAtLeast(0); Action.Redraw }
            else -> { screen = Screen.SETTINGS; selected = 4; Action.UnlinkWa }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SETTINGS; selected = 4; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun waPair(key: Key, digit: Int): Action {
        val done = waCode != null || waLinked
        return when (key) {
            Key.DIGIT -> if (!done && waPairNumber.length < 15 && digit in 0..9) { waPairNumber += digit; Action.Redraw } else Action.None
            // with a code showing, the left key asks for a fresh one (the old one may have run out)
            Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (!waLinked && waPairNumber.length >= 9) Action.PairWa(waPairNumber) else Action.None
            Key.SOFT_RIGHT, Key.RIGHT -> when {
                done -> { screen = waPairBack; selected = 0; Action.Redraw }
                waPairNumber.isNotEmpty() -> { waPairNumber = waPairNumber.dropLast(1); Action.Redraw }
                else -> { screen = waPairBack; selected = 0; Action.Redraw }
            }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun whatsapp(key: Key, digit: Int): Action = move(key, if (waLinked) waConvos.size else 1) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (!waLinked) startPair(Screen.WHATSAPP) else waConvos.getOrNull(selected)?.let { convo = it; threadWa = true; screen = Screen.CHAT; selected = if (it.msgs.isEmpty()) 0 else 1; scroll = 0; Action.WaRead(it.address) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { threadWa = false; if (homeEntry) goHome() else { screen = Screen.MESSAGES; selected = 1 }; Action.Redraw }
        Key.END -> { threadWa = false; goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun inbox(key: Key, digit: Int): Action = move(key, convos.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> convos.getOrNull(selected)?.let { convo = it; threadWa = false; screen = Screen.CHAT; selected = if (it.msgs.isEmpty()) 0 else 1; scroll = 0; Action.Redraw } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { if (homeEntry) goHome() else { screen = Screen.MESSAGES; selected = 0 }; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> convos.getOrNull(selected)?.let { Action.Call(it.address) } ?: Action.CallKey
        else -> Action.None
    }

    /** The open chat as a list: "Reply" first, then every kept message newest first; choosing one reads it in full. */
    private fun chat(key: Key, digit: Int): Action {
        val c = convo ?: run { goHome(); return Action.Redraw }
        val rows = c.msgs.size + 1
        return move(key, rows) ?: when (key) {
            Key.OK, Key.LEFT, Key.SOFT_LEFT ->
                if (selected == 0) startWrite(c.address, Screen.CHAT, label = c.name, wa = threadWa)
                else { val idx = c.msgs.size - selected; screen = Screen.THREAD; selected = idx.coerceIn(0, c.msgs.size - 1); scroll = 0; Action.Redraw }
            Key.RIGHT, Key.SOFT_RIGHT -> {
                if (threadWa) { screen = Screen.WHATSAPP; selected = waConvos.indexOfFirst { it.address == c.address }.coerceAtLeast(0) }
                else { screen = Screen.INBOX; selected = convos.indexOfFirst { it.address == c.address }.coerceAtLeast(0) }
                Action.Redraw
            }
            Key.CALL -> Action.Call(c.address)
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun thread(key: Key, digit: Int): Action {
        val c = convo ?: run { goHome(); return Action.Redraw }
        val msg = c.msgs.getOrNull(selected)
        val lines = msg?.let { wrapper(it.body).size } ?: 0
        return when (key) {
            Key.DOWN -> when {
                scroll + pageLines < lines -> { scroll += pageLines; Action.Redraw }
                selected < c.msgs.size - 1 -> { selected++; scroll = 0; Action.Redraw }
                else -> Action.None
            }
            Key.UP -> when {
                scroll > 0 -> { scroll = (scroll - pageLines).coerceAtLeast(0); Action.Redraw }
                selected > 0 -> { selected--; scroll = 0; Action.Redraw }
                else -> Action.None
            }
            Key.OK, Key.LEFT, Key.SOFT_LEFT -> startWrite(c.address, Screen.THREAD, label = c.name, wa = threadWa)
            Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.CHAT; selected = (c.msgs.size - selected).coerceIn(0, c.msgs.size); Action.Redraw }
            Key.CALL -> Action.Call(c.address)
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun startWrite(to: String, back: Screen, label: String = "", wa: Boolean = false): Action {
        writeTo = to; writeText = ""; cursor = 0; writeBack = back; writeMode = 0; lastDigit = -1; commit()
        writeWa = wa; writeLabel = label; writeSearch = false
        writeField = if (to.isEmpty()) 0 else 1
        screen = Screen.WRITE
        return Action.Redraw
    }

    private fun leaveWrite(): Action { screen = writeBack; if (writeBack == Screen.MESSAGES) selected = 1; return Action.Redraw }

    private fun write(key: Key, digit: Int, now: Long): Action =
        if (writeField == 0) writeToField(key, digit) else writeBody(key, digit, now)

    private fun writeToField(key: Key, digit: Int): Action = when (key) {
        Key.DIGIT -> if (writeTo.length < 20 && digit in 0..9) { writeTo += digit; Action.Redraw } else Action.None
        Key.STAR -> if (writeTo.isEmpty()) { writeTo = "+"; Action.Redraw } else Action.None
        Key.SOFT_RIGHT -> if (writeTo.isNotEmpty()) { writeTo = writeTo.dropLast(1); Action.Redraw } else leaveWrite()
        Key.DOWN -> { writeField = 1; Action.Redraw }
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (writeTo.isEmpty()) { picking = true; openContacts(Screen.WRITE) } else { writeField = 1; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun writeBody(key: Key, digit: Int, now: Long): Action = when (key) {
        Key.DIGIT -> {
            if (t9 && digit in 2..9) predictDigit(digit)
            else { commit(); typeKey(digit, now) }
            Action.Redraw
        }
        // * steps through the other words for the keys just pressed, like a Nokia; otherwise it types punctuation
        Key.STAR -> { if (t9 && seq.isNotEmpty()) nextWord() else { commit(); typeKey(10, now) }; Action.Redraw }
        Key.POUND -> { commit(); writeMode = (writeMode + 1) % modes.size; lastDigit = -1; Action.Redraw }
        Key.UP -> { commit(); writeField = 0; Action.Redraw }
        Key.LEFT -> { commit(); cursor = (cursor - 1).coerceAtLeast(0); lastDigit = -1; Action.Redraw }
        Key.RIGHT -> { commit(); cursor = (cursor + 1).coerceAtMost(writeText.length); lastDigit = -1; Action.Redraw }
        Key.OK, Key.SOFT_LEFT -> { commit(); if (writeSearch) { if (writeText.isBlank()) Action.None else { val q = writeText.trim(); screen = Screen.SEARCHRES; selected = 0; spResults = emptyList(); spStatus = "Searching..."; Action.SpSearch(q) } } else if (writeText.isBlank() || writeTo.isBlank()) Action.None else {
            val a = if (writeWa) Action.SendWa(writeTo, writeText.trim()) else Action.Send(writeTo, writeText.trim())
            screen = Screen.MESSAGES; selected = 0; notice = "Message sent"
            a
        } }
        Key.SOFT_RIGHT -> when {
            seq.isNotEmpty() -> { unpress(); Action.Redraw }
            cursor > 0 -> { writeText = writeText.removeRange(cursor - 1, cursor); cursor--; lastDigit = -1; Action.Redraw }
            writeText.isEmpty() -> leaveWrite()
            else -> Action.None
        }
        Key.CALL -> if (writeTo.isNotBlank()) Action.Call(writeTo) else Action.None
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    // ---- predictive text

    private fun predictDigit(d: Int) {
        if (writeText.length >= 480) return
        if (seq.isEmpty()) { compStart = cursor; compLen = 0 }
        seq += d
        lookUp()
    }

    private fun lookUp() {
        val p = predictor
        var found = p?.exact(seq).orEmpty()
        if (found.isEmpty() && p != null) found = p.longer(seq, 5).map { takeLetters(it, seq.length) }.distinct()
        if (found.isEmpty()) found = listOf(seq.map { tapChars[it - '0']!![0] }.joinToString(""))
        cands = found; candIdx = 0
        place()
    }

    private fun takeLetters(w: String, n: Int): String {
        var count = 0; val sb = StringBuilder()
        for (ch in w) { if (ch != '\'') { if (count == n) break; count++ }; sb.append(ch) }
        return sb.toString()
    }

    private fun nextWord() { candIdx = (candIdx + 1) % cands.size; place() }

    private fun unpress() {
        seq = seq.dropLast(1)
        if (seq.isEmpty()) {
            writeText = writeText.removeRange(compStart, compStart + compLen); cursor = compStart; compLen = 0; cands = emptyList()
        } else lookUp()
    }

    private fun place() {
        val word = caseWord(cands[candIdx], writeText.substring(0, compStart))
        writeText = writeText.substring(0, compStart) + word + writeText.substring(compStart + compLen)
        compLen = word.length
        cursor = compStart + compLen
    }

    /** Accepts the word on show and starts a new one. */
    private fun commit() { seq = ""; cands = emptyList(); compLen = 0; candIdx = 0 }

    private fun caseWord(w: String, before: String): String = when {
        modeName.endsWith("ABC") -> w.uppercase()
        w.any { it.isUpperCase() } -> w // names and "I" keep their capitals
        modeName.endsWith("Abc") && startsSentence(before) -> w.replaceFirstChar { it.uppercase() }
        else -> w
    }

    private fun startsSentence(before: String) = before.isEmpty() || before.endsWith(". ") || before.endsWith("! ") || before.endsWith("? ") || before.endsWith("\n")

    private val tapChars = mapOf(
        0 to " 0", 1 to ".,?!'-@1", 2 to "abc2", 3 to "def3", 4 to "ghi4", 5 to "jkl5", 6 to "mno6", 7 to "pqrs7", 8 to "tuv8", 9 to "wxyz9",
        10 to ".,?!'-@:/()&",
    )

    /** Multi-tap typing: press a key again within a second to cycle its letters, like a Nokia without T9. */
    private fun typeKey(d: Int, now: Long) {
        if (writeText.length >= 480) return
        if (modeName == "123" && d <= 9) { insert(d.toString()); lastDigit = -1; return }
        val set = tapChars[d] ?: return
        if (d == lastDigit && now - lastAt <= 1000 && cursor > 0) {
            tapIdx = (tapIdx + 1) % set.length
            val ch = style(set[tapIdx], writeText.substring(0, cursor - 1))
            writeText = writeText.substring(0, cursor - 1) + ch + writeText.substring(cursor)
        } else {
            tapIdx = 0
            insert(style(set[0], writeText.substring(0, cursor)))
        }
        lastDigit = d; lastAt = now
    }

    private fun style(c: Char, before: String): String = when {
        !c.isLetter() -> c.toString()
        modeName.endsWith("ABC") -> c.uppercase()
        modeName.endsWith("Abc") && startsSentence(before) -> c.uppercase()
        else -> c.toString()
    }

    private fun insert(t: String) { writeText = writeText.substring(0, cursor) + t + writeText.substring(cursor); cursor += t.length }

    private fun openContacts(from: Screen): Action { contactsFrom = from; screen = Screen.CONTACTS; selected = 0; return Action.Redraw }

    private fun contactOpen(): Action = contacts.getOrNull(selected)?.let {
        if (picking) { picking = false; writeTo = it.numbers.firstOrNull().orEmpty(); screen = Screen.WRITE; writeField = 1; Action.Redraw }
        else { contact = it; screen = Screen.CONTACT; selected = 0; Action.Redraw }
    } ?: Action.None

    private fun contactList(key: Key, digit: Int): Action = move(key, contacts.size) ?: when (key) {
        Key.LEFT, Key.SOFT_LEFT -> if (contactsFrom == Screen.DIAL && !picking) { screen = Screen.DIAL; Action.Redraw } else contactOpen()
        Key.OK -> contactOpen()
        Key.RIGHT, Key.SOFT_RIGHT -> {
            when {
                picking -> { picking = false; screen = Screen.WRITE }
                contactsFrom == Screen.DIAL -> exitDialler()
                contactsFrom == Screen.MENU -> { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == contactsApp }.coerceAtLeast(0) }
                else -> goHome()
            }
            Action.Redraw
        }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun contactDetail(key: Key, digit: Int): Action {
        val c = contact ?: run { goHome(); return Action.Redraw }
        val count = c.numbers.size + 1
        return move(key, count) ?: when (key) {
            Key.OK, Key.LEFT, Key.SOFT_LEFT, Key.CALL ->
                if (key == Key.CALL && c.numbers.isNotEmpty()) Action.Call(c.numbers[selected.coerceAtMost(c.numbers.size - 1)])
                else if (selected < c.numbers.size) Action.Call(c.numbers[selected])
                else startWrite(c.numbers.firstOrNull() ?: "", Screen.CONTACT)
            Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.CONTACTS; selected = contacts.indexOf(c).coerceAtLeast(0); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun move(key: Key, size: Int): Action? = when (key) {
        Key.UP -> if (size == 0) Action.None else { selected = (selected - 1 + size) % size; Action.Redraw }
        Key.DOWN -> if (size == 0) Action.None else { selected = (selected + 1) % size; Action.Redraw }
        else -> null
    }

    private fun open(item: MenuItem?): Action = when {
        item == null -> Action.None
        item.pkg == LOGSET_PKG -> { screen = Screen.LOGSET; selected = 0; Action.Redraw }
        item.pkg == SETTINGS_PKG -> { settingsFrom = screen; screen = Screen.SETTINGS; selected = 0; Action.Redraw }
        item.pkg == contactsApp -> openContacts(Screen.MENU)
        item.pkg == messagesApp -> openMessages()
        item.pkg == dialerApp -> openPhoneMenu(Screen.MENU)
        item.pkg == CALC_PKG -> { calc.allClear(); screen = Screen.CALC; Action.Redraw }
        item.pkg == CALLLOG_PKG -> openCallLog(if (screen == Screen.LOGSET) Screen.LOGSET else Screen.MENU)
        item.pkg == CAMERA_PKG -> Action.OpenCamera
        item.pkg == MUSIC_PKG -> { musicFromHome = false; screen = Screen.MUSICMENU; selected = 0; Action.Redraw }
        item.pkg == NOTES_PKG -> { notesFrom = if (screen == Screen.LOGSET) Screen.LOGSET else Screen.MENU; screen = Screen.NOTES; selected = 0; Action.Redraw }
        item.pkg == WEATHER_PKG -> { weatherFrom = Screen.MENU; screen = Screen.WEATHER; Action.Redraw }
        item.pkg == GAMES_PKG -> { screen = Screen.GAMES; selected = 0; Action.Redraw }
        item.pkg == APPS_PKG -> { screen = Screen.APPS; selected = 0; Action.Redraw }
        else -> Action.Launch(item.pkg)
    }

    private fun settings(key: Key, digit: Int): Action = move(key, settingsRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            var out: Action = Action.Redraw
            when (settingsRows.getOrNull(selected)) {
                "Theme" -> { screen = Screen.THEME; selected = Themes.all.indexOfFirst { it.id == theme }.coerceAtLeast(0) }
                "Font" -> { screen = Screen.FONT; selected = Fonts.all.indexOfFirst { it.id == font }.coerceAtLeast(0) }
                "Predictive text" -> { screen = Screen.TYPING; selected = if (predictiveOn) 0 else 1 }
                "Speakerphone" -> { screen = Screen.SPEAKER; selected = if (speakerShown) 0 else 1 }
                "Sound test" -> { screen = Screen.SOUNDTEST; selected = 0 }
                "WhatsApp" -> { screen = Screen.WASET; selected = 0 }
                "Android settings" -> out = Action.Launch("com.android.settings")
                "Battery" -> { screen = Screen.BATTERY; selected = 0 }
            }
            out
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { if (settingsFrom == Screen.LOGSET) { screen = Screen.LOGSET; selected = logItems.indexOfFirst { it.pkg == SETTINGS_PKG }.coerceAtLeast(0) } else { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == SETTINGS_PKG }.coerceAtLeast(0) }; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun fonts(key: Key, digit: Int): Action = move(key, Fonts.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { font = Fonts.all[selected].id; screen = Screen.SETTINGS; selected = 1; Action.SaveFont(font) }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SETTINGS; selected = 1; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun themes(key: Key, digit: Int): Action = move(key, Themes.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { theme = Themes.all[selected].id; screen = Screen.SETTINGS; selected = 0; Action.SaveTheme(theme) }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SETTINGS; selected = 0; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    // The two labels at the bottom of the screen belong to the two keys under the screen (left key =
    // left label, right key = right label), like a real Nokia. D-pad Left and Right do the same.
    private fun home(key: Key, digit: Int): Action = when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { screen = Screen.MENU; selected = 0; Action.Redraw }
        Key.RIGHT, Key.SOFT_RIGHT -> if (items.any { it.pkg == MUSIC_PKG }) { musicFromHome = true; screen = Screen.MUSICMENU; selected = 0; Action.Redraw } else openContacts(Screen.HOME)
        Key.CALL -> openPhoneMenu(Screen.HOME)
        Key.DIGIT -> if (digit in 0..9) { dialNumber = digit.toString(); dialFrom = Screen.HOME; screen = Screen.DIAL; Action.Redraw } else Action.None
        Key.STAR -> { dialNumber = "*"; screen = Screen.DIAL; Action.Redraw }
        Key.POUND -> { dialNumber = "#"; screen = Screen.DIAL; Action.Redraw }
        else -> Action.None
    }

    private val gameNames = listOf("Snake", "Pairs", "Blaster", "Seeds")
    /** Which game the how-to-play page is for. */
    var helpGame = 0
        private set

    /** What to do and which keys to use, one short line each, shown before a game starts. */
    val helpLines: List<String> get() = when (helpGame) {
        0 -> listOf("Eat the squares to grow.", "Don't hit a wall or your", "own tail.", "", "Arrows or 2 4 6 8: steer", "OK, 5 or left key: pause")
        1 -> listOf("Turn over two cards at a", "time and find every pair", "in as few moves as you can.", "", "Arrows or 2 4 6 8: move", "OK or 5: turn a card")
        2 -> listOf("Shoot the enemies and dodge", "their fire. Each stage ends", "with a boss.", "", "Arrows or 2 4 6 8: fly", "1 3 7 9: fly diagonally", "Your gun fires by itself.", "Hold OK or 5, then let go:", "a piercing beam", "# or 0: pod out or back", "Fly into crates for a pod,", "spread, bounce or speed.", "Left key or *: pause")
        else -> listOf("Your pits are the bottom row,", "your store is on the right.", "Pick a pit and sow its seeds", "round the board. End in your", "store for another go; end in", "an empty pit of yours to take", "the seeds opposite. Most", "seeds wins.", "", "Left/Right then OK, or 1 to 6")
    }

    /** Screens that are a plain list of rows, where a tap on a row chooses it. */
    private val rowScreens = setOf(Screen.MENU, Screen.SETTINGS, Screen.THEME, Screen.FONT, Screen.CONTACTS, Screen.CONTACT, Screen.CALLLOG, Screen.INBOX, Screen.CHAT,
        Screen.APPS, Screen.GAMES, Screen.NOTES, Screen.MUSICMENU, Screen.PLAYLISTS, Screen.SEARCHRES, Screen.DEVICES, Screen.LOGSET, Screen.SOUNDTEST, Screen.WASET, Screen.WANAMES, Screen.TYPING, Screen.SPEAKER, Screen.MESSAGES, Screen.WHATSAPP, Screen.CALLMENU)

    private val noTouch = setOf(Screen.SNAKE, Screen.PAIRS, Screen.SHOOTER, Screen.SEEDS)

    private var listTop = 0
    private var listScreen: Screen? = null

    /** The first row on screen in a list [visible] rows tall: it moves only as far as needed to keep the chosen row in view, or when swiped. */
    fun listFirst(visible: Int): Int {
        if (screen != listScreen) { listScreen = screen; listTop = 0 }
        if (selected < listTop) listTop = selected else if (selected > listTop + visible - 1) listTop = selected - visible + 1
        listTop = listTop.coerceIn(0, (rows.size - visible).coerceAtLeast(0))
        return listTop
    }

    /** A swipe moved the list by this many rows (positive shows later rows). True if anything moved. */
    fun scrollBy(deltaRows: Int): Boolean {
        if (screen !in rowScreens || screen in noTouch) return false
        listFirst(LIST_VISIBLE)
        val n = (listTop + deltaRows).coerceIn(0, (rows.size - LIST_VISIBLE).coerceAtLeast(0))
        if (n == listTop) return false
        listTop = n
        selected = selected.coerceIn(listTop, (listTop + LIST_VISIBLE - 1).coerceAtMost((rows.size - 1).coerceAtLeast(0)))
        return true
    }

    /**
     * A touch, in the 480 by 640 design grid. Only reaches us where a mode allows touch (School blocks it). The two soft-key labels
     * at the bottom work as keys, a row in a list is chosen, and the weather on the home screen opens the forecast.
     */
    fun tap(x: Float, y: Float): Action {
        if (screen in noTouch) return Action.None // the games stay keypad classics
        if (y >= 576f) return onKey(if (x < 240f) Key.SOFT_LEFT else Key.SOFT_RIGHT)
        if (screen == Screen.HOME) {
            if (y in 345f..440f) return openWeather()
            if (y in 455f..565f && badges.isNotEmpty()) return openBadge(badges[badgeAt(x)])
            return Action.None
        }
        if (screen in rowScreens && y >= 104f) {
            val idx = listFirst(LIST_VISIBLE) + ((y - 104f) / 62f).toInt()
            if (idx in rows.indices) { selected = idx; return onKey(Key.OK) }
        }
        return Action.None
    }

    /** The centre of badge [i] of [n] on the home screen's 480 wide grid. */
    fun badgeX(i: Int, n: Int): Float = 240f + (i - (n - 1) / 2f) * 130f
    private fun badgeAt(x: Float): Int = badges.indices.minByOrNull { Math.abs(badgeX(it, badges.size) - x) } ?: 0

    /** Jump straight to what a home-screen symbol stands for: the text inbox, the WhatsApp chats or the call log. */
    private fun openBadge(b: Badge): Action = when (b.kind) {
        BadgeKind.SMS -> { homeEntry = true; threadWa = false; screen = Screen.INBOX; selected = convos.indexOfFirst { it.unread > 0 }.coerceAtLeast(0); Action.Redraw }
        BadgeKind.WHATSAPP -> { homeEntry = true; threadWa = true; screen = Screen.WHATSAPP; selected = waConvos.indexOfFirst { it.unread > 0 }.coerceAtLeast(0); if (waLinked) Action.WaOpen else Action.Redraw }
        BadgeKind.MISSED -> openCallLog(Screen.HOME)
        BadgeKind.NOTES -> { notesFrom = Screen.HOME; screen = Screen.NOTES; selected = 0; Action.Redraw }
    }

    /** A tap on the weather on the home screen (touch modes only: School blocks touches before they get here). */
    fun openWeather(): Action { if (screen != Screen.HOME || weather == null) return Action.None; weatherFrom = Screen.HOME; screen = Screen.WEATHER; return Action.Redraw }

    /** The numbers on the battery page; the activity fills them in while the page is open. */
    var battery: BatteryInfo? = null

    /** What the music screen shows; the activity refreshes it about once a second while that screen is open. */
    /** What "Logs & Settings" lists (Call log, Notifications, Settings); the activity fills it in. */
    var logItems: List<MenuItem> = emptyList()
    private var settingsFrom = Screen.MENU
    /** Where the Phone menu was opened from (Back returns there) and where the dial screen was opened from. */
    private var dialFrom = Screen.HOME
    private var musicFromHome = false
    var music: io.github.durkimat.phoneguard.music.MusicInfo = io.github.durkimat.phoneguard.music.MusicInfo.NONE
    var musicApp = false
    /** Spotify account state: signed in, the lists the screens show, and a short status line for an empty list. */
    var spSignedIn = false
    /** What is left of the sleep timer ("20 min"), or empty when it is off; set by the activity. */
    var sleepLabel = ""
    /** False until HA has sent a Spotify Client ID. */
    var spConfigured = true
    var spPlaylists: List<io.github.durkimat.phoneguard.music.SpItem> = emptyList()
    var spResults: List<io.github.durkimat.phoneguard.music.SpItem> = emptyList()
    var spDevices: List<io.github.durkimat.phoneguard.music.SpDevice> = emptyList()
    var spStatus = ""
    /** Tells whether a Spotify device is this phone (set by the activity, which knows the phone's name). */
    var spIsThisPhone: (io.github.durkimat.phoneguard.music.SpDevice) -> Boolean = { false }
    /** True while the text entry is a Spotify search rather than a message. */
    var writeSearch = false
        private set

    /** The notifications, newest first (the activity fills this in). */
    var notes: List<io.github.durkimat.phoneguard.notes.NoteItem> = emptyList()
    private var noteKeyShown: String? = null
    private var notesFrom = Screen.MENU

    private fun notesKey(key: Key): Action = move(key, notes.size + (if (notes.isEmpty()) 0 else 1)) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (notes.isEmpty()) Action.None else if (selected >= notes.size) Action.ClearNotes else { noteKeyShown = notes[selected].key; screen = Screen.NOTE; Action.Redraw }
        Key.RIGHT, Key.SOFT_RIGHT -> { if (notesFrom == Screen.HOME) goHome() else if (notesFrom == Screen.LOGSET) { screen = Screen.LOGSET; selected = logItems.indexOfFirst { it.pkg == NOTES_PKG }.coerceAtLeast(0) } else { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == NOTES_PKG }.coerceAtLeast(0) }; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** The note being read: OK opens its app (and clears it), 0 clears it, Back returns to the list. */
    val currentNote: io.github.durkimat.phoneguard.notes.NoteItem? get() = notes.firstOrNull { it.key == noteKeyShown }

    private fun noteDetailKey(key: Key, digit: Int): Action = when {
        key == Key.OK || key == Key.LEFT || key == Key.SOFT_LEFT -> currentNote?.let { Action.OpenNote(it.key) } ?: Action.None
        key == Key.DIGIT && digit == 0 -> currentNote?.let { screen = Screen.NOTES; selected = 0; Action.ClearNote(it.key) } ?: Action.None
        key == Key.RIGHT || key == Key.SOFT_RIGHT -> { screen = Screen.NOTES; Action.Redraw }
        key == Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /**
     * Music remote: OK, 5 or the left key play and pause; the D-pad Left and Right, and 4 and 6, go to the previous and next track;
     * Up and Down, and 2 and 8, change the volume; the right key under the screen goes back. With nothing playing OK opens the music app.
     */
    private fun musicKey(key: Key, digit: Int): Action {
        val d = if (key == Key.DIGIT) digit else -1
        return when {
            key == Key.OK || key == Key.SOFT_LEFT || d == 5 -> if (music.active) Action.MusicPlayPause else if (musicApp) Action.MusicOpenApp else Action.None
            key == Key.RIGHT || d == 6 -> if (music.active) Action.MusicNext else Action.None
            key == Key.LEFT || d == 4 -> if (music.active) Action.MusicPrevious else Action.None
            key == Key.UP || d == 2 -> Action.MusicVolume(true)
            key == Key.DOWN || d == 8 -> Action.MusicVolume(false)
            key == Key.SOFT_RIGHT -> { screen = Screen.MUSICMENU; selected = 0; Action.Redraw }
            d == 0 && spSignedIn -> { screen = Screen.DEVICES; selected = 0; spStatus = "Loading..."; Action.SpLoad("devices") }
            key == Key.STAR && spSignedIn -> Action.SpShuffle
            key == Key.POUND && spSignedIn -> Action.SpRepeat
            key == Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private val musicMenuRows: List<String> get() = listOf("Now playing", "Sleep timer: " + sleepLabel.ifEmpty { "off" }) + (if (spSignedIn) listOf("Playlists", "Search", "Play on...", "Sign out of Spotify") else if (spConfigured) listOf("Sign in to Spotify") else listOf("Spotify: set up in HA"))

    // the Phone entry and the green key open the dial pad (see the dialler notes in the backlog)
    private fun openPhoneMenu(from: Screen): Action { dialNumber = ""; dialFrom = from; screen = Screen.DIAL; return Action.Redraw }


    private fun soundTestKey(key: Key): Action = move(key, io.github.durkimat.phoneguard.SoundTest.routes.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> Action.SoundTest(selected)
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SETTINGS; selected = settingsRows.indexOf("Sound test").coerceAtLeast(0); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun logSetKey(key: Key): Action = move(key, logItems.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> open(logItems.getOrNull(selected))
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == LOGSET_PKG }.coerceAtLeast(0); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun musicMenuKey(key: Key): Action = move(key, musicMenuRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (musicMenuRows.getOrNull(selected)) {
            "Now playing" -> { screen = Screen.MUSIC; Action.Redraw }
            "Sleep timer: ${sleepLabel.ifEmpty { "off" }}" -> Action.SleepStep
            "Playlists" -> { screen = Screen.PLAYLISTS; selected = 0; spStatus = "Loading..."; Action.SpLoad("playlists") }
            "Search" -> startSearch()
            "Play on..." -> { screen = Screen.DEVICES; selected = 0; spStatus = "Loading..."; Action.SpLoad("devices") }
            "Sign out of Spotify" -> Action.SpSignOut
            "Sign in to Spotify" -> Action.SpSignIn
            else -> Action.None
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { if (musicFromHome) goHome() else { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == MUSIC_PKG }.coerceAtLeast(0) }; musicFromHome = false; Action.Redraw }
        Key.END -> { musicFromHome = false; goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun startSearch(): Action { startWrite("", Screen.MUSICMENU); writeField = 1; writeSearch = true; return Action.Redraw }

    /** The Spotify lists: OK plays the playlist or result, or moves playback to the device. */
    private fun spListKey(key: Key): Action {
        val size = when (screen) { Screen.PLAYLISTS -> spPlaylists.size; Screen.SEARCHRES -> spResults.size; else -> spDevices.size }
        return move(key, size) ?: when (key) {
            Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (screen) {
                Screen.PLAYLISTS -> spPlaylists.getOrNull(selected)?.let { screen = Screen.MUSIC; Action.SpPlay(it.uri) } ?: Action.None
                Screen.SEARCHRES -> spResults.getOrNull(selected)?.let { screen = Screen.MUSIC; Action.SpPlay(it.uri) } ?: Action.None
                else -> spDevices.getOrNull(selected)?.let { screen = Screen.MUSIC; Action.SpTransfer(it.id, it.name) } ?: Action.None
            }
            Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MUSICMENU; selected = 0; Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun batteryKey(key: Key): Action {
        when (key) {
            Key.RIGHT, Key.SOFT_RIGHT, Key.OK, Key.LEFT, Key.SOFT_LEFT -> { screen = Screen.SETTINGS; selected = settingsRows.size - 1 }
            Key.END -> goHome()
            else -> return Action.None
        }
        return Action.Redraw
    }

    private fun weatherKey(key: Key): Action {
        when (key) {
            Key.RIGHT, Key.SOFT_RIGHT, Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (weatherFrom == Screen.HOME) goHome() else { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == WEATHER_PKG }.coerceAtLeast(0) }
            Key.END -> goHome()
            else -> return Action.None
        }
        return Action.Redraw
    }

    private fun gameHelp(key: Key, digit: Int): Action {
        when (key) {
            Key.OK, Key.LEFT, Key.SOFT_LEFT -> startGame(helpGame)
            Key.DIGIT -> if (digit == 5) startGame(helpGame) else return Action.None
            Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.GAMES; selected = helpGame }
            Key.END -> goHome()
            else -> return Action.None
        }
        return Action.Redraw
    }

    private fun startGame(n: Int) {
        when (n) {
            0 -> { snake.reset(); screen = Screen.SNAKE }
            1 -> { pairs.reset(); screen = Screen.PAIRS }
            2 -> { shooter.reset(); screen = Screen.SHOOTER }
            else -> { seeds.reset(); screen = Screen.SEEDS }
        }
    }

    private fun gameList(key: Key): Action = move(key, 4) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { helpGame = selected; screen = Screen.GAMEHELP; Action.Redraw }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == GAMES_PKG }.coerceAtLeast(0); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** Arrows or 2, 4, 6, 8 steer; OK or the left key starts and pauses; the right key leaves. */
    private fun snakeKey(key: Key, digit: Int): Action {
        when (key) {
            Key.UP -> snake.steer(0)
            Key.RIGHT -> snake.steer(1)
            Key.DOWN -> snake.steer(2)
            Key.LEFT -> snake.steer(3)
            Key.DIGIT -> when (digit) { 2 -> snake.steer(0); 6 -> snake.steer(1); 8 -> snake.steer(2); 4 -> snake.steer(3); 5 -> snake.toggle() }
            Key.OK, Key.SOFT_LEFT -> snake.toggle()
            Key.SOFT_RIGHT -> { snake.reset(); screen = Screen.GAMES; selected = 0 }
            Key.END -> { snake.reset(); goHome() }
            else -> return Action.None
        }
        if (snake.score > snakeBest) snakeBest = snake.score
        return Action.Redraw
    }

    private fun pairsKey(key: Key, digit: Int): Action {
        when (key) {
            Key.UP -> pairs.move(0, -1); Key.DOWN -> pairs.move(0, 1); Key.LEFT -> pairs.move(-1, 0); Key.RIGHT -> pairs.move(1, 0)
            Key.DIGIT -> when (digit) { 2 -> pairs.move(0, -1); 8 -> pairs.move(0, 1); 4 -> pairs.move(-1, 0); 6 -> pairs.move(1, 0); 5 -> pairsPick() }
            Key.OK, Key.SOFT_LEFT -> if (pairs.won) pairs.reset() else pairsPick()
            Key.SOFT_RIGHT -> { screen = Screen.GAMES; selected = 1 }
            Key.END -> goHome()
            else -> return Action.None
        }
        return Action.Redraw
    }

    private fun pairsPick() { pairs.pick(); if (pairs.won && (pairsBest == 0 || pairs.moves < pairsBest)) pairsBest = pairs.moves }

    /** The directions a key means in the blaster: the arrows and 2 4 6 8, and 1 3 7 9 for the diagonals. */
    private fun shotDirs(key: Key, digit: Int): List<Int> = when (key) {
        Key.UP -> listOf(0); Key.RIGHT -> listOf(1); Key.DOWN -> listOf(2); Key.LEFT -> listOf(3)
        Key.DIGIT -> when (digit) { 2 -> listOf(0); 6 -> listOf(1); 8 -> listOf(2); 4 -> listOf(3); 1 -> listOf(0, 3); 3 -> listOf(0, 1); 7 -> listOf(2, 3); 9 -> listOf(2, 1); else -> emptyList() }
        else -> emptyList()
    }

    private fun shooterKey(key: Key, digit: Int): Action {
        val dirs = shotDirs(key, digit)
        if (dirs.isNotEmpty()) {
            if (shooter.state == Shooter.State.READY) shooter.toggle()
            dirs.forEach { shooter.hold(it, true) }
            return Action.Redraw
        }
        when {
            key == Key.OK || (key == Key.DIGIT && digit == 5) -> shooter.fireDown()
            key == Key.POUND || (key == Key.DIGIT && digit == 0) -> shooter.podToggle()
            key == Key.SOFT_LEFT || key == Key.STAR -> shooter.toggle()
            key == Key.SOFT_RIGHT -> { shooter.reset(); screen = Screen.GAMES; selected = 2 }
            key == Key.END -> { shooter.reset(); goHome() }
            else -> return Action.None
        }
        return Action.Redraw
    }

    /** A key came up: the blaster stops moving that way, or lets a charged beam go. */
    fun onRelease(key: Key, digit: Int): Action {
        if (screen != Screen.SHOOTER) return Action.None
        val dirs = shotDirs(key, digit)
        if (dirs.isNotEmpty()) { dirs.forEach { shooter.hold(it, false) }; return Action.None }
        if (key == Key.OK || (key == Key.DIGIT && digit == 5)) shooter.fireUp()
        return Action.None
    }

    private fun seedsKey(key: Key, digit: Int): Action {
        when (key) {
            Key.LEFT -> seeds.moveCursor(-1); Key.RIGHT -> seeds.moveCursor(1)
            Key.DIGIT -> if (digit in 1..6) { seeds.select(digit - 1); if (!seeds.over) seeds.play() }
            Key.OK, Key.SOFT_LEFT -> if (seeds.over) seeds.reset() else seeds.play()
            Key.SOFT_RIGHT -> { screen = Screen.GAMES; selected = 3 }
            Key.END -> goHome()
            else -> return Action.None
        }
        return Action.Redraw
    }

    /** One step of whatever game is on screen; the activity calls it on a timer while there is something to step. */
    fun gameTick(): Action {
        when (screen) {
            Screen.SNAKE -> { if (snake.state != Snake.State.RUNNING) return Action.None; snake.step(); if (snake.score > snakeBest) snakeBest = snake.score }
            Screen.PAIRS -> { if (!pairs.waiting) return Action.None; pairs.resolve() }
            Screen.SHOOTER -> { if (shooter.state != Shooter.State.RUNNING) return Action.None; shooter.tick(); if (shooter.score > shooterBest) shooterBest = shooter.score }
            Screen.SEEDS -> { if (!seeds.phoneToMove) return Action.None; seeds.phoneMove() }
            else -> return Action.None
        }
        return Action.Redraw
    }

    /** How long until the game on screen next needs a tick, or null when it is waiting for a key. */
    fun gameDelayMs(): Long? = when (screen) {
        Screen.SNAKE -> if (snake.state == Snake.State.RUNNING) snake.stepMs else null
        Screen.PAIRS -> if (pairs.waiting) 900L else null
        Screen.SHOOTER -> if (shooter.state == Shooter.State.RUNNING) shooter.tickMs else null
        Screen.SEEDS -> if (seeds.phoneToMove) 800L else null
        else -> null
    }

    private fun appList(key: Key): Action = move(key, apps.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> apps.getOrNull(selected)?.let { Action.Launch(it.pkg) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == APPS_PKG }.coerceAtLeast(0); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun menu(key: Key, digit: Int): Action = when (key) {
        Key.UP -> if (items.isEmpty()) Action.None else { selected = (selected - 1 + items.size) % items.size; Action.Redraw }
        Key.DOWN -> if (items.isEmpty()) Action.None else { selected = (selected + 1) % items.size; Action.Redraw }
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> open(items.getOrNull(selected))
        Key.RIGHT, Key.SOFT_RIGHT, Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> openCallLog(Screen.MENU)
        // 1 to 9 jump straight to that entry, like a Nokia menu
        Key.DIGIT -> { val i = if (digit == 0) 9 else digit - 1; items.getOrNull(i)?.let { selected = i; open(it) } ?: Action.None }
        else -> Action.None
    }
}

enum class BadgeKind { SMS, WHATSAPP, MISSED, NOTES }
data class Badge(val kind: BadgeKind, val count: Int)

/** The battery page: level, charging, a rough time left, today's screen time and the apps that used it most. */
data class BatteryInfo(val level: Int, val charging: Boolean, val hoursLeft: Double?, val screenMinutes: Int, val top: List<Pair<String, Int>>)

/** What the home screen shows besides the menu. Plain data so a skin can draw it any way it likes. */
data class HomeInfo(
    val time: String,
    val date: String,
    val modeLine: String,
    /** What wants attention, drawn as small symbols with a count: new texts, new WhatsApp messages, missed calls. */
    val badges: List<Badge>,
    val batteryPct: Int,
    val charging: Boolean,
    /** -1 means no SIM, otherwise 0 to 4 bars. */
    val signalBars: Int,
    val softLeft: String,
    val softRight: String,
    /** Wi-Fi: -1 not shown (the mode hides it), 0 not connected (drawn greyed), 1 connected. */
    val wifi: Int = -1,
)

/** A way of drawing the launcher. The engine never knows which one is in use. */
interface LauncherSkin {
    val id: String
    fun create(context: android.content.Context): android.view.View
    fun render(model: LauncherModel, info: HomeInfo)
}
