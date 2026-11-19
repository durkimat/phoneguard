package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.alarm.Alarm
import io.github.durkimat.phoneguard.alarm.AlarmMath
import io.github.durkimat.phoneguard.alarm.ClockPrefs
import io.github.durkimat.phoneguard.alarm.Stopwatch
import io.github.durkimat.phoneguard.alarm.TimerState
import io.github.durkimat.phoneguard.alarm.Tones

import io.github.durkimat.phoneguard.core.OpenConfig
import io.github.durkimat.phoneguard.calendar.AgendaItem
import io.github.durkimat.phoneguard.calendar.Cal
import io.github.durkimat.phoneguard.calendar.CalEvent
import io.github.durkimat.phoneguard.calendar.CalInfo
import io.github.durkimat.phoneguard.mail.MailAccount
import io.github.durkimat.phoneguard.mail.MailHeader
import io.github.durkimat.phoneguard.mail.Presets as MailPresets

/**
 * The launcher's behaviour, with no drawing and no Android classes, so it is unit tested and any skin
 * can sit on top of it. A skin shows [screen], [items] and [selected], and tells the model which key
 * was pressed. The model says what should happen next.
 */
/** A music provider: each has its own Now playing screen and menu. */
enum class Provider { RADIO, SPOTIFY }

enum class Screen { HOME, MENU, SETTINGS, THEME, FONT, MENUSTYLE, APPEARANCE, CHOICE, CONTACTS, CONTACT, MESSAGES, INBOX, THREAD, WRITE, TYPING, CALC, CALLLOG, DIAL, CALL, CALLMENU, SPEAKER, WHATSAPP, WAPAIR, WASET, WASETUP, WAREMOVE, CHAT, WANAMES, APPS, GAMES, SNAKE, PAIRS, SHOOTER, SEEDS, GAMEHELP, WEATHER, BATTERY, NOTES, NOTE, MUSIC, MUSICMENU, PLAYLISTS, DEVICES, SEARCHRES, SOUNDTEST, RUNNING, WIFI, WIFINET, BT, BTDEV, RADIO, RADIOBROWSE, RADIORES, CONNECTIONS, RADIOMENU, SPOTIFYMENU, CALENDAR, CALMENU, CALMONTH, EVENT, EVMENU, FORM, MAILBOX, MAILLIST, MAILMSG, MAILMENU, FIRSTRUN, PHONETYPE, OPENOPTS, TIMEZONE, LANGUAGE, DATA, LOOK, SOUNDSET, THISPHONE, WHEREPLACES, WHEREDETAIL, WHEREJOURNEYS, WHERELEGS, WHEREMAP, DATAAPPS, CLOCK, ALARMS, TIMER, STOPWATCH, RING }

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
/**
 * A colour set. `skin` picks the drawing style (a plain Nokia screen, or the classic-Mac desktop with windows); `desk` and `desk2`
 * are the two dither colours behind the windows, `accent` is the colour of held spectrum peaks, and `font` is the typeface a
 * theme prefers until the user picks one.
 */
data class Theme(
    val id: String, val name: String, val lcd: Int, val ink: Int,
    val skin: String = "nokia", val desk: Int = lcd, val desk2: Int = lcd, val accent: Int = ink, val font: String? = null,
)

object Themes {
    val all = listOf(
        Theme("green", "Green", 0xFF9DC25A.toInt(), 0xFF1A2A08.toInt()),
        Theme("grey", "Grey", 0xFFCFD3CC.toInt(), 0xFF1A1A1A.toInt()),
        Theme("amber", "Amber", 0xFF1A1200.toInt(), 0xFFFFB52E.toInt()),
        Theme("orange", "Orange", 0xFFF2A33A.toInt(), 0xFF3A1A00.toInt()),
        Theme("mac", "Classic Mac", 0xFFFAFAF5.toInt(), 0xFF111111.toInt(), "mac", 0xFF8E8E8E.toInt(), 0xFFCFCFCF.toInt(), 0xFFD42A1E.toInt(), "pixelify"),
        Theme("macdark", "Classic Mac dark", 0xFF222226.toInt(), 0xFFEDEDE6.toInt(), "mac", 0xFF050505.toInt(), 0xFF2E2E34.toInt(), 0xFFFF5A4A.toInt(), "pixelify"),
    )
    const val DEFAULT = "green"
    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }
}

/** How the main menu is drawn: a plain list, or a grid of pixel icons with the chosen name underneath. */
data class MenuStyle(val id: String, val name: String)

object MenuStyles {
    val all = listOf(MenuStyle("list", "List"), MenuStyle("grid", "Grid"), MenuStyle("text", "List, no icons"))
    const val DEFAULT = "list"
}

/** How a screen's entries are laid out. The model tells every skin, so key handling and drawing always agree. */
enum class Layout { LIST, GRID, CUSTOM }
/** How far the home screen's data line moves down in the Mac look (design pixels): its title bar and frame take the top. */
const val DATA_MAC_SHIFT = 46f
/** Where the data strip's rows start: [DATA_MAC_SHIFT] lower in the Mac look; the other looks sit 5 px lower so it is centred between the status line and the weather band. */
fun dataY(mac: Boolean) = if (mac) DATA_MAC_SHIFT else 5f
/** The home screen's weather group: its centre line (design pixels), above the clock in every look (the Mac look's clock sits 26 px lower to make room). */
fun weatherCy(mac: Boolean) = if (mac) 174f else 130f
/** The clock's baseline. */
fun clockY(mac: Boolean) = if (mac) 296f else 270f
/** The slot for Now playing, the next event and the rain note: where the weather used to be, the same in every look. */
fun homeSlotY(@Suppress("UNUSED_PARAMETER") mac: Boolean) = 392f
const val GRID_COLS = 3
/** Rows of tiles on screen at once; more rows scroll vertically. */
const val GRID_ROWS = 3
// The grid on the 480 by 640 design grid: where it starts and how big a tile is.
const val GRID_LEFT = 10f
const val GRID_TOP = 108f
const val GRID_TILE_W = 148f
const val GRID_TILE_H = 128f
const val GRID_GAP = 8f

/** The entry the launcher adds to its own menu; it opens the launcher's Settings, not an app. */
const val SETTINGS_PKG = "pg:settings"
const val CAMERA_PKG = "pg:camera"
const val MUSIC_PKG = "pg:music"
const val WHERE_PKG = "pg:where"
const val NOTES_PKG = "pg:notes"
const val CALENDAR_PKG = "pg:calendar"
const val CLOCK_PKG = "pg:clock"
const val MAIL_PKG = "pg:mail"
const val RUNNING_PKG = "pg:running"
val CAMERA_APPS = setOf("org.lineageos.aperture", "com.android.camera2", "org.lineageos.snap", "com.android.camera")
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

/** An app that looks to be running in the background, with a short state ("playing", "paused", "busy" or minutes since use). */
data class RunningApp(val pkg: String, val label: String, val state: String)

/** A Wi-Fi network, saved (id >= 0) and/or in range; level 0 to 4 when in range, -1 when not. */
data class WifiNet(val ssid: String, val id: Int, val saved: Boolean, val secured: Boolean, val level: Int, val connected: Boolean, val inRange: Boolean, val caps: String)

/** A Bluetooth device: paired or just found, and whether it is connected now. */
data class BtDev(val address: String, val name: String, val bonded: Boolean, val connected: Boolean, val kind: Int = 0)

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
    data class SaveMenuStyle(val id: String) : Action()
    /** An Appearance setting was chosen: [key] is one of [Choices.keys], [id] empty for "same as Font". */
    data class SaveOption(val key: String, val id: String) : Action()
    /** First-run: the person chose the Open phone. */
    object ChooseOpen : Action()
    /** First-run: the person chose the Family phone (starts the pairing screen). */
    object StartFamily : Action()
    data class SetTimeZone(val id: String) : Action()
    /** Screen lock: set or change the PIN (current is empty when none was set), remove it, ask Android to confirm the PIN once for parent control, or open Android's own set-PIN screen (an Open phone). */
    data class SetLockPin(val current: String, val pin: String) : Action()
    data class RemoveLockPin(val current: String) : Action()
    object ConfirmLockForParent : Action()
    object LockLoad : Action()
    /** Mobile data plan: the activity fills in the plan, the SIMs and the renewal date; or saves what the form collected. */
    object DataLoad : Action()
    object DataByApp : Action()
    data class SaveDataPlan(val plan: io.github.durkimat.phoneguard.core.DataPlan) : Action()
    object OpenAndroidLock : Action()
    data class SetLanguage(val tag: String) : Action()
    /** Factory reset behind "switch the phone type". */
    object EraseAndRestart : Action()
    /** An Open phone's own option (sound, notifications, ...). */
    data class SaveOpenOption(val key: String, val value: String) : Action()
    data class SavePreset(val id: String) : Action()
    data class SavePredictive(val on: Boolean) : Action()
    data class SaveSpeaker(val shown: Boolean) : Action()
    /** Start linking WhatsApp: this is the account's phone number (digits, with country code). */
    data class PairWa(val number: String) : Action()
    /** The setup window (SIM method): start it, open the official app inside it, end it. */
    object SetupStart : Action()
    object SetupOpenOfficial : Action()
    object SetupEnd : Action()
    object UninstallOfficial : Action()
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
    /** A text conversation was opened: its messages count as read (the phone's own read flag is not ours to write). */
    data class SmsRead(val address: String) : Action()
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
    /** The ring screen: snooze the alarm (nine minutes) or stop it. */
    object AlarmSnooze : Action()
    object AlarmStop : Action()
    /** Open the themed camera. */
    object OpenCamera : Action()
    object MusicPlayPause : Action()
    object MusicNext : Action()
    object MusicPrevious : Action()
    data class MusicVolume(val up: Boolean) : Action()
    object SpSignIn : Action()
    object SpSignOut : Action()
    data class SpLoad(val kind: String) : Action()
    data class SpSearch(val query: String) : Action()
    data class SpPlay(val uri: String) : Action()
    /** A playlist was pinned or unpinned on the Playlists screen: the phone remembers it. */
    data class SpPin(val uri: String, val pinned: Boolean) : Action()
    /** "Navigate": look a place up, or plan the way there. */
    data class WhereSearch(val query: String) : Action()
    /** A kind of place was asked for with no position: the town the child named is found, then the kind of place around it. */
    data class WhereTown(val town: String, val query: String) : Action()
    data class WhereRoute(val to: io.github.durkimat.phoneguard.where.Place, val time: String? = null) : Action()
    /** The `#` key on a playlist: download it for offline listening, or remove the download. */
    data class SpOffline(val uri: String, val name: String, val remove: Boolean) : Action()
    /** Play radio preset n (0 is the first). */
    object DataToggle : Action()
    /** Hold a digit on radio Now playing: the playing station becomes that preset (1 to 9). */
    data class RadioStore(val slot: Int) : Action()
    data class RadioPlay(val index: Int) : Action()
    data class RadioPlayUrl(val name: String, val url: String) : Action()
    data class RadioLoad(val kind: String, val arg: String) : Action()
    data class RadioSearch(val query: String) : Action()
    /** Add a station to the child's own list, or take it off. */
    data class RadioFav(val name: String, val url: String) : Action()
    object RadioFavCurrent : Action()
    data class SpTransfer(val id: String, val name: String) : Action()
    object SpShuffle : Action()
    object SpRepeat : Action()
    /** Stop a running app (the Running Apps screen). */
    data class KillApp(val pkg: String) : Action()
    /** Wi-Fi and Bluetooth (the activity does the work and refreshes the lists). */
    object WifiLoad : Action()
    object WifiToggle : Action()
    data class WifiConnect(val id: Int) : Action()
    data class WifiForget(val id: Int) : Action()
    data class WifiJoin(val ssid: String, val password: String, val caps: String, val id: Int) : Action()
    object BtLoad : Action()
    object BtSearch : Action()
    object BtToggle : Action()
    data class BtPair(val address: String) : Action()
    data class BtConnect(val address: String, val connect: Boolean) : Action()
    data class BtForget(val address: String) : Action()
    /** Step the sleep timer: off, 15, 30, 60 minutes. */
    object SleepStep : Action()
    object SpectrumToggle : Action()
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
    /** Load the calendar's events for this window (epoch ms), save one, or delete one (the activity does it and refills the model). */
    data class CalLoad(val from: Long, val to: Long) : Action()
    data class CalSave(val event: CalEvent) : Action()
    data class CalDelete(val id: Long) : Action()
    /** Mail: check the server (force: even if checked lately), fetch a message's text, send, mark read or unread, delete, sign in with a new account, forget the account. */
    data class MailSync(val force: Boolean) : Action()
    data class MailFetch(val sent: Boolean, val uid: Long) : Action()
    data class MailSend(val to: List<String>, val subject: String, val body: String) : Action()
    data class MailSeen(val uid: Long, val seen: Boolean) : Action()
    data class MailDelete(val sent: Boolean, val uid: Long) : Action()
    data class MailSetup(val account: MailAccount, val password: String) : Action()
    object MailRemove : Action()
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

    /** One step of the way back: the screen to return to and the row that was selected on it. */
    private data class Frame(val screen: Screen, val selected: Int)
    private val trail = ArrayDeque<Frame>()
    /** How many screens sit behind this one (a stacked-window skin draws a close box when there is something to close back to). */
    val depth: Int get() = trail.size
    /** Opens [to] (on row [row]) on top of the current screen, remembering where we are so Back comes back to the same row. */
    private fun enter(to: Screen, row: Int = 0) {
        remember()
        trail.addLast(Frame(screen, selected)); screen = to; selected = row
        if (row == 0 && to in rememberRow) selected = (positions[to] ?: 0).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    }
    /** The lists that open again where the person left them (until the phone goes home): the menu, Settings and its groups. */
    private val rememberRow = setOf(Screen.MENU, Screen.SETTINGS, Screen.LOOK, Screen.SOUNDSET, Screen.CONNECTIONS, Screen.THISPHONE)
    private val positions = HashMap<Screen, Int>()
    private fun remember() { if (screen in rememberRow) positions[screen] = selected }
    /** Back to a screen further down the trail, dropping its own frame and everything above it; with no such frame it is simply shown. */
    private fun leaveTo(target: Screen, row: Int) {
        val i = trail.indexOfLast { it.screen == target }
        if (i >= 0) while (trail.size > i) trail.removeLast()
        screen = target; selected = row
    }
    /** Back: returns to the screen below and the row it was on, or home when there is nothing below. */
    private fun leave() { onPreviewStop(); remember(); val f = trail.removeLastOrNull(); if (f == null || f.screen == Screen.HOME) goHome() else { screen = f.screen; selected = f.selected } }

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
    /** [MenuStyles]: the main menu as a list or as a grid of icons. */
    var menuStyle: String = MenuStyles.DEFAULT
    /** An accent colour a parent set, replacing the theme's (null: the theme's own). */
    var accent: Int? = null
    /** Window frame, colour, wallpaper, shadow, analyser and the three typefaces (see [Style]). */
    var style: Style = Style()
    private var choiceKey = ""

    /** The phone's contacts, sorted by name. The activity fills this in. */
    var contacts: List<Contact> = emptyList()
    private var contact: Contact? = null
    /** Inside the dialler: the dial pad, the calls and the contacts, which Left cycles through and Back leaves together. */
    private var dialler = false

    /** Whether the Settings list offers Android's own settings (only where touch works: Android's screens are made for it). */
    var androidSettings = false
    /** The Settings groups and what is in each, every one numbered 1.. in the order shown (rows come and go with the mode and the edition). */
    private fun groupRows(on: Screen): List<String> = when (on) {
        Screen.LOOK -> listOf("Theme", "Font", "Menu style", "Appearance")
        Screen.SOUNDSET -> listOf("Ringer mode", "Volumes", "Predictive text", "Speakerphone", "Ringtone and message tones", "Sound test")
        Screen.THISPHONE -> listOf("Screen lock", "Time zone", "Language") + (if (edition == "open") listOf("Phone options") else emptyList()) + (if (edition.isNotEmpty()) listOf("Phone type") else emptyList()) +
            (if (waEnabled || waSetupAvail || waSetupActive) listOf("WhatsApp") else emptyList()) + (if (androidSettings) listOf("Android settings") else emptyList())
        else -> listOf("Look", "Sound and typing", "Connections", "This phone") + extraItems.filter { it.pkg == NOTES_PKG }.map { "Notifications" } + extraItems.filter { it.pkg == RUNNING_PKG }.map { "Running Apps" }
    }

    // ---- Messages state
    /** Conversations, newest first. The activity fills this in. */
    var convos: List<Convo> = emptyList()
    /** WhatsApp: whether the account is linked, the chats (newest first), the count unread and the bridge's state. */
    var waEnabled = false
    /** SIM method: the companion allowed the setup window ([waSetupAvail]) or it is running ([waSetupActive]); whether the official WhatsApp is installed; this phone's own number if Android tells us. */
    var waSetupAvail = false
    var waSetupActive = false
    var waOfficial = false
    /** The companion chose the SIM method (the official app is the account). */
    var waSim = false
    var waSimNumber = ""
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
        "history", "history_sync" -> "Getting your chats..."
        "idle" -> "Idle: updates every 30 min or when opened"
        "connecting" -> "Connecting..."
        "not linked" -> "Not linked yet"
        "paired" -> "Linked"
        "logged_out" -> "Link was removed"
        "error" -> "Not available"
        else -> if (waStatus.startsWith("app state synced")) "" else "Not connected"
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
        if (waEnabled) listOf("Inbox", "Write message", "WhatsApp" + if (waLinked && waUnread > 0) " ($waUnread)" else "") else listOf("Inbox", "Write message")

    var writeTo = ""
        private set
    var writeText = ""
        private set
    var cursor = 0
        private set
    /** 0 is the To field, 1 the message, 2 the microphone button (only while [voiceOn]). */
    var writeField = 1
        private set
    /** True when speaking into a text field is possible on this phone (set by the activity); the microphone button shows only then. */
    var voiceOn = false
    /** 0 idle, 1 listening (key held), 2 sending the recording; and how loud it is (0 to 4). Set by the activity. */
    var voiceState = 0
    var voiceLevel = 0
    /** The microphone button shows only where speaking is allowed and never in a password field. */
    val voiceShown: Boolean get() = voiceOn && writeWifi == null
    /** Put dictated words into the message at the cursor and go back to the text. */
    fun voiceInsert(said: String) {
        val r = io.github.durkimat.phoneguard.voice.VoiceText.insert(writeText, cursor, said)
        commit(); writeText = r.first; cursor = r.second; lastDigit = -1; writeField = 1
    }
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
        if (exactTyping) listOf("abc", "ABC", "123") // a password is typed exactly: no word prediction, no capital after a full stop
        else if (predictor != null && predictiveOn) listOf("T9 Abc", "T9 abc", "T9 ABC", "Abc", "abc", "ABC", "123") else listOf("Abc", "abc", "ABC", "123")
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

    fun goHome() { onPreviewStop(); screen = Screen.HOME; selected = 0; dialler = false; trail.clear(); homeFocus = -1; positions.clear() }

    val title: String get() = when (screen) {
        Screen.SETTINGS -> "Settings"
        Screen.THEME -> "Theme"
        Screen.FONT -> "Font"
        Screen.MENUSTYLE -> "Menu style"
        Screen.APPEARANCE -> "Appearance"
        Screen.CHOICE -> Choices.title(choiceKey)
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
        Screen.WASETUP -> "WhatsApp setup"
        Screen.WAREMOVE -> "Remove WhatsApp"
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
        Screen.MUSIC -> if (provider == Provider.RADIO) "Internet Radio" else "Spotify"
        Screen.RADIO -> "Presets"
        Screen.RADIOMENU -> "Radio menu"
        Screen.SPOTIFYMENU -> "Spotify menu"
        Screen.RADIOBROWSE -> "Browse"
        Screen.CONNECTIONS -> "Connections"
        Screen.FIRSTRUN -> if (firstConfirm != null) "Are you sure?" else when (wizStep) { 0 -> "1 of 3: Wi-Fi"; 1 -> "2 of 3: Time, language"; else -> "3 of 3: Phone type" }
        Screen.DATA -> "Mobile data"
        Screen.DATAAPPS -> "Data by app"
        Screen.TIMEZONE -> "Time zone"
        Screen.LANGUAGE -> "Language"
        Screen.PHONETYPE -> "Phone type"
        Screen.OPENOPTS -> "Phone options"
        Screen.RADIORES -> radioResTitle
        Screen.LOOK -> "Look"
        Screen.SOUNDSET -> "Sound and typing"
        Screen.THISPHONE -> "This phone"
        Screen.RUNNING -> "Running Apps"
        Screen.WIFI -> "Wi-Fi"
        Screen.WIFINET -> wifiSel?.ssid ?: "Wi-Fi"
        Screen.BT -> "Bluetooth"
        Screen.BTDEV -> btSel?.name ?: "Bluetooth"
        Screen.SOUNDTEST -> "Sound test"
        Screen.MUSICMENU -> "Music"
        Screen.PLAYLISTS -> if (spOfflineOnly) "Offline playlists" else "Playlists"
        Screen.WHEREPLACES -> "Navigate"
        Screen.WHEREDETAIL -> wherePlace?.name.orEmpty()
        Screen.WHEREJOURNEYS -> (if (whereFrom.isNotEmpty()) "From $whereFrom to " else "To ") + (wherePlace?.name ?: "")
        Screen.WHERELEGS -> whereJourney?.let { io.github.durkimat.phoneguard.where.Where.liveTag(it) } ?: "Walk"
        Screen.WHEREMAP -> "Map"
        Screen.CLOCK -> "Clock"
        Screen.ALARMS -> "Alarms"
        Screen.TIMER -> "Timer"
        Screen.STOPWATCH -> "Stopwatch"
        Screen.RING -> if (ringKind == "timer") "Timer" else "Alarm"
        Screen.DEVICES -> "Play on"
        Screen.SEARCHRES -> "Results"
        Screen.NOTES -> "Notifications"
        Screen.NOTE -> "Notification"
        Screen.CALC -> "Calculator"
        Screen.CALLLOG -> "Call log"
        Screen.DIAL -> "Call"
        Screen.CALLMENU -> "Options"
        Screen.CALL -> "Call"
        Screen.CALENDAR -> "Calendar"
        Screen.CALMENU, Screen.EVMENU -> "Options"
        Screen.CALMONTH -> Cal.monthName(calDay.monthValue) + " " + calDay.year
        Screen.EVENT -> "Event"
        Screen.FORM -> form?.title ?: "Edit"
        Screen.MAILBOX -> "Email"
        Screen.MAILLIST -> if (mailSentBox) "Sent" else "Inbox"
        Screen.MAILMSG -> "Message"
        Screen.MAILMENU -> "Options"
        else -> "Menu"
    }

    /** The two labels under the screen, which the two keys beneath it and D-pad Left/Right carry out. */
    val softKeys: Pair<String, String> get() = when (screen) {
        Screen.HOME -> if (homeFocus >= 0) "Select" to "Back" else "Menu" to (if (items.any { it.pkg == MUSIC_PKG }) "Music" else "Contacts")
        Screen.CALC -> "Equals" to "Clear"
        Screen.CALLLOG -> (if (dialler) "Contacts" else "Call") to "Back"
        Screen.CONTACTS -> (if (dialler && !picking) "Dial" else "Select") to "Back"
        Screen.SNAKE -> (when (snake.state) { Snake.State.READY -> "Start"; Snake.State.RUNNING -> "Pause"; Snake.State.PAUSED -> "Resume"; Snake.State.OVER -> "Again" }) to "Back"
        Screen.SHOOTER -> (when (shooter.state) { Shooter.State.READY -> "Start"; Shooter.State.RUNNING -> "Pause"; Shooter.State.PAUSED -> "Resume"; Shooter.State.OVER -> "Again" }) to "Back"
        Screen.PAIRS -> (if (pairs.won) "Again" else "Turn") to "Back"
        Screen.SEEDS -> (if (seeds.over) "Again" else "Sow") to "Back"
        Screen.GAMEHELP -> "Play" to "Back"
        Screen.WEATHER -> "" to "Back"
        Screen.BATTERY -> "" to "Back"
        Screen.MUSICMENU, Screen.PLAYLISTS, Screen.SEARCHRES, Screen.DEVICES, Screen.RADIOMENU, Screen.SPOTIFYMENU, Screen.WHEREPLACES -> "Select" to "Back"
        Screen.WHEREDETAIL -> "Go" to "Back"
        Screen.WHEREJOURNEYS -> "Details" to "Back"
        Screen.WHERELEGS -> "Map" to "Back"
        Screen.WHEREMAP -> "" to "Back"
        Screen.CLOCK, Screen.ALARMS -> "Select" to "Back"
        Screen.TIMER -> (if (timer.running) "Pause" else if (timer.paused) "Resume" else "Start") to "Back"
        Screen.STOPWATCH -> (if (stopwatch.running) "Stop" else "Start") to "Back"
        Screen.RING -> (if (ringKind == "timer") "" else "Snooze") to "Stop"
        Screen.RADIO, Screen.RADIORES -> "Play" to "Back"
        Screen.RADIOBROWSE, Screen.CONNECTIONS, Screen.PHONETYPE -> "Select" to "Back"
        Screen.FIRSTRUN -> "Select" to (if (firstConfirm != null) "Back" else if (wizStep > 0) "Back" else "")
        Screen.TIMEZONE, Screen.LANGUAGE -> "Select" to "Back"
        Screen.DATA -> "Select" to "Back"
        Screen.DATAAPPS -> "" to "Back"
        Screen.OPENOPTS -> "Change" to "Back"
        Screen.RUNNING -> (if (running.isEmpty()) "" else "Kill") to "Back"
        Screen.WIFI, Screen.WIFINET, Screen.BT, Screen.BTDEV -> "Select" to "Back"
        Screen.SOUNDTEST -> "Play" to "Back"
        Screen.MUSIC -> "Menu" to "Back"
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
        Screen.CALENDAR -> "Options" to "Back"
        Screen.CALMONTH -> "Open" to "Back"
        Screen.EVENT -> "Options" to "Back"
        Screen.MAILMSG -> "Options" to "Back"
        Screen.FORM -> (form?.primary ?: "Save") to "Back"
        Screen.INBOX -> "Open" to "Back"
        Screen.WHATSAPP -> (if (waLinked) "Open" else "Link") to "Back"
        Screen.WAPAIR -> (if (waLinked) "" else if (waCode != null) "New code" else "Get code") to (if (waCode != null || waLinked) "Back" else "Clear")
        Screen.THREAD -> "Reply" to "Back"
        Screen.CHAT -> (if (selected == 0) "Reply" else "Read") to "Back"
        Screen.WRITE -> (if (writeField == 0) (if (writeTo.isEmpty()) "Contacts" else "Next") else if (writeField == 2) "Speak" else if (writeHead != null) "Done" else "Send") to "Clear"
        else -> "Select" to "Back"
    }

    /** The lines of whichever list is showing. */
    val rows: List<String> get() = when (screen) {
        Screen.MENU -> items.map { it.label }
        Screen.APPS -> apps.map { it.label }
        Screen.GAMES -> listOf("Snake", "Pairs", "Blaster", "Seeds")
        Screen.SNAKE, Screen.PAIRS, Screen.SHOOTER, Screen.SEEDS, Screen.GAMEHELP, Screen.WEATHER, Screen.BATTERY, Screen.NOTE, Screen.MUSIC -> emptyList()
        Screen.MUSICMENU -> musicMenuRows
        Screen.RADIO -> radioRows
        Screen.RADIOBROWSE -> radioBrowseRows
        Screen.CONNECTIONS -> connectionRows
        Screen.FIRSTRUN -> firstRunRows
        Screen.DATA -> dataRows
        Screen.DATAAPPS -> dataApps
        Screen.TIMEZONE -> Regional.zones.map { it.name + if (it.id == tzId) " *" else "" }
        Screen.LANGUAGE -> Regional.languages.map { it.name + if (it.id.equals(langTag, true)) " *" else "" }
        Screen.PHONETYPE -> phoneTypeRows
        Screen.OPENOPTS -> openOptRows
        Screen.RADIORES -> if (radioRes.isEmpty()) listOf(radioStatus.ifEmpty { "Nothing found" }) else radioRes.map { it.name }
        Screen.RADIOMENU -> radioMenuRows
        Screen.SPOTIFYMENU -> spotifyMenuRows
        Screen.RUNNING -> if (running.isEmpty()) listOf("Nothing running") else running.map { it.label + " (" + it.state + ")" }
        Screen.WIFI -> listOf("Wi-Fi: " + (if (wifiOn) "On" else "Off")) + (if (!wifiOn) emptyList() else if (wifiNets.isEmpty()) listOf("Searching...") else wifiNets.map { wifiLabel(it) })
        Screen.WIFINET -> wifiNetRows
        Screen.BT -> listOf("Bluetooth: " + (if (btOn) "On" else "Off")) + (if (!btOn) emptyList() else listOf("Search for devices") + btDevs.map { btLabel(it) })
        Screen.BTDEV -> btDevRows
        Screen.SOUNDTEST -> io.github.durkimat.phoneguard.SoundTest.routes
        Screen.PLAYLISTS -> spPlaylists.map { it.label }
        Screen.WHEREPLACES -> (if (whereRecent) listOf("Search for a place...") else emptyList()) + whereResults.map { io.github.durkimat.phoneguard.where.Where.placeRow(it) }
        Screen.WHEREDETAIL -> wherePlace?.let { io.github.durkimat.phoneguard.where.Where.detailLines(it) }.orEmpty()
        Screen.WHEREJOURNEYS -> whereRows.map { it.label }
        Screen.WHERELEGS -> whereLegs.map { io.github.durkimat.phoneguard.where.Where.legLine(it) }
        Screen.WHEREMAP -> emptyList()
        Screen.CLOCK -> clockRows
        Screen.ALARMS -> alarmRows
        Screen.TIMER, Screen.STOPWATCH, Screen.RING -> emptyList()
        Screen.SEARCHRES -> spResults.map { it.label }
        Screen.DEVICES -> spDevices.map { (if (it.active) "> " else "  ") + it.name + (if (spIsThisPhone(it)) " (this phone)" else "") }
        Screen.NOTES -> notes.map { n -> n.app + ": " + (if (n.title.isNotEmpty()) n.title else n.text) } + (if (notes.isEmpty()) emptyList() else listOf("Clear all"))
        Screen.SETTINGS, Screen.LOOK, Screen.SOUNDSET, Screen.THISPHONE -> groupRows(screen)
        Screen.THEME -> Themes.all.map { it.name + if (it.id == theme) " *" else "" }
        Screen.FONT -> Fonts.all.map { it.name + if (it.id == font) " *" else "" }
        Screen.MENUSTYLE -> MenuStyles.all.map { it.name + if (it.id == menuStyle) " *" else "" }
        Screen.APPEARANCE -> appearanceKeys.map { if (it == "preset") "Presets" else Choices.title(it) + ": " + Choices.valueName(style, it) }
        Screen.CHOICE -> choiceOpts.map { it.name + if (it.id == choiceCurrent) " *" else "" }
        Screen.CONTACTS -> contacts.map { it.name }
        Screen.CONTACT -> (contact?.numbers ?: emptyList()).map { "Call $it" } + "Message"
        Screen.CALLLOG -> calls.map { (when (it.type) { 2 -> "> "; 3 -> "! "; else -> "< " }) + it.name }
        Screen.CALC, Screen.DIAL, Screen.CALL, Screen.WAPAIR -> emptyList()
        Screen.CALLMENU -> (if (speakerShown) listOf(if (call?.speaker == true) "Speaker off" else "Speaker on") else emptyList()) + (if (call?.muted == true) "Unmute" else "Mute")
        Screen.WASET -> waSetRows().map { waSetLabel(it) }
        Screen.WASETUP -> waSetupRows()
        Screen.WAREMOVE -> waRemoveRows()
        Screen.WANAMES -> NameStyle.all.map { it.second + if (it.first == waNameStyle) " *" else "" }
        Screen.SPEAKER -> listOf("Show" + if (speakerShown) " *" else "", "Hide" + if (!speakerShown) " *" else "")
        Screen.TYPING -> listOf("On" + if (predictiveOn) " *" else "", "Off" + if (!predictiveOn) " *" else "", "Forget learned words")
        Screen.MESSAGES -> messagesRows
        Screen.INBOX -> convos.map { (if (it.unread > 0) "* " else "") + it.name }
        Screen.WHATSAPP -> waLead() + if (!waLinked) listOf("Link account") else waConvos.map { (if (it.unread > 0) "* " else "") + it.name }
        // the messages of the open chat, newest first, behind a "Reply" entry; each is a one-line preview
        Screen.CHAT -> listOf("Reply") + (convo?.msgs ?: emptyList()).asReversed().map {
            val who = if (!it.mine && it.sender.isNotBlank()) NameStyle.short(it.sender, waNameStyle) + ": " else ""
            (if (it.mine) "> " else "< ") + who + it.body.lineSequence().firstOrNull().orEmpty()
        }
        Screen.CALENDAR -> agenda.map { Cal.agendaRow(it, today, zone) }.ifEmpty { listOf("No events") }
        Screen.CALMENU -> listOf("Add event", "Month view", "Go to today")
        Screen.EVMENU -> evMenuRows
        Screen.FORM -> form?.rowsOf?.invoke()?.map { it.text } ?: emptyList()
        Screen.CALMONTH, Screen.EVENT, Screen.MAILMSG -> emptyList()
        Screen.MAILBOX -> mailBoxRows
        Screen.MAILLIST -> mailList.map { (if (it.unread && !mailSentBox) "* " else "") + (if (mailSentBox) it.to.ifBlank { it.from } else it.from).let { n -> if (n.length > 12) n.take(11) + "." else n } + ": " + it.subject }.ifEmpty { listOf(mailStatus.ifBlank { "No mail" }) }
        Screen.MAILMENU -> mailMenuRows
        Screen.THREAD, Screen.WRITE, Screen.HOME -> emptyList()
    }

    /** `digit` is only used with [Key.DIGIT]. */
    fun onKey(key: Key, digit: Int = -1, nowMs: Long = System.currentTimeMillis()): Action {
        notice = null
        return dispatch(key, digit, nowMs)
    }

    /** The lists whose numbered entries are chosen by pressing that number, like a Nokia menu. */
    private val numbered = setOf(Screen.CLOCK, Screen.ALARMS, Screen.SETTINGS, Screen.THEME, Screen.FONT, Screen.MENUSTYLE, Screen.APPEARANCE, Screen.CHOICE, Screen.TYPING, Screen.SPEAKER, Screen.INBOX, Screen.WHATSAPP, Screen.WASET, Screen.WASETUP, Screen.WAREMOVE, Screen.WANAMES, Screen.CHAT, Screen.CONTACTS, Screen.CONTACT, Screen.CALLLOG, Screen.CALLMENU, Screen.APPS, Screen.GAMES, Screen.MUSICMENU, Screen.PLAYLISTS, Screen.SEARCHRES, Screen.DEVICES, Screen.SOUNDTEST, Screen.RUNNING, Screen.WIFI, Screen.WIFINET, Screen.BT, Screen.BTDEV, Screen.RADIO, Screen.RADIOMENU, Screen.SPOTIFYMENU, Screen.RADIOBROWSE, Screen.RADIORES, Screen.CONNECTIONS, Screen.CALENDAR, Screen.CALMENU, Screen.EVMENU, Screen.FORM, Screen.MAILBOX, Screen.MAILLIST, Screen.MAILMENU, Screen.FIRSTRUN, Screen.PHONETYPE, Screen.OPENOPTS, Screen.TIMEZONE, Screen.LANGUAGE, Screen.DATA, Screen.LOOK, Screen.SOUNDSET, Screen.THISPHONE)

    private fun dispatch(key: Key, digit: Int, nowMs: Long): Action {
        if (key == Key.DIGIT && screen in numbered && !inConfirm() && digit in 1..minOf(9, rows.size)) { selected = digit - 1; return route(Key.OK, -1, nowMs) }
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
        Screen.RADIO -> radioKey(key)
        Screen.RADIOBROWSE -> radioBrowseKey(key)
        Screen.CONNECTIONS -> connectionsKey(key)
        Screen.FIRSTRUN -> firstRunKey(key)
        Screen.DATA -> dataKey(key)
        Screen.DATAAPPS -> move(key, dataApps.size) ?: when (key) { Key.OK, Key.LEFT, Key.SOFT_LEFT, Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }; Key.END -> { goHome(); Action.Redraw }; else -> Action.None }
        Screen.TIMEZONE -> pickKey(key, Regional.zones.size) { tzId = Regional.zones[it].id; Action.SetTimeZone(tzId) }
        Screen.LANGUAGE -> pickKey(key, Regional.languages.size) { langTag = Regional.languages[it].id; Action.SetLanguage(langTag) }
        Screen.PHONETYPE -> phoneTypeKey(key)
        Screen.OPENOPTS -> openOptsKey(key)
        Screen.RADIORES -> radioResKey(key)
        Screen.RADIOMENU -> radioMenuKey(key)
        Screen.SPOTIFYMENU -> spotifyMenuKey(key)
        Screen.RUNNING -> runningKey(key)
        Screen.WIFI -> wifiKey(key)
        Screen.WIFINET -> wifiNetKey(key)
        Screen.BT -> btKey(key)
        Screen.BTDEV -> btDevKey(key)
        Screen.SOUNDTEST -> soundTestKey(key)
        Screen.PLAYLISTS, Screen.SEARCHRES, Screen.DEVICES -> spListKey(key)
        Screen.WHEREPLACES, Screen.WHEREDETAIL, Screen.WHEREJOURNEYS, Screen.WHERELEGS, Screen.WHEREMAP -> whereKey(key, digit)
        Screen.NOTES -> notesKey(key)
        Screen.NOTE -> noteDetailKey(key, digit)
        Screen.SNAKE -> snakeKey(key, digit)
        Screen.PAIRS -> pairsKey(key, digit)
        Screen.SHOOTER -> shooterKey(key, digit)
        Screen.SEEDS -> seedsKey(key, digit)
        Screen.SETTINGS, Screen.LOOK, Screen.SOUNDSET, Screen.THISPHONE -> settings(key, digit)
        Screen.THEME -> themes(key, digit)
        Screen.APPEARANCE -> appearance(key)
        Screen.CHOICE -> choice(key)
        Screen.FONT -> fonts(key, digit)
        Screen.MENUSTYLE -> menuStyles(key, digit)
        Screen.CONTACTS -> contactList(key, digit)
        Screen.CONTACT -> contactDetail(key, digit)
        Screen.MESSAGES -> messagesMenu(key, digit)
        Screen.INBOX -> inbox(key, digit)
        Screen.WHATSAPP -> whatsapp(key, digit)
        Screen.WAPAIR -> waPair(key, digit)
        Screen.WANAMES -> waNames(key, digit)
        Screen.WASET -> waSettings(key, digit)
        Screen.WASETUP -> waSetup(key, digit)
        Screen.WAREMOVE -> waRemove(key, digit)
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
        Screen.CALENDAR -> calendarKey(key)
        Screen.CALMENU -> calMenuKey(key)
        Screen.CALMONTH -> calMonthKey(key, digit)
        Screen.EVENT -> eventKey(key)
        Screen.EVMENU -> evMenuKey(key)
        Screen.FORM -> formKey(key)
        Screen.CLOCK -> clockKey(key)
        Screen.ALARMS -> alarmsKey(key)
        Screen.TIMER -> timerKey(key, digit, nowMs)
        Screen.STOPWATCH -> stopwatchKey(key, nowMs)
        Screen.RING -> ringKey(key)
        Screen.MAILBOX -> mailBoxKey(key)
        Screen.MAILLIST -> mailListKey(key)
        Screen.MAILMSG -> mailMsgKey(key)
        Screen.MAILMENU -> mailMenuKey(key)
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
        dialler = false
        leave()
        dialNumber = ""
        return Action.Redraw
    }

    /** From the dial pad this is a sideways step inside the dialler; from anywhere else it opens on top of where you are. */
    private fun openCallLog(from: Screen): Action {
        if (from == Screen.DIAL) { dialler = true; screen = Screen.CALLLOG; selected = 0 } else { dialler = false; enter(Screen.CALLLOG) }
        return Action.Redraw
    }

    private fun callLog(key: Key, digit: Int): Action = move(key, calls.size) ?: when (key) {
        Key.LEFT, Key.SOFT_LEFT -> if (dialler) openContacts(Screen.DIAL) else calls.getOrNull(selected)?.let { Action.Call(it.number) } ?: Action.None
        Key.OK, Key.CALL -> calls.getOrNull(selected)?.let { Action.Call(it.number) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> if (dialler) exitDialler() else { leave(); Action.Redraw }
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
            Key.RIGHT, Key.SOFT_RIGHT -> if (!calc.clear()) leave()
            Key.END -> { calc.allClear(); goHome() }
            else -> return Action.None
        }
        return Action.Redraw
    }

    private fun speakerSetting(key: Key, digit: Int): Action = move(key, 2) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { speakerShown = selected == 0; leave(); Action.SaveSpeaker(speakerShown) }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun typing(key: Key, digit: Int): Action = move(key, 3) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (selected == 2) { leave(); Action.ForgetWords } else { predictiveOn = selected == 0; writeMode = 0; leave(); Action.SavePredictive(predictiveOn) }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    // ---- Messages

    private fun openMessages(): Action { enter(Screen.MESSAGES); return Action.Redraw }

    private fun messagesMenu(key: Key, digit: Int): Action = move(key, messagesRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> messageEntry(selected)
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        Key.DIGIT -> if (digit in 1..messagesRows.size) { selected = digit - 1; messageEntry(digit - 1) } else Action.None
        else -> Action.None
    }

    /** Row 0 is the SMS inbox; with WhatsApp linked row 1 is WhatsApp and the last row writes a text. */
    private fun messageEntry(row: Int): Action = when {
        row == 0 -> { enter(Screen.INBOX); Action.Redraw }
        waEnabled && row == 2 -> { enter(Screen.WHATSAPP); threadWa = true; if (waLinked) Action.WaOpen else Action.Redraw }
        else -> { threadWa = false; startWrite("", Screen.MESSAGES, wa = false) }
    }

    private fun startPair(): Action { waPairNumber = ""; waCode = null; enter(Screen.WAPAIR); return Action.Redraw }

    private fun waNames(key: Key, digit: Int): Action = move(key, NameStyle.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { waNameStyle = NameStyle.all[selected].first; leave(); Action.SaveNameStyle(waNameStyle) }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun waSetRows(): List<String> = (if (waSetupAvail || waSetupActive) listOf("setup") else emptyList()) + (if (waLinked) listOf("resync", "names", "unlink") else listOf("link")) + (if (waSim && (waLinked || waOfficial)) listOf("remove") else emptyList())
    private fun waSetLabel(id: String) = when (id) {
        "setup" -> if (waSetupActive) "Setup (running)" else "Set up WhatsApp"
        "resync" -> "Re-sync names and messages"; "names" -> "Names in lists"; "unlink" -> "Unlink account"; "remove" -> "Remove WhatsApp..."; else -> "Link account"
    }

    /** The setup window's steps. Not started: one row. Running: open the official app to register, link this phone to it, then Done. */
    private fun waSetupRows(): List<String> =
        if (!waSetupActive) listOf("Start setup (30 minutes)")
        else listOf(if (waOfficial) "1. Open WhatsApp and register" else "1. WhatsApp is not installed yet", "2. Link this phone to it" + if (waLinked) " (done)" else "", "3. Done")

    private fun waSetup(key: Key, digit: Int): Action = move(key, waSetupRows().size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when {
            !waSetupActive -> Action.SetupStart
            selected == 0 -> if (waOfficial) Action.SetupOpenOfficial else { notice = "Ask for it to be installed on the companion"; Action.Redraw }
            selected == 1 -> if (waLinked) { notice = "Already linked"; Action.Redraw } else { waPairNumber = waSimNumber.filter { it.isDigit() }.take(15); waCode = null; enter(Screen.WAPAIR); Action.Redraw }
            else -> { leave(); Action.SetupEnd }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** Remove WhatsApp (SIM method): unlink this phone, delete the account inside WhatsApp (a person does that; needs a setup window), then uninstall the app. */
    private fun waRemoveRows(): List<String> = listOf(
        "1. Unlink this phone" + if (!waLinked) " (done)" else "",
        if (!waOfficial) "2. Delete account (WhatsApp is not on the phone)" else if (waSetupActive) "2. Delete account in WhatsApp" else "2. Delete account (needs setup allowed)",
        if (waOfficial) "3. Uninstall WhatsApp" else "3. Uninstall (done)",
    )

    private fun waRemove(key: Key, digit: Int): Action = move(key, waRemoveRows().size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (selected) {
            0 -> if (waLinked) { notice = "Unlinking..."; Action.UnlinkWa } else { notice = "Already unlinked"; Action.Redraw }
            1 -> if (waOfficial && waSetupActive) { notice = "In WhatsApp: Settings, Account, Delete account"; Action.SetupOpenOfficial } else { notice = if (waOfficial) "Ask the companion to allow setup, then Start it" else "Nothing to delete"; Action.Redraw }
            else -> if (waOfficial) { notice = "Uninstalling..."; Action.UninstallOfficial } else { notice = "Already removed"; Action.Redraw }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun waSettings(key: Key, digit: Int): Action = move(key, waSetRows().size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (waSetRows().getOrNull(selected)) {
            "setup" -> { enter(Screen.WASETUP); Action.Redraw }
            "link" -> startPair()
            "resync" -> { notice = "Re-syncing..."; Action.ResyncWa }
            "names" -> { enter(Screen.WANAMES, NameStyle.all.indexOfFirst { it.first == waNameStyle }.coerceAtLeast(0)); Action.Redraw }
            "unlink" -> { leave(); Action.UnlinkWa }
            "remove" -> { enter(Screen.WAREMOVE); Action.Redraw }
            else -> Action.None
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
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
                done -> { leave(); Action.Redraw }
                waPairNumber.isNotEmpty() -> { waPairNumber = waPairNumber.dropLast(1); Action.Redraw }
                else -> { leave(); Action.Redraw }
            }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    /** A "Set up WhatsApp" row leads the WhatsApp list while the companion allows the SIM-method setup (or it is running). */
    private fun waLead(): List<String> = if (waSetupAvail || waSetupActive) listOf("Set up WhatsApp") else emptyList()

    private fun whatsapp(key: Key, digit: Int): Action = move(key, waLead().size + if (waLinked) waConvos.size else 1) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (waLead().isNotEmpty() && selected == 0) { enter(Screen.WASETUP); Action.Redraw } else if (!waLinked) startPair() else waConvos.getOrNull(selected - waLead().size)?.let { convo = it; threadWa = true; screen = Screen.CHAT; selected = if (it.msgs.isEmpty()) 0 else 1; scroll = 0; Action.WaRead(it.address) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { threadWa = false; leave(); Action.Redraw }
        Key.END -> { threadWa = false; goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun inbox(key: Key, digit: Int): Action = move(key, convos.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> convos.getOrNull(selected)?.let { convo = it; threadWa = false; screen = Screen.CHAT; selected = if (it.msgs.isEmpty()) 0 else 1; scroll = 0; if (it.unread > 0) Action.SmsRead(it.address) else Action.Redraw } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
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
        writeWa = wa; writeLabel = label; writeSearch = false; writeRadio = false; writeWifi = null; writeHead = null; writeDone = null; writeExact = false
        writeField = if (to.isEmpty()) 0 else 1
        screen = Screen.WRITE
        return Action.Redraw
    }

    private fun leaveWrite(): Action { screen = writeBack; return Action.Redraw }

    private fun write(key: Key, digit: Int, now: Long): Action =
        if (writeField == 0) writeToField(key, digit) else if (writeField == 2) writeMic(key, digit, now) else writeBody(key, digit, now)

    /** The microphone button has the focus: hold OK (handled by the activity) speaks; every other key goes back to the text. */
    private fun writeMic(key: Key, digit: Int, now: Long): Action = when (key) {
        Key.UP, Key.SOFT_RIGHT -> { writeField = 1; Action.Redraw }
        Key.OK -> { notice = "Hold OK and speak"; Action.Redraw }
        Key.DOWN, Key.SOFT_LEFT -> Action.None
        Key.END -> { goHome(); Action.Redraw }
        else -> { writeField = 1; writeBody(key, digit, now) }
    }

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
        Key.DOWN -> if (voiceShown) { commit(); writeField = 2; Action.Redraw } else Action.None
        Key.LEFT -> { commit(); cursor = (cursor - 1).coerceAtLeast(0); lastDigit = -1; Action.Redraw }
        Key.RIGHT -> { commit(); cursor = (cursor + 1).coerceAtMost(writeText.length); lastDigit = -1; Action.Redraw }
        Key.OK, Key.SOFT_LEFT -> { commit(); writeDone?.let { f -> writeDone = null; writeHead = null; screen = writeBack; return f(writeText.trim()) }; if (writeWifi != null) { val n = writeWifi!!; screen = Screen.WIFI; selected = 0; writeWifi = null; Action.WifiJoin(n.ssid, writeText, n.caps, n.id) } else if (writeSearch && writeRadio) { if (writeText.isBlank()) Action.None else { val q = writeText.trim(); writeRadio = false; writeSearch = false; resFromFind = true; radioRes = emptyList(); radioStatus = "Searching..."; radioResTitle = "Stations"; screen = Screen.RADIORES; selected = 0; Action.RadioSearch(q) } } else if (writeSearch) { if (writeText.isBlank()) Action.None else { val q = writeText.trim(); screen = Screen.SEARCHRES; selected = 0; spResults = emptyList(); spStatus = "Searching..."; Action.SpSearch(q) } } else if (writeText.isBlank() || writeTo.isBlank()) Action.None else {
            val a = if (writeWa) Action.SendWa(writeTo, writeText.trim()) else Action.Send(writeTo, writeText.trim())
            leaveTo(Screen.MESSAGES, 0); notice = "Message sent"
            a
        } }
        Key.SOFT_RIGHT -> when {
            seq.isNotEmpty() -> { unpress(); Action.Redraw }
            cursor > 0 -> { writeText = writeText.removeRange(cursor - 1, cursor); cursor--; lastDigit = -1; Action.Redraw }
            writeText.isEmpty() -> leaveWrite()
            else -> Action.None
        }
        Key.CALL -> if (writeTo.isNotBlank() && writeHead == null) Action.Call(writeTo) else Action.None
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

    private val PW_SYMBOLS = "!@#\$%^&*()-_=+.,?:;/'\"[]{}\\|<>~`"

    private val tapChars = mapOf(
        0 to " 0", 1 to ".,?!'-@1", 2 to "abc2", 3 to "def3", 4 to "ghi4", 5 to "jkl5", 6 to "mno6", 7 to "pqrs7", 8 to "tuv8", 9 to "wxyz9",
        10 to ".,?!'-@:/()&",
    )

    /** Multi-tap typing: press a key again within a second to cycle its letters, like a Nokia without T9. */
    private fun typeKey(d: Int, now: Long) {
        if (writeText.length >= 480) return
        if (modeName == "123" && d <= 9) { insert(d.toString()); lastDigit = -1; return }
        val set = (if (exactTyping && d == 10) PW_SYMBOLS else if (exactTyping && d == 1) "1.-_@" else tapChars[d]) ?: return
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

    /** From the dial pad, or to pick a recipient while writing, this is a sideways step; from anywhere else it opens on top of where you are. */
    private fun openContacts(from: Screen): Action {
        when (from) {
            Screen.DIAL -> { dialler = true; screen = Screen.CONTACTS; selected = 0 }
            Screen.WRITE -> { screen = Screen.CONTACTS; selected = 0 }
            else -> { dialler = false; enter(Screen.CONTACTS) }
        }
        return Action.Redraw
    }

    private fun contactOpen(): Action = contacts.getOrNull(selected)?.let {
        if (picking) { picking = false; writeTo = it.numbers.firstOrNull().orEmpty(); screen = Screen.WRITE; writeField = 1; Action.Redraw }
        else { contact = it; screen = Screen.CONTACT; selected = 0; Action.Redraw }
    } ?: Action.None

    private fun contactList(key: Key, digit: Int): Action = move(key, contacts.size) ?: when (key) {
        Key.LEFT, Key.SOFT_LEFT -> if (dialler && !picking) { screen = Screen.DIAL; Action.Redraw } else contactOpen()
        Key.OK -> contactOpen()
        Key.RIGHT, Key.SOFT_RIGHT -> {
            when {
                picking -> { picking = false; screen = Screen.WRITE }
                dialler -> exitDialler()
                else -> leave()
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

    /**
     * On the "Are you sure?" pages the first lines are plain text, not choices: they carry no number, the cursor never lands on them, and a number key never
     * picks anything (a stray key must not erase the phone). Everywhere else this is 0.
     */
    val plainRows: Int get() = when {
        screen == Screen.FIRSTRUN && firstConfirm == "open" -> 4
        screen == Screen.PHONETYPE && confirmErase -> 4
        else -> 0
    }

    private fun move(key: Key, size: Int): Action? {
        val lo = plainRows.coerceAtMost(size)
        val n = size - lo
        return when (key) {
            Key.UP -> if (n <= 0) Action.None else { selected = lo + ((selected.coerceAtLeast(lo) - lo - 1 + n) % n); Action.Redraw }
            Key.DOWN -> if (n <= 0) Action.None else { selected = lo + ((selected.coerceAtLeast(lo) - lo + 1) % n); Action.Redraw }
            else -> null
        }
    }

    private fun open(item: MenuItem?): Action = when {
        item == null -> Action.None
        item.pkg == SETTINGS_PKG -> { enter(Screen.SETTINGS); Action.Redraw }
        item.pkg == contactsApp -> openContacts(Screen.MENU)
        item.pkg == messagesApp -> openMessages()
        item.pkg == dialerApp -> openPhoneMenu()
        item.pkg == CLOCK_PKG -> openClock()
        item.pkg == CALC_PKG -> { calc.allClear(); enter(Screen.CALC); Action.Redraw }
        item.pkg == CALLLOG_PKG -> openCallLog(Screen.MENU)
        item.pkg == CAMERA_PKG -> Action.OpenCamera
        item.pkg == MUSIC_PKG -> { enter(Screen.MUSICMENU); Action.Redraw }
        item.pkg == WHERE_PKG -> whereStart("")
        item.pkg == RUNNING_PKG -> { enter(Screen.RUNNING); Action.Redraw }
        item.pkg == NOTES_PKG -> { enter(Screen.NOTES); Action.Redraw }
        item.pkg == CALENDAR_PKG -> openCalendar()
        item.pkg == MAIL_PKG -> openMail()
        item.pkg == WEATHER_PKG -> { enter(Screen.WEATHER); Action.Redraw }
        item.pkg == GAMES_PKG -> { enter(Screen.GAMES); Action.Redraw }
        item.pkg == APPS_PKG -> { enter(Screen.APPS); Action.Redraw }
        else -> Action.Launch(item.pkg)
    }

    // ---- editions (docs/plan-two-editions.md) ----
    /** "first_boot", "open" or "family"; set by the activity. A first-boot phone shows the first-run screen until the person chooses. */
    var edition = ""
        set(v) { field = v; if (v == "first_boot" && screen != Screen.FIRSTRUN && !firstRunLeft()) { trail.clear(); screen = Screen.FIRSTRUN; selected = 0; firstConfirm = null } }
    /** The options of an Open phone, as saved on the phone. */
    var openOpts: Map<String, String> = emptyMap()
    private var firstConfirm: String? = null
    private var confirmErase = false
    /** True while the person is in a screen reached from first-run (Wi-Fi and so on), so the first-run screen is not forced back over it. */
    private fun firstRunLeft() = trail.any { it.screen == Screen.FIRSTRUN } || screen in setOf(Screen.WIFI, Screen.WIFINET, Screen.BT, Screen.BTDEV, Screen.TIMEZONE, Screen.LANGUAGE)

    /** The wizard's place: 0 Wi-Fi, 1 time and language, 2 phone type. */
    var wizStep = 0
        private set
    var tzId: String = java.util.TimeZone.getDefault().id
    var langTag: String = java.util.Locale.getDefault().toLanguageTag()

    private val firstRunRows: List<String> get() = when {
        firstConfirm == "open" -> listOf("Nothing is locked.", "No parent controls.", "Changing this later", "erases the phone.", "Yes, Open phone", "No, go back")
        wizStep == 0 -> listOf("Choose Wi-Fi", if (connWifi == 1) "Next" else "Skip")
        wizStep == 1 -> listOf("Time zone: " + Regional.zoneName(tzId), "Language: " + Regional.languageName(langTag), "Next", "Back")
        else -> listOf("Open phone", "Family phone", "Back")
    }

    private fun pickKey(key: Key, size: Int, choose: (Int) -> Action): Action = move(key, size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { val a = choose(selected); leave(); a }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** On a question that erases or cannot be undone a number key must never choose: only Select on the highlighted row does. */
    private fun inConfirm() = (screen == Screen.FIRSTRUN && firstConfirm != null) || (screen == Screen.PHONETYPE && confirmErase)

    private fun firstRunKey(key: Key): Action = move(key, firstRunRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (firstConfirm == "open") when (selected) {
            4 -> { edition = "open"; firstConfirm = null; goHome(); Action.ChooseOpen }
            5 -> { firstConfirm = null; selected = 0; Action.Redraw }
            else -> Action.None
        } else when (wizStep) {
            0 -> if (selected == 0) { enter(Screen.WIFI); Action.WifiLoad } else { wizStep = 1; selected = 0; Action.Redraw }
            1 -> when (selected) {
                0 -> { enter(Screen.TIMEZONE, Regional.zones.indexOfFirst { it.id == tzId }.coerceAtLeast(0)); Action.Redraw }
                1 -> { enter(Screen.LANGUAGE, Regional.languages.indexOfFirst { it.id.equals(langTag, true) || it.id.startsWith(langTag.substringBefore('-') + "-", true) }.coerceAtLeast(0)); Action.Redraw }
                2 -> { wizStep = 2; selected = 0; Action.Redraw }
                else -> { wizStep = 0; selected = 0; Action.Redraw }
            }
            else -> when (selected) {
                0 -> { firstConfirm = "open"; selected = 5; Action.Redraw }
                1 -> Action.StartFamily
                else -> { wizStep = 1; selected = 2; Action.Redraw }
            }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> if (firstConfirm != null) { firstConfirm = null; selected = 0; Action.Redraw } else if (wizStep > 0) { wizStep--; selected = 0; Action.Redraw } else Action.None
        else -> Action.None // End does nothing until a choice is made
    }

    private val phoneTypeRows: List<String> get() = when {
        edition == "family" -> listOf("Family phone", "Set by your parent", "Parent can release it")
        confirmErase -> listOf("This erases the", "whole phone:", "contacts, messages", "and settings.", "Erase and restart", "No, go back")
        else -> listOf("Open phone", "Lost PIN = erase phone", "Switch to Family (erases)")
    }

    private fun phoneTypeKey(key: Key): Action = move(key, phoneTypeRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when {
            edition == "family" -> Action.None
            confirmErase -> when (selected) { 4 -> Action.EraseAndRestart; 5 -> { confirmErase = false; selected = 2; Action.Redraw }; else -> Action.None }
            selected == 2 -> { confirmErase = true; selected = 5; Action.Redraw }
            else -> Action.None
        }
        Key.RIGHT, Key.SOFT_RIGHT -> if (confirmErase) { confirmErase = false; selected = 2; Action.Redraw } else { leave(); Action.Redraw }
        Key.END -> { confirmErase = false; goHome(); Action.Redraw }
        else -> Action.None
    }

    private val openOptRows: List<String> get() = OpenConfig.options.map { it.label + ": " + OpenConfig.nameOf(openOpts, it) } +
        ("Spotify Client ID: " + (if (openOpts["spotifyClientId"].isNullOrEmpty()) "not set" else "set"))

    private fun openOptsKey(key: Key): Action = move(key, openOptRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> OpenConfig.options.getOrNull(selected)?.let { o ->
            val v = OpenConfig.step(openOpts, o, 1); openOpts = openOpts + (o.key to v); Action.SaveOpenOption(o.key, v)
        } ?: startText("Spotify Client ID", openOpts["spotifyClientId"].orEmpty(), Screen.OPENOPTS, exact = true) { v ->
            openOpts = openOpts + ("spotifyClientId" to v.trim()); Action.SaveOpenOption("spotifyClientId", v.trim())
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun settings(key: Key, digit: Int): Action = move(key, groupRows(screen).size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            var out: Action = Action.Redraw
            when (groupRows(screen).getOrNull(selected)) {
                "Look" -> enter(Screen.LOOK)
                "Sound and typing" -> enter(Screen.SOUNDSET)
                "This phone" -> enter(Screen.THISPHONE)
                "Notifications" -> out = open(extraItems.firstOrNull { it.pkg == NOTES_PKG })
                "Running Apps" -> out = open(extraItems.firstOrNull { it.pkg == RUNNING_PKG })
                "Theme" -> enter(Screen.THEME, Themes.all.indexOfFirst { it.id == theme }.coerceAtLeast(0))
                "Font" -> enter(Screen.FONT, Fonts.all.indexOfFirst { it.id == font }.coerceAtLeast(0))
                "Menu style" -> enter(Screen.MENUSTYLE, MenuStyles.all.indexOfFirst { it.id == menuStyle }.coerceAtLeast(0))
                "Appearance" -> enter(Screen.APPEARANCE)
                "Predictive text" -> enter(Screen.TYPING, if (predictiveOn) 0 else 1)
                "Speakerphone" -> enter(Screen.SPEAKER, if (speakerShown) 0 else 1)
                "Ringer mode" -> { pushForm(ringerForm()); out = Action.Redraw }
                "Volumes" -> { pushForm(volumesForm()); out = Action.Redraw }
                "Ringtone and message tones" -> { pushForm(tonesForm()); out = Action.Redraw }
                "Sound test" -> enter(Screen.SOUNDTEST)
                "WhatsApp" -> enter(Screen.WASET)
                "Android settings" -> out = Action.Launch("com.android.settings")
                "Connections" -> enter(Screen.CONNECTIONS)
                "Time zone" -> enter(Screen.TIMEZONE, Regional.zones.indexOfFirst { it.id == tzId }.coerceAtLeast(0))
                "Screen lock" -> out = openLock()
                "Language" -> enter(Screen.LANGUAGE, Regional.languages.indexOfFirst { it.id.equals(langTag, true) || it.id.startsWith(langTag.substringBefore('-') + "-", true) }.coerceAtLeast(0))
                "Phone options" -> enter(Screen.OPENOPTS)
                "Phone type" -> { confirmErase = false; enter(Screen.PHONETYPE) }
            }
            out
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun menuStyles(key: Key, digit: Int): Action = move(key, MenuStyles.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { menuStyle = MenuStyles.all[selected].id; leave(); Action.SaveMenuStyle(menuStyle) }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun fonts(key: Key, digit: Int): Action = move(key, Fonts.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { font = Fonts.all[selected].id; leave(); Action.SaveFont(font) }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    /** Preset first, then the settings the skin in use has (window frame, colour, wallpaper and shadow belong to the Mac look). */
    private val appearanceKeys: List<String> get() = listOf("preset") + (if (Themes.byId(theme).skin == "mac") listOf("windows", "colour", "wall", "shadow") else emptyList()) + listOf("bars", "font_display", "font_text", "font_label")
    private val choiceOpts: List<Opt> get() = when {
        choiceKey == "preset" -> Presets.all.map { Opt(it.id, it.name) }
        Choices.isFont(choiceKey) -> listOf(Opt("", "Same as Font")) + Choices.fonts
        else -> Choices.of(choiceKey)
    }
    /** The id of the choice in force (a font role with none is the empty id), or null for the presets, which have no current one. */
    private val choiceCurrent: String? get() = if (choiceKey == "preset") null else style[choiceKey] ?: if (Choices.isFont(choiceKey)) "" else null

    private fun appearance(key: Key): Action = move(key, appearanceKeys.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { choiceKey = appearanceKeys[selected]; enter(Screen.CHOICE, choiceOpts.indexOfFirst { it.id == choiceCurrent }.coerceAtLeast(0)); Action.Redraw }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun choice(key: Key): Action = move(key, choiceOpts.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            val o = choiceOpts[selected]
            val a: Action = if (choiceKey == "preset") {
                Presets.byId(o.id)?.let { theme = it.theme; menuStyle = it.menuStyle; style = it.style }
                Action.SavePreset(o.id)
            } else { style = style.with(choiceKey, o.id.ifEmpty { null }); Action.SaveOption(choiceKey, o.id) }
            leave(); a
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun themes(key: Key, digit: Int): Action = move(key, Themes.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { theme = Themes.all[selected].id; leave(); Action.SaveTheme(theme) }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    // The two labels at the bottom of the screen belong to the two keys under the screen (left key =
    // left label, right key = right label), like a real Nokia. D-pad Left and Right do the same.
    // ---- the symbols on the home screen, reached with the keypad (the same places a touch goes to)
    /** What can be chosen on the home screen without touch, top to bottom: the status symbols, the weather, the notification symbols. */
    class HomeTarget(val kind: String, val l: Float, val t: Float, val r: Float, val b: Float, val badge: Badge? = null)

    /** Which of [homeTargets] has the highlight, or -1 when none (the normal home screen). */
    var homeFocus = -1

    fun homeTargets(): List<HomeTarget> = buildList {
        if (dataShown || wifiShown) add(HomeTarget("signal", 0f, 0f, 105f, 46f))
        if (wifiShown) add(HomeTarget("wifi", 105f, 0f, 175f, 46f))
        if (btShown) add(HomeTarget("bt", 176f, 0f, 215f, 46f))
        if (items.any { it.pkg == SETTINGS_PKG }) add(HomeTarget("battery", 330f, 0f, 480f, 46f)) // the battery page lives under Settings: hidden where the mode hides Settings
        if (dataView != null) { val y = dataY(Themes.byId(theme).skin == "mac"); add(HomeTarget("data", 8f, 52f + y, 472f, 88f + y)) }
        if (weather != null) { val mac = Themes.byId(theme).skin == "mac"; val cy = weatherCy(mac); add(HomeTarget("weather", 8f, cy - 34f, 472f, cy + 34f)) }
        add(clockTarget())
        badges.forEachIndexed { n, b -> val cx = badgeX(n, badges.size); add(HomeTarget("badge", (cx - 62f).coerceAtLeast(2f), 455f, (cx + 62f).coerceAtMost(478f), 565f, b)) }
    }

    /** The highlighted symbol, for the skin to outline. */
    fun homeFocusTarget(): HomeTarget? = if (screen == Screen.HOME) homeTargets().getOrNull(homeFocus) else null

    private fun homeFocusKey(key: Key): Action? {
        val list = homeTargets()
        if (list.isEmpty()) { homeFocus = -1; return null }
        return when (key) {
            Key.UP, Key.LEFT -> { homeFocus = if (homeFocus < 0) (list.indexOfFirst { it.kind == "clock" }.takeIf { it >= 0 } ?: 0) else (homeFocus - 1).coerceAtLeast(0); Action.Redraw }
            Key.DOWN, Key.RIGHT -> { homeFocus = if (homeFocus < 0) -1 else if (homeFocus + 1 >= list.size) -1 else homeFocus + 1; Action.Redraw }
            Key.END, Key.SOFT_RIGHT -> { homeFocus = -1; Action.Redraw }
            Key.OK, Key.SOFT_LEFT -> {
                val t = list.getOrNull(homeFocus) ?: return null
                homeFocus = -1
                when (t.kind) {
                    "signal" -> { openConnection(Screen.CONNECTIONS); Action.Redraw }
                    "wifi" -> { openConnection(Screen.WIFI); Action.WifiLoad }
                    "bt" -> { openConnection(Screen.BT); Action.BtLoad }
                    "battery" -> { enter(Screen.BATTERY); Action.Redraw }
                    "weather" -> openWeather()
                    "clock" -> openClock()
                    "data" -> { enter(Screen.DATA); Action.DataLoad }
                    else -> t.badge?.let { openBadge(it) } ?: Action.Redraw
                }
            }
            else -> { homeFocus = -1; null } // any other key (digits, Call) leaves the highlight and does what it always did
        }
    }

    private fun home(key: Key, digit: Int): Action {
        if (homeFocus >= 0 || key == Key.UP) homeFocusKey(key)?.let { return it }
        return homeKey(key, digit)
    }

    private fun homeKey(key: Key, digit: Int): Action = when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { screen = Screen.MENU; selected = 0; Action.Redraw }
        Key.RIGHT, Key.SOFT_RIGHT -> if (items.any { it.pkg == MUSIC_PKG }) { if (badges.any { it.kind == BadgeKind.MUSIC }) { selected = 0; provider = playingProvider ?: provider; screen = Screen.MUSIC } else enter(Screen.MUSICMENU); Action.Redraw } else openContacts(Screen.HOME)
        Key.CALL -> openPhoneMenu()
        Key.DIGIT -> if (digit in 0..9) { dialNumber = digit.toString(); dialler = true; enter(Screen.DIAL); Action.Redraw } else Action.None
        Key.STAR -> { dialNumber = "*"; dialler = true; enter(Screen.DIAL); Action.Redraw }
        Key.POUND -> { dialNumber = "#"; dialler = true; enter(Screen.DIAL); Action.Redraw }
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
    private val rowScreens = setOf(Screen.CLOCK, Screen.ALARMS, Screen.WIFI, Screen.WIFINET, Screen.BT, Screen.BTDEV, Screen.MENU, Screen.SETTINGS, Screen.THEME, Screen.FONT, Screen.MENUSTYLE, Screen.APPEARANCE, Screen.CHOICE, Screen.CONTACTS, Screen.CONTACT, Screen.CALLLOG, Screen.INBOX, Screen.CHAT,
        Screen.APPS, Screen.GAMES, Screen.NOTES, Screen.MUSICMENU, Screen.PLAYLISTS, Screen.SEARCHRES, Screen.DEVICES, Screen.SOUNDTEST, Screen.WASET, Screen.WASETUP, Screen.WAREMOVE, Screen.WANAMES, Screen.TYPING, Screen.SPEAKER, Screen.MESSAGES, Screen.WHATSAPP, Screen.CALLMENU, Screen.RADIO, Screen.RADIOMENU, Screen.SPOTIFYMENU, Screen.RADIOBROWSE, Screen.RADIORES, Screen.CONNECTIONS, Screen.CALENDAR, Screen.CALMENU, Screen.EVMENU, Screen.FORM, Screen.MAILBOX, Screen.MAILLIST, Screen.MAILMENU, Screen.FIRSTRUN, Screen.PHONETYPE, Screen.OPENOPTS, Screen.TIMEZONE, Screen.LANGUAGE, Screen.DATA, Screen.LOOK, Screen.SOUNDSET, Screen.THISPHONE)

    private val connectionScreens = setOf(Screen.CONNECTIONS, Screen.WIFI, Screen.WIFINET, Screen.BT, Screen.BTDEV)
    /** The status-line symbols open the connection screens from anywhere: on top of where you are, or sideways when you are already in them. */
    private fun openConnection(to: Screen) { if (screen in connectionScreens) { screen = to; selected = 0 } else enter(to) }

    private val noTouch = setOf(Screen.SNAKE, Screen.PAIRS, Screen.SHOOTER, Screen.SEEDS)

    /** The screens a skin draws by hand; everything else is a list of rows. */
    private val customScreens = setOf(Screen.HOME, Screen.WEATHER, Screen.BATTERY, Screen.NOTE, Screen.MUSIC, Screen.THREAD, Screen.WRITE, Screen.CALC, Screen.GAMEHELP, Screen.SNAKE, Screen.PAIRS, Screen.SHOOTER, Screen.SEEDS, Screen.WAPAIR, Screen.DIAL, Screen.CALL, Screen.CALMONTH, Screen.EVENT, Screen.MAILMSG)

    /** How the current screen is laid out. On a grid the D-pad moves in four directions, so Left and Right stop being soft-key aliases. */
    val layout: Layout get() = when {
        screen == Screen.MENU && menuStyle == "grid" && items.isNotEmpty() -> Layout.GRID
        screen in customScreens -> Layout.CUSTOM
        else -> Layout.LIST
    }

    private var gridTop = 0

    /** The first row of tiles on screen when [visible] rows fit: it moves only as far as needed to keep the chosen tile in view. */
    fun gridFirstRow(visible: Int = GRID_ROWS): Int {
        val rows = (items.size + GRID_COLS - 1) / GRID_COLS
        val row = selected / GRID_COLS
        if (row < gridTop) gridTop = row else if (row > gridTop + visible - 1) gridTop = row - visible + 1
        gridTop = gridTop.coerceIn(0, (rows - visible).coerceAtLeast(0))
        return gridTop
    }

    /** Where a D-pad press takes the highlight on a grid of [n] tiles: Left and Right step through them in order, Up and Down move a row (wrapping within the column). */
    internal fun gridNeighbour(sel: Int, n: Int, key: Key): Int = when (key) {
        Key.LEFT -> (sel - 1 + n) % n
        Key.RIGHT -> (sel + 1) % n
        Key.DOWN -> if ((sel / GRID_COLS + 1) * GRID_COLS < n) minOf(sel + GRID_COLS, n - 1) else sel % GRID_COLS
        Key.UP -> if (sel >= GRID_COLS) sel - GRID_COLS else { val bottom = ((n - 1) / GRID_COLS) * GRID_COLS + sel % GRID_COLS; if (bottom < n) bottom else bottom - GRID_COLS }
        else -> sel
    }

    /** The name of the pixel icon that stands for a menu entry; entries without a picture of their own share a plain window. */
    fun iconFor(item: MenuItem): String = when {
        item.pkg == MUSIC_PKG -> "music"
        item.pkg == CAMERA_PKG || item.pkg in CAMERA_APPS -> "camera"
        item.pkg == APPS_PKG || item.pkg == RUNNING_PKG -> "apps"
        item.pkg == GAMES_PKG -> "games"
        item.pkg == CALC_PKG -> "calculator"
        item.pkg == SETTINGS_PKG -> "settings"
        item.pkg == NOTES_PKG -> "notes"
        item.pkg == MAIL_PKG -> "mail"
        item.pkg == CALENDAR_PKG -> "calendar"
        item.pkg == CLOCK_PKG -> "alarm"
        item.pkg == WEATHER_PKG -> "weather"
        item.pkg == CALLLOG_PKG || item.pkg == dialerApp -> "calls"
        item.pkg == messagesApp -> "messages"
        item.pkg == contactsApp -> "contacts"
        item.pkg.contains("whatsapp", ignoreCase = true) -> "chat"
        else -> "app"
    }

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
        if (screen !in rowScreens || screen in noTouch || layout == Layout.GRID) return false
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
        if (y < 46f && x in 105f..175f && screen != Screen.WIFI && wifiShown) { openConnection(Screen.WIFI); return Action.WifiLoad } // the Wi-Fi symbol in the status line
        if (y < 46f && x in 176f..215f && screen != Screen.BT && btShown) { openConnection(Screen.BT); return Action.BtLoad } // the Bluetooth symbol
        if (y < 46f && x < 105f && screen != Screen.CONNECTIONS && (dataShown || wifiShown)) { openConnection(Screen.CONNECTIONS); return Action.Redraw } // the signal and data symbols
        if (screen == Screen.MUSIC && music.active) { // touch: the big play/pause, and the previous and next words either side of it
            if (y in 300f..362f) return Action.SpectrumToggle // touch the bar to swap progress and spectrum
            if (y in 410f..500f && x in 180f..300f) return Action.MusicPlayPause
            if (y in 430f..490f && x < 150f) return Action.MusicPrevious
            if (y in 430f..490f && x > 330f) return Action.MusicNext
            return Action.None
        }
        if (screen == Screen.HOME) {
            if (weather != null && y in (weatherCy(Themes.byId(theme).skin == "mac") - 34f)..(weatherCy(Themes.byId(theme).skin == "mac") + 34f)) return openWeather()
            clockTarget().let { if (x in it.l..it.r && y in it.t..it.b) return openClock() }
            if (y in 455f..565f && badges.isNotEmpty()) return openBadge(badges[badgeAt(x)])
            return Action.None
        }
        if (layout == Layout.GRID) { // a tile is chosen by touching it
            val col = ((x - GRID_LEFT) / (GRID_TILE_W + GRID_GAP)).toInt()
            val row = ((y - GRID_TOP) / (GRID_TILE_H + GRID_GAP)).toInt()
            val idx = (gridFirstRow() + row) * GRID_COLS + col
            if (y >= GRID_TOP && x >= GRID_LEFT && col in 0 until GRID_COLS && row in 0 until GRID_ROWS && idx in items.indices) { selected = idx; return onKey(Key.OK) }
            return Action.None
        }
        if (screen in rowScreens && y >= 104f) {
            val idx = listFirst(LIST_VISIBLE) + ((y - 104f) / 62f).toInt()
            if (idx in rows.indices) { selected = idx; return onKey(Key.OK) }
        }
        return Action.None
    }

    /** The centre of badge [i] of [n] on the home screen's 480 wide grid. */
    fun badgeX(i: Int, n: Int): Float =
        if (n * 130f <= 460f) 240f + (i - (n - 1) / 2f) * 130f // they fit: centred
        else 20f + 65f + i * 130f - badgeOffset // too many to fit: the row starts at the left and is scrolled by [badgeOffset]

    /** How far the symbol row is scrolled left when it is wider than the screen (design pixels), and when a finger last moved it. */
    var badgeOffset = 0f
    var badgeDragAt = 0L

    /** How much wider than the screen the row is right now (0 when it fits). */
    fun badgeOverflow(): Float = (badges.size * 130f - 460f).coerceAtLeast(0f)

    /** A finger drags the row; true when it moved. */
    fun dragBadges(dx: Float): Boolean {
        val old = badgeOffset
        badgeOffset = (badgeOffset + dx).coerceIn(0f, badgeOverflow())
        badgeDragAt = System.currentTimeMillis()
        return badgeOffset != old
    }
    private fun badgeAt(x: Float): Int = badges.indices.minByOrNull { Math.abs(badgeX(it, badges.size) - x) } ?: 0

    /** Jump straight to what a home-screen symbol stands for: the text inbox, the WhatsApp chats or the call log. */
    private fun openBadge(b: Badge): Action = when (b.kind) {
        BadgeKind.SMS -> { threadWa = false; enter(Screen.INBOX, convos.indexOfFirst { it.unread > 0 }.coerceAtLeast(0)); Action.Redraw }
        BadgeKind.WHATSAPP -> { threadWa = true; enter(Screen.WHATSAPP, waConvos.indexOfFirst { it.unread > 0 }.coerceAtLeast(0)); if (waLinked) Action.WaOpen else Action.Redraw }
        BadgeKind.MISSED -> openCallLog(Screen.HOME)
        BadgeKind.NOTES -> { enter(Screen.NOTES); Action.Redraw }
        BadgeKind.MAIL -> { mailSentBox = false; enter(Screen.MAILLIST, mailInbox.indexOfFirst { it.unread }.coerceAtLeast(0)); Action.MailSync(false) }
        BadgeKind.MUSIC -> { provider = playingProvider ?: provider; screen = Screen.MUSIC; Action.Redraw } // music is playing: straight to Now playing
    }

    /** A tap on the weather on the home screen (touch modes only: School blocks touches before they get here). */
    fun openWeather(): Action { if (screen != Screen.HOME || weather == null) return Action.None; enter(Screen.WEATHER); return Action.Redraw }

    /** The numbers on the battery page; the activity fills them in while the page is open. */
    var battery: BatteryInfo? = null

    /** What the music screen shows; the activity refreshes it about once a second while that screen is open. */
    /** The built-in entries Settings lists as groups of their own (Notifications, Running Apps), where the mode shows them; the activity fills it in. */
    var extraItems: List<MenuItem> = emptyList()
    var music: io.github.durkimat.phoneguard.music.MusicInfo = io.github.durkimat.phoneguard.music.MusicInfo.NONE
    var musicApp = false
    /** Spotify account state: signed in, the lists the screens show, and a short status line for an empty list. */
    var spSignedIn = false
    /** What is left of the sleep timer ("20 min"), or empty when it is off; set by the activity. */
    var sleepLabel = ""
    /** Now playing shows the spectrum analyser in place of the progress bar (a per-phone choice, kept by the activity). */
    var spectrum = false
    /** False until HA has sent a Spotify Client ID. */
    var spConfigured = true
    var spPlaylists: List<io.github.durkimat.phoneguard.music.SpItem> = emptyList()
    /** The playlists as Spotify gave them, what is pinned, and when each was last played; [spPlaylists] is these in the screen's order (pins first, then recent). */
    var spRaw: List<io.github.durkimat.phoneguard.music.SpItem> = emptyList()
    var spPins: Set<String> = emptySet()
    var spPlayed: Map<String, Long> = emptyMap()
    /** The downloaded playlists (the phone's own record) and the ones being downloaded now. */
    var spOffline: Map<String, io.github.durkimat.phoneguard.music.PlaylistOffline.Offline> = emptyMap()
    var spBusy: Set<String> = emptySet()
    /** True while the playlists shown are the downloaded ones from the record, because Spotify's list could not be loaded. */
    var spOfflineOnly = false
    var spNow: () -> Long = { System.currentTimeMillis() }

    /** Put [raw] (Spotify's order) on the screen in the screen's order, with a star before the pinned ones; the cursor stays on the same playlist. */
    fun setPlaylists(raw: List<io.github.durkimat.phoneguard.music.SpItem>) {
        val cur = spPlaylists.getOrNull(selected)?.uri
        spRaw = raw
        spPlaylists = io.github.durkimat.phoneguard.music.PlaylistOrder.order(raw, spPins, spPlayed, spNow()).map { val m = io.github.durkimat.phoneguard.music.PlaylistOffline.marker(it.uri, spPins, spOffline, spBusy); if (m.isEmpty()) it else it.copy(label = m + it.label) }
        if (cur != null) spPlaylists.indexOfFirst { it.uri == cur }.let { if (it >= 0) selected = it }
        if (selected >= spPlaylists.size) selected = (spPlaylists.size - 1).coerceAtLeast(0)
    }
    var spResults: List<io.github.durkimat.phoneguard.music.SpItem> = emptyList()
    var spDevices: List<io.github.durkimat.phoneguard.music.SpDevice> = emptyList()
    var spStatus = ""
    /** Tells whether a Spotify device is this phone (set by the activity, which knows the phone's name). */
    var spIsThisPhone: (io.github.durkimat.phoneguard.music.SpDevice) -> Boolean = { false }
    /** True while the text entry is a Spotify search rather than a message. */
    var writeSearch = false
    /** Set while the text screen is asking for the password of this Wi-Fi network. */
    var writeWifi: WifiNet? = null
        private set

    /** The notifications, newest first (the activity fills this in). */
    var notes: List<io.github.durkimat.phoneguard.notes.NoteItem> = emptyList()
    private var noteKeyShown: String? = null

    private fun notesKey(key: Key): Action = move(key, notes.size + (if (notes.isEmpty()) 0 else 1)) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (notes.isEmpty()) Action.None else if (selected >= notes.size) Action.ClearNotes else { noteKeyShown = notes[selected].key; screen = Screen.NOTE; Action.Redraw }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
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

    /** Which provider's Now playing screen is open, which one is playing (null if none), and each provider's state for the Music menu: 0 idle, 1 paused, 2 playing. */
    var provider = Provider.SPOTIFY
    var playingProvider: Provider? = null
    var provStatus: Map<Provider, Int> = emptyMap()

    /**
     * A provider's Now playing screen is that provider's own screen: OK plays and pauses; Up and Down (2 and 8 on Spotify) change the volume;
     * Left and Right step to the previous and next track (Spotify: 4 and 6 too) or preset (radio); the left key under the screen opens the
     * provider's menu; 0 steps the sleep timer; the right key goes back. Radio: 1 to 9 play a preset and * swaps progress and spectrum.
     * Spotify: 5 plays and pauses, 7 swaps progress and spectrum, * is shuffle and # repeat. With nothing playing OK opens the provider's picker.
     */
    private fun musicKey(key: Key, digit: Int): Action {
        val d = if (key == Key.DIGIT) digit else -1
        val radio = provider == Provider.RADIO
        if (radio && d in 1..9) return if (d <= radioNames.size) Action.RadioPlay(d - 1) else Action.None
        return when {
            key == Key.SOFT_LEFT -> { screen = if (radio) Screen.RADIOMENU else Screen.SPOTIFYMENU; selected = 0; Action.Redraw }
            key == Key.OK || (d == 5 && !radio) -> when {
                music.active -> Action.MusicPlayPause
                radio -> if (radioNames.isNotEmpty()) { screen = Screen.RADIO; selected = 0; Action.Redraw } else Action.None
                spSignedIn -> { screen = Screen.PLAYLISTS; selected = 0; spStatus = "Loading..."; Action.SpLoad("playlists") }
                else -> Action.None
            }
            key == Key.RIGHT || (d == 6 && !radio) -> if (music.active) Action.MusicNext else Action.None
            key == Key.LEFT || (d == 4 && !radio) -> if (music.active) Action.MusicPrevious else Action.None
            key == Key.UP || (d == 2 && !radio) -> Action.MusicVolume(true)
            key == Key.DOWN || (d == 8 && !radio) -> Action.MusicVolume(false)
            key == Key.SOFT_RIGHT -> { goHome(); Action.Redraw } // Now playing is a destination: Back is always home
            key == Key.POUND -> openSourcePicker() // change source
            d == 0 -> Action.SleepStep
            (d == 7 && !radio) || (key == Key.STAR && radio) -> Action.SpectrumToggle
            key == Key.STAR && spSignedIn && !radio -> Action.SpShuffle
            d == 1 && spSignedIn && !radio -> Action.SpRepeat
            key == Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private val commonMusicRows: List<String> get() = listOf("Sleep timer: " + sleepLabel.ifEmpty { "off" }, "Bar shows: " + (if (spectrum) "spectrum" else "progress"))
    private fun commonMusicRow(row: String?): Action = when { row == null -> Action.None; row.startsWith("Sleep timer") -> Action.SleepStep; row.startsWith("Bar shows") -> Action.SpectrumToggle; else -> Action.None }

    /** What each provider's menu offers (the sleep timer and the bar are in each, as in the Music menu). */
    private val radioMenuRows: List<String> get() = listOf("Presets") + (if (radioMode == "any") listOf("Find station", "Browse stations") else emptyList()) + "Change source"
    private val spotifyMenuRows: List<String> get() = (if (spSignedIn) listOf("Playlists", "Search", "Play on...") else if (spConfigured) listOf("Sign in to Spotify") else listOf("Spotify: set up in HA")) + "Change source" + (if (spSignedIn) listOf("Sign out of Spotify") else emptyList())

    private fun radioMenuKey(key: Key): Action = move(key, radioMenuRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (val r = radioMenuRows.getOrNull(selected)) {
            "Presets" -> { screen = Screen.RADIO; selected = if (provider == Provider.RADIO && music.active) radioIndex.coerceAtLeast(0) else 0; Action.Redraw }
            "Find station" -> { startWrite("", Screen.RADIOMENU); writeField = 1; writeSearch = true; writeRadio = true; Action.Redraw }
            "Browse stations" -> { browseLevel = 0; screen = Screen.RADIOBROWSE; selected = 0; Action.Redraw }
            "Change source" -> openSourcePicker()
            else -> Action.None
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MUSIC; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun spotifyMenuKey(key: Key): Action = move(key, spotifyMenuRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (val r = spotifyMenuRows.getOrNull(selected)) {
            "Playlists" -> { screen = Screen.PLAYLISTS; selected = 0; spStatus = "Loading..."; Action.SpLoad("playlists") }
            "Search" -> startSearch()
            "Play on..." -> { screen = Screen.DEVICES; selected = 0; spStatus = "Loading..."; Action.SpLoad("devices") }
            "Sign out of Spotify" -> Action.SpSignOut
            "Sign in to Spotify" -> Action.SpSignIn
            "Change source" -> openSourcePicker()
            else -> Action.None
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MUSIC; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** The presets: the stations in order (the first nine are the number keys). */
    private val radioRows: List<String> get() = radioNames.ifEmpty { listOf("No stations") }

    private fun radioKey(key: Key): Action = move(key, maxOf(radioRows.size, 1)) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (selected in radioNames.indices) { provider = Provider.RADIO; screen = Screen.MUSIC; Action.RadioPlay(selected) } else Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.RADIOMENU; selected = 0; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** Stations found by browsing or searching, and what to say while there are none yet. */
    var radioRes: List<io.github.durkimat.phoneguard.music.RadioItem> = emptyList()
    var radioStatus = ""
    var radioResTitle = "Stations"
    private var browseLevel = 0
    private var resFromFind = false
    var writeRadio = false

    private val radioBrowseRows: List<String> get() = when (browseLevel) {
        1 -> io.github.durkimat.phoneguard.music.RadioBrowser.genres.map { it.replaceFirstChar { c -> c.uppercase() } }
        2 -> io.github.durkimat.phoneguard.music.RadioBrowser.countries.map { it.first }
        else -> listOf("Popular in the UK", "Genres", "Countries")
    }

    private fun radioBrowseKey(key: Key): Action = move(key, radioBrowseRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (browseLevel) {
            1 -> io.github.durkimat.phoneguard.music.RadioBrowser.genres.getOrNull(selected)?.let { loadRadio("tag", it, it.replaceFirstChar { c -> c.uppercase() }) } ?: Action.None
            2 -> io.github.durkimat.phoneguard.music.RadioBrowser.countries.getOrNull(selected)?.let { loadRadio("country", it.second, it.first) } ?: Action.None
            else -> when (selected) { 0 -> loadRadio("top", "GB", "Popular in the UK"); 1 -> { browseLevel = 1; selected = 0; Action.Redraw }; 2 -> { browseLevel = 2; selected = 0; Action.Redraw }; else -> Action.None }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { if (browseLevel > 0) { selected = browseLevel - 1; browseLevel = 0 } else { screen = Screen.RADIOMENU; selected = radioMenuRows.indexOf("Browse stations").coerceAtLeast(0) }; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun loadRadio(kind: String, arg: String, title: String): Action { resFromFind = false; radioRes = emptyList(); radioStatus = "Loading..."; radioResTitle = title; screen = Screen.RADIORES; selected = 0; return Action.RadioLoad(kind, arg) }

    private fun radioResKey(key: Key): Action = move(key, maxOf(radioRes.size, 1)) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> radioRes.getOrNull(selected)?.let { provider = Provider.RADIO; screen = Screen.MUSIC; Action.RadioPlayUrl(it.name, it.url) } ?: Action.None
        Key.STAR -> radioRes.getOrNull(selected)?.let { Action.RadioFav(it.name, it.url) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { if (resFromFind) { screen = Screen.RADIOMENU; selected = radioMenuRows.indexOf("Find station").coerceAtLeast(0) } else { screen = Screen.RADIOBROWSE; selected = 0 }; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** The radio station names in preset order, the setting for this mode ("off", "presets", "any") and the preset playing now; the activity keeps them current. */
    var radioNames: List<String> = emptyList()
    var radioMode = "any"
    var radioIndex = -1

    private val musicMenuRows: List<String> get() = (if (radioMode != "off") listOf("Internet Radio") else emptyList()) + listOf("Spotify") + commonMusicRows

    /** A small symbol for each row of the Music menu: 2 playing, 1 paused, 0 none. */
    val rowMarks: List<Int> get() = if (screen == Screen.MUSICMENU) musicMenuRows.map { r -> when (r) { "Internet Radio" -> provStatus[Provider.RADIO] ?: 0; "Spotify" -> provStatus[Provider.SPOTIFY] ?: 0; else -> 0 } } else emptyList()

    // the Phone entry and the green key open the dial pad (see the dialler notes in the backlog)
    private fun openPhoneMenu(): Action { dialNumber = ""; dialler = true; enter(Screen.DIAL); return Action.Redraw }


    private fun soundTestKey(key: Key): Action = move(key, io.github.durkimat.phoneguard.SoundTest.routes.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> Action.SoundTest(selected)
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** The apps that look to be running, set by the activity while the Running Apps screen is open. */
    var running: List<RunningApp> = emptyList()

    /**
     * Left key stops the highlighted app; OK switches to it. Where the phone has its own screens for an app (Music for Spotify, the
     * dial pad for the Phone, Messages, Contacts, the Camera, our WhatsApp) OK goes to those, not to the real app.
     */
    private fun runningKey(key: Key): Action = move(key, maxOf(running.size, 1)) ?: when (key) {
        Key.SOFT_LEFT, Key.LEFT -> running.getOrNull(selected)?.let { Action.KillApp(it.pkg) } ?: Action.None
        Key.OK -> running.getOrNull(selected)?.let { switchTo(it.pkg) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun switchTo(pkg: String): Action = when {
        pkg == io.github.durkimat.phoneguard.music.MusicController.SPOTIFY_PKG && items.any { it.pkg == MUSIC_PKG } -> { provider = Provider.SPOTIFY; screen = Screen.MUSIC; Action.Redraw }
        pkg == dialerApp -> openPhoneMenu()
        pkg == messagesApp -> openMessages()
        pkg == contactsApp -> openContacts(Screen.HOME)
        pkg in CAMERA_APPS && items.any { it.pkg == CAMERA_PKG } -> Action.OpenCamera
        pkg == "com.whatsapp" && waEnabled -> { screen = Screen.WHATSAPP; selected = 0; threadWa = true; if (waLinked) Action.WaOpen else Action.Redraw }
        else -> Action.Launch(pkg)
    }

    // ---- Wi-Fi and Bluetooth (the activity fills the lists in while these screens are open)

    var wifiOn = false
    var wifiNets: List<WifiNet> = emptyList()
    var wifiSel: WifiNet? = null
    var wifiShown = true
    var btShown = true
    var dataShown = false
    /** Wi-Fi, Bluetooth and mobile data as the status line shows them: 0 on, not connected; 1 connected; 2 switched off; -1 not available here. */
    var connWifi = 0; var connBt = 0; var connData = -1

    var batteryPct = -1

    private val connectionRows: List<String> get() = listOf(
        "Wi-Fi: " + (if (connWifi == 2) "Off" else "On"), "Bluetooth: " + (if (connBt == 2) "Off" else "On"),
        "Mobile data" + (dataView?.let { ": " + io.github.durkimat.phoneguard.core.DataView.amount(it.left) } ?: ""),
        "Battery" + (if (batteryPct >= 0) ": $batteryPct%" else ""),
    )

    private fun connectionsKey(key: Key): Action = move(key, connectionRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (selected) {
            0 -> { screen = Screen.WIFI; selected = 0; Action.WifiLoad }
            1 -> { screen = Screen.BT; selected = 0; Action.BtLoad }
            2 -> { enter(Screen.DATA); Action.DataLoad }
            else -> { enter(Screen.BATTERY); Action.Redraw }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }
    var btOn = false
    var btDevs: List<BtDev> = emptyList()
    var btSel: BtDev? = null
    var netStatus = ""

    /** ">" connected, "*" saved (or paired), a blank otherwise: the marker comes first so a long name cannot push it off the screen. */
    private fun wifiLabel(n: WifiNet) = (if (n.connected) "> " else if (n.saved) "* " else "  ") + n.ssid
    private fun btLabel(d: BtDev) = (if (d.connected) "> " else if (d.bonded) "* " else "  ") + d.name

    private val wifiNetRows: List<String> get() {
        val n = wifiSel ?: return emptyList()
        return buildList {
            if (n.connected) { /* already connected */ } else if (n.saved) add("Connect") else if (!n.secured) add("Join") else add("Join with password")
            if (n.saved && n.secured) add("Change password")
            if (n.saved) add("Forget")
        }
    }

    private val btDevRows: List<String> get() {
        val d = btSel ?: return emptyList()
        return if (!d.bonded) listOf("Pair") else listOf(if (d.connected) "Disconnect" else "Connect", "Forget")
    }

    private fun wifiKey(key: Key): Action = move(key, rows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (selected == 0) Action.WifiToggle else wifiNets.getOrNull(selected - 1)?.let { wifiSel = it; screen = Screen.WIFINET; selected = 0; Action.Redraw } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = if (edition == "first_boot") Screen.FIRSTRUN else Screen.CONNECTIONS; selected = 0; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun wifiNetKey(key: Key): Action = move(key, wifiNetRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            val n = wifiSel
            when (wifiNetRows.getOrNull(selected)) {
                "Connect" -> { screen = Screen.WIFI; selected = 0; if (n != null) Action.WifiConnect(n.id) else Action.Redraw }
                "Join" -> { screen = Screen.WIFI; selected = 0; if (n != null) Action.WifiJoin(n.ssid, "", n.caps, n.id) else Action.Redraw }
                "Join with password", "Change password" -> if (n != null) { startWrite("", Screen.WIFINET, n.ssid); writeField = 1; writeWifi = n; writeMode = 0; Action.Redraw } else Action.None
                "Forget" -> { screen = Screen.WIFI; selected = 0; if (n != null) Action.WifiForget(n.id) else Action.Redraw }
                else -> Action.None
            }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.WIFI; selected = 0; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun btKey(key: Key): Action = move(key, rows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when {
            selected == 0 -> Action.BtToggle
            selected == 1 -> Action.BtSearch
            else -> btDevs.getOrNull(selected - 2)?.let { btSel = it; screen = Screen.BTDEV; selected = 0; Action.Redraw } ?: Action.None
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = if (edition == "first_boot") Screen.FIRSTRUN else Screen.CONNECTIONS; selected = if (edition == "first_boot") 0 else 1; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun btDevKey(key: Key): Action = move(key, btDevRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            val d = btSel
            when (btDevRows.getOrNull(selected)) {
                "Pair" -> { screen = Screen.BT; selected = 0; if (d != null) Action.BtPair(d.address) else Action.Redraw }
                "Connect" -> { screen = Screen.BT; selected = 0; if (d != null) Action.BtConnect(d.address, true) else Action.Redraw }
                "Disconnect" -> { screen = Screen.BT; selected = 0; if (d != null) Action.BtConnect(d.address, false) else Action.Redraw }
                "Forget" -> { screen = Screen.BT; selected = 0; if (d != null) Action.BtForget(d.address) else Action.Redraw }
                else -> Action.None
            }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.BT; selected = 0; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** "Change source": the Music menu with Home beneath it, so Back from it goes home however Now playing was reached. */
    private fun openSourcePicker(): Action { trail.clear(); trail.addLast(Frame(Screen.HOME, 0)); screen = Screen.MUSICMENU; selected = 0; return Action.Redraw }

    private fun musicMenuKey(key: Key): Action = move(key, musicMenuRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (val r = musicMenuRows.getOrNull(selected)) {
            "Internet Radio" -> { provider = Provider.RADIO; selected = 0; if ((provStatus[Provider.RADIO] ?: 0) == 0 && radioNames.isNotEmpty()) screen = Screen.RADIO else screen = Screen.MUSIC; Action.Redraw }
            "Spotify" -> { provider = Provider.SPOTIFY; selected = 0; if ((provStatus[Provider.SPOTIFY] ?: 0) == 0 && spSignedIn) { screen = Screen.PLAYLISTS; spStatus = "Loading..."; Action.SpLoad("playlists") } else { screen = Screen.MUSIC; Action.Redraw } }
            else -> commonMusicRow(r)
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }


    // ---- Clock: alarms, timer, stopwatch, set time. The activity loads the saved state in and saves what the model changes (the callbacks).
    var alarms: List<Alarm> = emptyList()
    var clockPrefs = ClockPrefs()
    var timer = TimerState()
    var stopwatch = Stopwatch()
    /** Alarms may ring in the mode in force (the screens say so when not), and whether "Set time" is offered. */
    var alarmsAllowed = true
    var timeSettable = false
    var onAlarms: (List<Alarm>) -> Unit = {}
    var onClockPrefs: (ClockPrefs) -> Unit = {}
    /** The volume step a new alarm starts with (the companion's default, else 5). */
    var defaultAlarmVol = 5
    /** Plays an alarm tune on a loop at a volume step while it is being chosen; the preview stops when the form is left. */
    var onPreviewLoop: (String, Int) -> Unit = { _, _ -> }
    var onPreviewStop: () -> Unit = {}
    var onTimer: (TimerState) -> Unit = {}
    var onStopwatch: (Stopwatch) -> Unit = {}
    var onSetTime: (Long) -> Boolean = { false }
    /** The tune picked for calls ("ring"), texts ("sms") and WhatsApp ("wa"): a tune id or "stock". The activity saves a pick and plays a preview. */
    var tonePicks: Map<String, String> = emptyMap()
    var onTonePick: (String, String) -> Unit = { _, _ -> }
    var onPreview: (String) -> Unit = {}
    var ringKind = "alarm"; var ringTime = ""; var ringQuiet = false

    fun setRing(kind: String, time: String, quiet: Boolean) {
        ringKind = kind; ringTime = time; ringQuiet = quiet
        if (screen != Screen.RING) { trail.clear(); screen = Screen.RING; selected = 0 }
    }

    /** Where the clock sits on the home screen, for the keypad highlight and a touch. */
    fun clockTarget(): HomeTarget { val y = clockY(Themes.byId(theme).skin == "mac"); return HomeTarget("clock", 50f, y - 100f, 430f, y + 12f) }

    fun openTimer(): Action { enter(Screen.TIMER); return Action.Redraw }

    fun openClock(): Action { enter(Screen.CLOCK); return Action.Redraw }

    private val clockRows: List<String> get() = buildList {
        add("Alarms"); add("Timer"); add("Stopwatch")
        add(if (timeSettable) "Set time" else "Set time: from network")
    }

    private fun clockKey(key: Key): Action {
        val r = clockRows
        return move(key, r.size) ?: when (key) {
            Key.OK, Key.SOFT_LEFT -> when (r.getOrNull(selected)) {
                "Alarms" -> { enter(Screen.ALARMS); if (!alarmsAllowed) notify("Alarms are off in this mode"); Action.Redraw }
                "Timer" -> { enter(Screen.TIMER); Action.Redraw }
                "Stopwatch" -> { enter(Screen.STOPWATCH); Action.Redraw }
                "Set time", "Set time: from network" -> if (timeSettable) { pushForm(setTimeForm()); Action.Redraw } else { notify("Time is set from the network"); Action.Redraw }
                else -> Action.None
            }
            Key.SOFT_RIGHT -> { leave(); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private val alarmRows: List<String> get() = alarms.map { AlarmMath.row(it) } + (if (alarms.size < AlarmMath.MAX_ALARMS) listOf("Add alarm") else emptyList())

    private fun alarmsKey(key: Key): Action {
        val n = alarmRows.size
        return move(key, n) ?: when (key) {
            Key.OK, Key.SOFT_LEFT -> {
                val a = alarms.getOrNull(selected) ?: Alarm(AlarmMath.freeId(alarms), 7, 0, vol = defaultAlarmVol)
                if (a.id == 0) Action.None else { pushForm(alarmForm(a, alarms.none { it.id == a.id })); Action.Redraw }
            }
            Key.LEFT, Key.RIGHT -> { // switch the chosen alarm on or off without opening it
                val a = alarms.getOrNull(selected) ?: return Action.None
                saveAlarm(a.copy(on = !a.on), false); Action.Redraw
            }
            Key.SOFT_RIGHT -> { leave(); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun saveAlarm(a: Alarm, announce: Boolean) {
        alarms = (alarms.filter { it.id != a.id } + a).sortedBy { it.id }.take(AlarmMath.MAX_ALARMS)
        onAlarms(alarms)
        if (announce || a.on) AlarmMath.next(a, System.currentTimeMillis(), zone)?.let { if (a.on && announce) notify("Rings " + AlarmMath.until(it - System.currentTimeMillis())) }
    }

    private fun alarmForm(a0: Alarm, isNew: Boolean): Form {
        var a = a0.let { if (it.vol == 0) it.copy(vol = clockPrefs.volume, gradual = it.gradual || clockPrefs.crescendo) else it } // an alarm saved before volumes were per alarm takes the old shared setting
        val rows: () -> List<FormRow> = {
            buildList {
                add(FormRow("Alarm", { if (a.on) "On" else "Off" }, onStep = { _, _ -> a = a.copy(on = !a.on) }, okStep = true))
                add(FormRow("Hour", { "%02d".format(a.hour) }, onStep = { d, big -> a = a.copy(hour = ((a.hour + d * (if (big) 6 else 1)) % 24 + 24) % 24) }))
                add(FormRow("Minute", { "%02d".format(a.minute) }, onStep = { d, big -> a = a.copy(minute = ((a.minute + d * (if (big) 10 else 1)) % 60 + 60) % 60) }))
                AlarmMath.dayNames.forEachIndexed { i, n ->
                    add(FormRow(n, { if (a.days and (1 shl i) != 0) "On" else "-" }, onStep = { _, _ -> a = a.copy(days = a.days xor (1 shl i)) }, okStep = true))
                }
                add(FormRow("Repeat", { AlarmMath.daysLabel(a.days) }, onStep = { d, _ ->
                    val presets = listOf(0, AlarmMath.WEEKDAYS, AlarmMath.WEEKEND, AlarmMath.EVERY)
                    val i = presets.indexOf(a.days).let { if (it < 0) (if (d > 0) -1 else 0) else it }
                    a = a.copy(days = presets[((i + d) % presets.size + presets.size) % presets.size])
                }))
                add(FormRow("Sound", { Tones.byId(a.tone).name }, onStep = { d, _ ->
                    val i = Tones.all.indexOfFirst { it.id == a.tone }.coerceAtLeast(0)
                    a = a.copy(tone = Tones.all[((i + d) % Tones.all.size + Tones.all.size) % Tones.all.size].id); onPreviewLoop(a.tone, a.vol)
                }))
                add(FormRow("Volume", { "#".repeat(a.vol) + "-".repeat(AlarmMath.MAX_VOLUME - a.vol) }, onStep = { d, _ ->
                    a = a.copy(vol = (a.vol + d).coerceIn(1, AlarmMath.MAX_VOLUME)); onPreviewLoop(a.tone, a.vol)
                }))
                add(FormRow("Gradual", { if (a.gradual) "Starts quiet, gets louder" else "Off" }, onStep = { _, _ -> a = a.copy(gradual = !a.gradual) }, okStep = true))
                if (!isNew) add(FormRow("Delete this alarm", run = { alarms = alarms.filter { it.id != a.id }; onAlarms(alarms); leave(); Action.Redraw }))
            }
        }
        return Form(if (isNew) "New alarm" else "Alarm", "Save", rows) { saveAlarm(a.copy(on = a.on || isNew), true); leave(); Action.Redraw }
    }

    /** The voice of the phone's own tunes ("classic" or "soft"); the activity saves it, rewrites the saved ringtone and text tone and plays a sample. */
    var toneStyle = "classic"
    var onToneStyle: (String) -> Unit = {}

    /** What the Sound screens show: the ringer (0 normal, 1 vibrate, 2 silent), why it cannot be changed (the mode's words, or null), and the four volumes as current and maximum step. */
    class SoundInfo(val ringer: Int, val lockedBy: String?, val vols: List<Pair<Int, Int>>)
    var soundInfo: () -> SoundInfo = { SoundInfo(0, null, List(4) { 3 to 7 }) }
    var onRinger: (Int) -> Unit = {}
    /** Sets volume [which] (0 ringtone, 1 messages, 2 media, 3 phone call) to a step and plays a sample on that channel. */
    var onVolume: (Int, Int) -> Unit = { _, _ -> }

    private fun ringerForm(): Form {
        val names = listOf("Normal", "Vibrate", "Silent")
        return Form("Ringer mode", "Done", {
            val si = soundInfo()
            names.mapIndexed { i, n ->
                FormRow(n, { if (si.ringer == i) "(x)" else "( )" }, onStep = { _, _ -> pick(i) }, okStep = true)
            } + FormRow(si.lockedBy ?: "Alarms still ring in Silent.", run = { Action.Redraw })
        }) { leave(); Action.Redraw }
    }
    private fun pick(i: Int) { val si = soundInfo(); if (si.lockedBy != null) notify(si.lockedBy) else onRinger(i) }

    private fun volumesForm(): Form {
        val names = listOf("Ringtone", "Messages", "Media", "Phone call")
        return Form("Volumes", "Done", {
            names.mapIndexed { i, n ->
                FormRow(n, { val (cur, max) = soundInfo().vols[i]; "#".repeat(Math.round(cur * 7f / max.coerceAtLeast(1))) + "-".repeat(7 - Math.round(cur * 7f / max.coerceAtLeast(1))) },
                    onStep = { d, _ -> val (cur, max) = soundInfo().vols[i]; onVolume(i, (cur + d).coerceIn(0, max)) })
            }
        }) { leave(); Action.Redraw }
    }

    private fun tonesForm(): Form {
        val opts = listOf("stock") + Tones.all.map { it.id }
        fun row(label: String, slot: String) = FormRow(label, { tonePicks[slot]?.takeIf { it != "stock" }?.let { Tones.byId(it).name } ?: "Phone's own" }, onStep = { d, _ ->
            val i = opts.indexOf(tonePicks[slot] ?: "stock").coerceAtLeast(0)
            val n = opts[((i + d) % opts.size + opts.size) % opts.size]
            tonePicks = tonePicks + (slot to n); onTonePick(slot, n); onPreview(n)
        })
        val style = FormRow("Tune style", { Tones.Style.of(toneStyle).label }, onStep = { _, _ ->
            toneStyle = if (toneStyle == Tones.Style.SOFT.id) Tones.Style.CLASSIC.id else Tones.Style.SOFT.id; onToneStyle(toneStyle)
        }, okStep = true)
        return Form("Tones", "Done", { listOf(row("Calls", "ring"), row("Text messages", "sms"), row("WhatsApp", "wa"), style) }) { leave(); Action.Redraw }
    }

    private fun setTimeForm(): Form {
        var at = java.time.ZonedDateTime.now(zone).withSecond(0).withNano(0)
        val rows: () -> List<FormRow> = {
            listOf(
                FormRow("Date", { Cal.dayName(at.toLocalDate()) + " " + at.dayOfMonth + " " + Cal.monthName(at.monthValue).take(3) + " " + at.year }, onStep = { d, big -> at = at.plusDays(d * (if (big) 7L else 1L)) }),
                FormRow("Hour", { "%02d".format(at.hour) }, onStep = { d, big -> at = at.withHour(((at.hour + d * (if (big) 6 else 1)) % 24 + 24) % 24) }),
                FormRow("Minute", { "%02d".format(at.minute) }, onStep = { d, big -> at = at.withMinute(((at.minute + d * (if (big) 10 else 1)) % 60 + 60) % 60) }),
            )
        }
        return Form("Set time", "Set", rows) {
            val ok = onSetTime(at.toInstant().toEpochMilli())
            leave(); notify(if (ok) "Time set" else "This phone can't change the time"); Action.Redraw
        }
    }

    private fun timerKey(key: Key, digit: Int, nowMs: Long): Action {
        val t = timer
        val idle = !t.running && !t.paused
        fun set(n: TimerState) { timer = n; onTimer(n) }
        return when (key) {
            Key.OK, Key.SOFT_LEFT -> { set(if (t.running) t.pause(nowMs) else t.start(nowMs)); Action.Redraw }
            Key.UP -> { if (idle) set(t.copy(length = (t.length + 60).coerceAtMost(99 * 3600))); Action.Redraw }
            Key.DOWN -> { if (idle) set(t.copy(length = (t.length - 60).coerceAtLeast(10))); Action.Redraw }
            Key.RIGHT -> { if (idle) set(t.copy(length = (t.length + 10).coerceAtMost(99 * 3600))); Action.Redraw }
            Key.LEFT -> { if (idle) set(t.copy(length = (t.length - 10).coerceAtLeast(10))); Action.Redraw }
            Key.DIGIT -> { if (idle) set(t.copy(length = (if (digit == 0) 10 else digit) * 60)); Action.Redraw }
            Key.STAR -> { if (!idle) set(t.reset()); Action.Redraw }
            Key.SOFT_RIGHT -> { leave(); Action.Redraw } // a running timer carries on
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun stopwatchKey(key: Key, nowMs: Long): Action {
        fun set(n: Stopwatch) { stopwatch = n; onStopwatch(n) }
        return when (key) {
            Key.OK, Key.SOFT_LEFT -> { set(stopwatch.toggle(nowMs)); Action.Redraw }
            Key.DOWN, Key.POUND -> { set(stopwatch.lap(nowMs)); Action.Redraw }
            Key.STAR -> { if (!stopwatch.running) set(Stopwatch()); Action.Redraw }
            Key.SOFT_RIGHT -> { leave(); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun ringKey(key: Key): Action = when (key) {
        Key.OK, Key.SOFT_LEFT -> if (ringKind == "timer") Action.AlarmStop else Action.AlarmSnooze
        Key.SOFT_RIGHT, Key.END -> Action.AlarmStop
        else -> Action.None
    }

    // ---- "Navigate": type or speak a place, pick it, see the ways to get there (the Worker's /api/geo calls; docs/where-to-protocol.md)
    var whereResults: List<io.github.durkimat.phoneguard.where.Place> = emptyList()
        private set
    var whereRows: List<io.github.durkimat.phoneguard.where.JourneyRow> = emptyList()
        private set
    var whereLegs: List<io.github.durkimat.phoneguard.where.Leg> = emptyList()
        private set
    var wherePlace: io.github.durkimat.phoneguard.where.Place? = null
        private set
    var whereJourney: io.github.durkimat.phoneguard.where.Journey? = null
        private set
    private var whereWalk: io.github.durkimat.phoneguard.where.Plan? = null
    /** The leg the map is looking at (Left and Right step through the legs). */
    var whereMapLeg = 0
        private set
    /** The pieces of the route to draw, the leg in focus marked. */
    val whereMapSegments: List<io.github.durkimat.phoneguard.where.MapSeg> get() = io.github.durkimat.phoneguard.where.MapPicture.segments(whereLegs, whereMapLeg)
    /** The child's dot and which way the phone faces (set by the activity while the map is open); null until there is a fix. */
    var whereDot: Pair<Double, Double>? = null
        private set
    var whereHeading: Float? = null
        private set
    fun setWhereFix(lat: Double?, lon: Double?, heading: Float?) { if (lat != null && lon != null) whereDot = lat to lon; if (heading != null) whereHeading = heading }
    fun clearWhereFix() { whereDot = null; whereHeading = null }
    /** Debug builds only: true while a made-up journey is shown, so the real position tracker stays off. */
    var whereDemo = false
        private set
    /** Debug builds only: puts the made-up journey ([io.github.durkimat.phoneguard.where.WhereDemo]) on the map. */
    fun loadWhereDemo() {
        val plan = io.github.durkimat.phoneguard.where.Where.parsePlan(io.github.durkimat.phoneguard.where.WhereDemo.PLAN_JSON)
        wherePlace = io.github.durkimat.phoneguard.where.WhereDemo.PLACE; whereWalk = plan; whereFrom = "home"
        whereRows = io.github.durkimat.phoneguard.where.Where.journeyRows(plan, java.time.ZoneId.of("UTC"))
        whereJourney = plan.options[0]; whereLegs = plan.options[0].legs; whereMapLeg = 1; whereZoom = 0; whereDemo = true; clearWhereFix()
        screen = Screen.WHEREMAP
    }
    /** True when the dot is more than about 100 m from the route being shown. */
    val whereOffRoute: Boolean get() = whereDot?.let { d -> io.github.durkimat.phoneguard.where.WhereGeo.offRoute(d.first, d.second, io.github.durkimat.phoneguard.where.MapPicture.allPoints(whereMapSegments)) } == true
    /** The words under the map: the leg in focus, or the off-route notice, or that there is no position. */
    val whereMapCaption: String get() = if (whereOffRoute) "Off route: OK to re-plan" else whereLegs.getOrNull(whereMapLeg)?.let { io.github.durkimat.phoneguard.where.Where.legLine(it) } ?: ""
    private var whereQuery = ""
    /** The places picked before (newest first), read when "Navigate" opens; set by the activity. */
    var whereRecentsLoader: () -> List<io.github.durkimat.phoneguard.where.Place> = { emptyList() }
    /** True while the places list shows the recent places (first row "Search for a place...") instead of search results. */
    var whereRecent = false
        private set
    /** The map picture's fit, and the tiles it needs. */
    /** How far the map is zoomed: 0 shows the whole route, each step in doubles the scale (keys 3 in and 9 out; 6 shows the whole route again). */
    var whereZoom = 0
        private set
    val whereMapFit: io.github.durkimat.phoneguard.where.MapFit? get() = io.github.durkimat.phoneguard.where.MapPicture.fit(whereMapSegments, whereDot, whereZoom)
    /** The spot the edge arrow points at: the end of the leg being looked at (the next stop, or the destination on the last leg). */
    val whereMapTarget: Pair<Double, Double>? get() = whereMapSegments.firstOrNull { it.current }?.points?.lastOrNull() ?: whereMapSegments.lastOrNull()?.points?.lastOrNull()
    val whereMapTiles: List<io.github.durkimat.phoneguard.where.Tile> get() = if (screen == Screen.WHEREMAP) whereMapFit?.tiles().orEmpty() else emptyList()
    /** What the list says when it is empty ("Searching...", "No places found", "Connect first"...), and where the way starts from ("my position" or "home"). */
    var whereStatus = ""
    var whereFrom = ""
    /** Where a search by kind of place looked: "from last known position", "near home", "near Hatfield"; empty when the phone had a current fix. Shown under the list. */
    var whereNear = ""
        private set
    /** The kind of place asked for when there was no position: said again once the child names a town. */
    private var whereTownFor = ""

    /** The short kind line drawn under each name in the places list (empty where the Worker sent none). */
    val rowSubs: List<String> get() = if (screen == Screen.WHEREPLACES) (if (whereRecent) listOf("") else emptyList()) + whereResults.map { io.github.durkimat.phoneguard.where.Where.placeSub(it) } else emptyList()
    /** Detail pages are for places found by kind in this search; the recent places go straight to the ways there. */
    private fun hasDetail(p: io.github.durkimat.phoneguard.where.Place?) = p != null && p.kind == "poi" && !whereRecent

    /** Opens "Navigate" with the words already typed (a spoken "navigate to ..."); the child still picks the place. */
    fun openWhere(text: String): Action = whereStart(text)

    /** A spoken "navigate to X": the search runs at once and the places are listed; the child still picks the place. */
    fun searchWhere(text: String): Action {
        whereRecent = false; whereQuery = text; whereResults = emptyList(); whereStatus = "Searching..."; screen = Screen.WHEREPLACES; selected = 0
        return Action.WhereSearch(text)
    }

    private fun whereStart(text: String): Action {
        if (text.isEmpty()) {
            val recents = whereRecentsLoader()
            if (recents.isNotEmpty()) { whereRecent = true; whereResults = recents; whereStatus = ""; screen = Screen.WHEREPLACES; selected = 1.coerceAtMost(recents.size); return Action.Redraw }
        }
        return whereWrite(text)
    }

    private fun whereWrite(text: String): Action {
        whereRecent = false
        startWrite("", Screen.MENU); writeField = 1; writeHead = "Navigate"
        writeText = text; cursor = text.length
        writeDone = { q ->
            if (q.length < 2) { notice = "Type at least two letters"; whereStart(q) }
            else { whereRecent = false; whereQuery = q; whereResults = emptyList(); whereStatus = "Searching..."; screen = Screen.WHEREPLACES; selected = 0; Action.WhereSearch(q) }
        }
        return Action.Redraw
    }

    /** Under the places list: the distance and kind of the highlighted place. */
    val wherePlaceNote: String get() = if (screen == Screen.WHEREPLACES) whereResults.getOrNull(selected - (if (whereRecent) 1 else 0))?.let { p -> listOf(io.github.durkimat.phoneguard.where.Where.placeNote(p), if (p.kind == "poi") whereNear else "").filter { it.isNotEmpty() }.joinToString("  ") }.orEmpty() else ""

    /** The places Worker answered (or why it could not). */
    fun setWherePlaces(list: List<io.github.durkimat.phoneguard.where.Place>, status: String, near: String = "") { whereResults = io.github.durkimat.phoneguard.where.Where.nearestFirst(list); whereStatus = if (list.isEmpty()) status else ""; whereNear = near; selected = 0 }

    /** The Worker needs a position to look for a kind of place and the phone has none: ask for a town, then look around it ([Action.WhereTown]). */
    fun askWhereTown(query: String) {
        whereTownFor = query; whereRecent = false
        startWrite("", Screen.MENU); writeField = 1; writeHead = "Which town?"; writeText = ""; cursor = 0
        writeDone = { t -> if (t.length < 2) { notice = "Type at least two letters"; askWhereTown(query); Action.Redraw } else { whereResults = emptyList(); whereStatus = "Searching..."; whereNear = ""; screen = Screen.WHEREPLACES; selected = 0; Action.WhereTown(t, whereTownFor) } }
        screen = Screen.WRITE
    }

    /** The ways to [to] arrived; [from] says where they start. */
    fun setWherePlan(plan: io.github.durkimat.phoneguard.where.Plan?, from: String, status: String) {
        whereFrom = from; whereWalk = plan
        whereRows = if (plan == null) emptyList() else io.github.durkimat.phoneguard.where.Where.journeyRows(plan)
        whereStatus = if (whereRows.isEmpty()) status.ifEmpty { "No way found" } else ""
        if (screen == Screen.WHEREJOURNEYS) selected = 0
    }

    private fun whereKey(key: Key, digit: Int = -1): Action {
        if (screen == Screen.WHEREMAP) return when (key) {
            Key.DIGIT -> when (digit) {
                9 -> { whereZoom = (whereZoom - 1).coerceAtLeast(io.github.durkimat.phoneguard.where.MapFit.ZOOM_OUT); Action.Redraw }
                3 -> { whereZoom = (whereZoom + 1).coerceAtMost(io.github.durkimat.phoneguard.where.MapFit.ZOOM_IN); Action.Redraw }
                6 -> { whereZoom = 0; Action.Redraw }
                else -> Action.None
            }
            Key.LEFT, Key.UP -> { whereMapLeg = (whereMapLeg - 1).coerceAtLeast(0); Action.Redraw }
            Key.RIGHT, Key.DOWN -> { whereMapLeg = (whereMapLeg + 1).coerceAtMost((whereLegs.size - 1).coerceAtLeast(0)); Action.Redraw }
            Key.OK -> { val p = wherePlace; if (whereOffRoute && p != null) { whereRows = emptyList(); whereStatus = "Finding the way..."; screen = Screen.WHEREJOURNEYS; selected = 0; Action.WhereRoute(p) } else { screen = Screen.WHERELEGS; selected = whereMapLeg; Action.Redraw } }
            Key.SOFT_LEFT, Key.SOFT_RIGHT -> { screen = Screen.WHERELEGS; selected = whereMapLeg; Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
        val size = when (screen) { Screen.WHEREPLACES -> whereResults.size + (if (whereRecent) 1 else 0); Screen.WHEREJOURNEYS -> whereRows.size; Screen.WHEREDETAIL -> rows.size; else -> whereLegs.size }
        return move(key, size) ?: when (screen) {
            Screen.WHEREPLACES -> when (key) {
                Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (whereRecent && selected == 0) whereWrite("") else whereResults.getOrNull(selected - (if (whereRecent) 1 else 0))?.let { p ->
                    wherePlace = p
                    if (hasDetail(p)) { screen = Screen.WHEREDETAIL; selected = 0; Action.Redraw }
                    else { whereRows = emptyList(); whereStatus = "Finding the way..."; screen = Screen.WHEREJOURNEYS; selected = 0; Action.WhereRoute(p) }
                } ?: Action.None
                Key.RIGHT, Key.SOFT_RIGHT -> whereWrite(if (whereRecent) "" else whereQuery)
                Key.END -> { goHome(); Action.Redraw }
                else -> Action.None
            }
            Screen.WHEREDETAIL -> when (key) {
                Key.OK, Key.LEFT, Key.SOFT_LEFT -> wherePlace?.let { p -> whereRows = emptyList(); whereStatus = "Finding the way..."; screen = Screen.WHEREJOURNEYS; selected = 0; Action.WhereRoute(p) } ?: Action.None
                Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.WHEREPLACES; selected = whereResults.indexOf(wherePlace).coerceAtLeast(0) + (if (whereRecent) 1 else 0); Action.Redraw }
                Key.END -> { goHome(); Action.Redraw }
                else -> Action.None
            }
            Screen.WHEREJOURNEYS -> when (key) {
                Key.OK, Key.LEFT, Key.SOFT_LEFT -> whereRows.getOrNull(selected)?.let { row ->
                    val place = wherePlace
                    if (row.action != io.github.durkimat.phoneguard.where.RowAction.OPEN && place != null) {
                        val t = if (row.action == io.github.durkimat.phoneguard.where.RowAction.LATER) whereWalk?.let { io.github.durkimat.phoneguard.where.Where.laterTime(it) } else io.github.durkimat.phoneguard.where.Where.tomorrowMorning(java.time.Instant.ofEpochMilli(nowMs()))
                        whereRows = emptyList(); whereStatus = "Finding the way..."; selected = 0
                        return Action.WhereRoute(place, t)
                    }
                    whereJourney = row.journey
                    whereLegs = row.journey?.legs ?: whereWalk?.let { listOf(io.github.durkimat.phoneguard.where.Leg("walk", "", "", "", wherePlace?.name.orEmpty(), "", "", it.walkMinutes ?: 0, false, it.walkPoints)) }.orEmpty()
                    screen = Screen.WHERELEGS; selected = 0; Action.Redraw
                } ?: Action.None
                Key.RIGHT, Key.SOFT_RIGHT -> { if (hasDetail(wherePlace)) { screen = Screen.WHEREDETAIL; selected = 0 } else { screen = Screen.WHEREPLACES; selected = whereResults.indexOf(wherePlace).coerceAtLeast(0) + (if (whereRecent) 1 else 0) }; Action.Redraw }
                Key.END -> { goHome(); Action.Redraw }
                else -> Action.None
            }
            else -> when (key) { // the legs of one journey
                Key.OK, Key.LEFT, Key.SOFT_LEFT -> { whereMapLeg = selected.coerceIn(0, (whereLegs.size - 1).coerceAtLeast(0)); whereZoom = 0; screen = Screen.WHEREMAP; Action.Redraw }
                Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.WHEREJOURNEYS; selected = 0; Action.Redraw }
                Key.END -> { goHome(); Action.Redraw }
                else -> Action.None
            }
        }
    }

    private fun startSearch(): Action { startWrite("", Screen.SPOTIFYMENU); writeField = 1; writeSearch = true; return Action.Redraw }

    /** The Spotify lists: OK plays the playlist or result, or moves playback to the device. */
    private fun spListKey(key: Key): Action {
        val size = when (screen) { Screen.PLAYLISTS -> spPlaylists.size; Screen.SEARCHRES -> spResults.size; else -> spDevices.size }
        return move(key, size) ?: when (key) {
            Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (screen) {
                Screen.PLAYLISTS -> spPlaylists.getOrNull(selected)?.let { provider = Provider.SPOTIFY; screen = Screen.MUSIC; Action.SpPlay(it.uri) } ?: Action.None
                Screen.SEARCHRES -> spResults.getOrNull(selected)?.let { provider = Provider.SPOTIFY; screen = Screen.MUSIC; Action.SpPlay(it.uri) } ?: Action.None
                else -> spDevices.getOrNull(selected)?.let { provider = Provider.SPOTIFY; screen = Screen.MUSIC; Action.SpTransfer(it.id, it.name) } ?: Action.None
            }
            Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SPOTIFYMENU; selected = 0; Action.Redraw }
            Key.STAR -> if (screen != Screen.PLAYLISTS) Action.None else spPlaylists.getOrNull(selected)?.let { sel ->
                if (sel.uri in spOffline) { notice = "Remove the download to unpin"; Action.Redraw }
                else when (val t = io.github.durkimat.phoneguard.music.PlaylistOrder.toggle(spPins, sel.uri)) {
                    null -> { notice = "Pin list is full"; Action.Redraw }
                    else -> { spPins = t.first; setPlaylists(spRaw); notice = if (t.second) "Pinned" else "Unpinned"; Action.SpPin(sel.uri, t.second) }
                }
            } ?: Action.None
            Key.POUND -> if (screen != Screen.PLAYLISTS) Action.None else spPlaylists.getOrNull(selected)?.let { sel ->
                val rec = spOffline[sel.uri]
                when {
                    sel.uri in spBusy -> { notice = "Already downloading"; Action.Redraw }
                    io.github.durkimat.phoneguard.music.PlaylistOffline.idOf(sel.uri) == null -> Action.None
                    else -> Action.SpOffline(sel.uri, rec?.name ?: spRaw.firstOrNull { it.uri == sel.uri }?.label ?: sel.label, rec != null)
                }
            } ?: Action.None
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun batteryKey(key: Key): Action {
        when (key) {
            Key.RIGHT, Key.SOFT_RIGHT, Key.OK, Key.LEFT, Key.SOFT_LEFT -> leave()
            Key.END -> goHome()
            else -> return Action.None
        }
        return Action.Redraw
    }

    private fun weatherKey(key: Key): Action {
        when (key) {
            Key.RIGHT, Key.SOFT_RIGHT, Key.OK, Key.LEFT, Key.SOFT_LEFT -> leave()
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
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
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
    /**
     * The delete key (top right) is being held: where a text can be edited, delete a word (a number goes whole). Returns false when this screen has nothing to delete
     * this way, so the caller stops repeating. A short tap still deletes one character, through [onKey].
     */
    fun onHoldDelete(): Boolean {
        when (screen) {
            Screen.WRITE -> when (writeField) {
                0 -> { if (writeTo.isEmpty()) return false; writeTo = ""; return true }
                1 -> {
                    if (seq.isNotEmpty()) commit()
                    if (writeText.isEmpty() || cursor == 0) return false
                    val (t, c) = WordDelete.back(writeText, cursor); writeText = t; cursor = c; lastDigit = -1; return true
                }
                else -> return false
            }
            Screen.DIAL -> { if (dialNumber.isEmpty()) return false; dialNumber = ""; return true }
            Screen.CALC -> return calc.clear()
            Screen.WAPAIR -> { if (waCode != null || waLinked || waPairNumber.isEmpty()) return false; waPairNumber = ""; return true }
            else -> return false
        }
    }

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
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun menu(key: Key, digit: Int): Action = if (layout == Layout.GRID) menuGrid(key, digit) else menuList(key, digit)

    /** The grid: the D-pad moves in four directions; OK and the left key open the entry, the right key and End go home. */
    private fun menuGrid(key: Key, digit: Int): Action = when (key) {
        Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT -> { selected = gridNeighbour(selected, items.size, key); Action.Redraw }
        Key.OK, Key.SOFT_LEFT -> open(items.getOrNull(selected))
        Key.SOFT_RIGHT, Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> openCallLog(Screen.MENU)
        Key.DIGIT -> { val i = if (digit == 0) 9 else digit - 1; items.getOrNull(i)?.let { selected = i; open(it) } ?: Action.None }
        else -> Action.None
    }

    private fun menuList(key: Key, digit: Int): Action = when (key) {
        Key.UP -> if (items.isEmpty()) Action.None else { selected = (selected - 1 + items.size) % items.size; Action.Redraw }
        Key.DOWN -> if (items.isEmpty()) Action.None else { selected = (selected + 1) % items.size; Action.Redraw }
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> open(items.getOrNull(selected))
        Key.RIGHT, Key.SOFT_RIGHT, Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> openCallLog(Screen.MENU)
        // 1 to 9 jump straight to that entry, like a Nokia menu
        Key.DIGIT -> { val i = if (digit == 0) 9 else digit - 1; items.getOrNull(i)?.let { selected = i; open(it) } ?: Action.None }
        else -> Action.None
    }

    // ---- Calendar
    var zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    /** The clock, so a test can say what time it is. */
    var nowMs: () -> Long = { System.currentTimeMillis() }
    val today: java.time.LocalDate get() = java.time.Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()
    /** The occurrences the activity loaded after a [Action.CalLoad], the window they cover, and the calendars an event may be added to. */
    var calEvents: List<CalEvent> = emptyList()
    var calRange: Pair<Long, Long> = 0L to 0L
    var calWritable: List<CalInfo> = emptyList()
    /** The day the agenda starts at (null: today) and the day highlighted in the month. */
    var calFrom: java.time.LocalDate? = null
    var calDay: java.time.LocalDate = java.time.LocalDate.now()
    private var shown: CalEvent? = null
    private var evConfirm = false
    var form: Form? = null
        private set
    /** The page screens (an event, a mail) show a head line and a body, scrolled by [scroll]. */
    var pageHead = ""
        private set
    var pageBody = ""
        private set
    val agenda: List<AgendaItem> get() = Cal.agenda(calEvents, calFrom ?: today, 90, zone)
    val calShown: CalEvent? get() = shown

    private fun calLoad(center: java.time.LocalDate): Action {
        val from = Cal.dayStart(center.minusDays(60), zone); val to = Cal.dayStart(center.plusDays(240), zone)
        calRange = from to to
        return Action.CalLoad(from, to)
    }
    /** Asks for more events when [day] is near or outside the window already loaded. */
    private fun calNeeds(day: java.time.LocalDate): Action? {
        val t = Cal.dayStart(day, zone)
        return if (t < calRange.first + 14 * 86_400_000L || t > calRange.second - 14 * 86_400_000L) calLoad(day) else null
    }
    private fun openCalendar(): Action { calFrom = null; calDay = today; enter(Screen.CALENDAR); return calLoad(today) }

    private fun calendarKey(key: Key): Action {
        val items = agenda
        return move(key, maxOf(1, items.size)) ?: when (key) {
            Key.OK -> items.getOrNull(selected)?.let { showEvent(it.event) } ?: Action.None
            Key.LEFT, Key.SOFT_LEFT -> { enter(Screen.CALMENU); Action.Redraw }
            Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun showEvent(e: CalEvent): Action {
        shown = e; evConfirm = false; scroll = 0
        pageHead = Cal.when_(e, zone)
        pageBody = listOf(e.title.ifBlank { "(no title)" }, if (e.place.isBlank()) "" else "At " + e.place,
            if (e.repeat.isBlank()) "" else Cal.repeatName(e.repeat), e.notes).filter { it.isNotBlank() }.joinToString("\n\n")
        enter(Screen.EVENT)
        return Action.Redraw
    }

    private fun calMenuKey(key: Key): Action = move(key, 3) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when (selected) {
            0 -> { openForm(eventForm(Cal.newEvent(calFrom ?: today, nowMs(), zone, calWritable.firstOrNull()?.id ?: 0))); Action.Redraw }
            1 -> { screen = Screen.CALMONTH; calDay = calFrom ?: today; selected = 0; calNeeds(calDay) ?: Action.Redraw }
            else -> { calFrom = null; leave(); selected = 0; Action.Redraw }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun calMonthKey(key: Key, digit: Int): Action {
        fun go(d: java.time.LocalDate): Action { calDay = d; return calNeeds(d) ?: Action.Redraw }
        return when (key) {
            Key.UP -> go(calDay.minusDays(7))
            Key.DOWN -> go(calDay.plusDays(7))
            Key.LEFT -> go(calDay.minusDays(1))
            Key.RIGHT -> go(calDay.plusDays(1))
            Key.STAR -> go(calDay.minusMonths(1))
            Key.POUND -> go(calDay.plusMonths(1))
            Key.DIGIT -> if (digit == 0) go(today) else Action.None
            Key.OK, Key.SOFT_LEFT -> { leave(); calFrom = calDay; selected = 0; Action.Redraw }
            Key.SOFT_RIGHT -> { leave(); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private fun eventKey(key: Key): Action {
        val lines = wrapper(pageBody).size
        return when (key) {
            Key.DOWN -> if (scroll + pageLines < lines) { scroll += pageLines; Action.Redraw } else Action.None
            Key.UP -> if (scroll > 0) { scroll = (scroll - pageLines).coerceAtLeast(0); Action.Redraw } else Action.None
            Key.OK, Key.LEFT, Key.SOFT_LEFT -> { evConfirm = false; enter(Screen.EVMENU); Action.Redraw }
            Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private val evMenuRows: List<String> get() = when {
        shown?.writable != true -> listOf("Read only calendar")
        evConfirm -> listOf("Yes, delete it", "No")
        else -> listOf("Edit", "Delete")
    }

    private fun evMenuKey(key: Key): Action = move(key, evMenuRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            val e = shown
            when {
                e == null || !e.writable -> Action.None
                evConfirm -> if (selected == 0) { leaveTo(Screen.CALENDAR, 0); notice = "Deleted"; Action.CalDelete(e.id) } else { evConfirm = false; selected = 1; Action.Redraw }
                selected == 0 -> { openForm(eventForm(if (e.repeat.isNotBlank() && e.seriesStart > 0) e.copy(start = e.seriesStart, end = e.seriesEnd) else e)); Action.Redraw }
                else -> { evConfirm = true; selected = 1; Action.Redraw }
            }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { if (evConfirm) { evConfirm = false; selected = 1 } else leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** Shows a form in place of the screen that opened it, so Back returns to where that was. */
    private fun openForm(f: Form) { form = f; screen = Screen.FORM; selected = 0 }

    private fun formKey(key: Key): Action {
        val f = form ?: run { goHome(); return Action.Redraw }
        val rs = f.rowsOf()
        selected = selected.coerceIn(0, (rs.size - 1).coerceAtLeast(0))
        val r = rs.getOrNull(selected)
        fun step(dir: Int, big: Boolean): Action { val s = r?.onStep ?: return Action.None; s(dir, big); return Action.Redraw }
        return move(key, rs.size) ?: when (key) {
            Key.OK -> when {
                r == null -> Action.None
                r.onText != null -> startText(r.label, r.initial(), Screen.FORM, r.exact) { t -> r.onText.invoke(t); Action.Redraw }
                r.run != null -> r.run.invoke()
                r.onStep != null && r.okStep -> step(1, false)
                else -> Action.None
            }
            Key.LEFT -> step(-1, false)
            Key.RIGHT -> step(1, false)
            Key.STAR -> step(-1, true)
            Key.POUND -> step(1, true)
            Key.SOFT_LEFT -> f.onPrimary()
            Key.SOFT_RIGHT -> { leave(); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    /** The edit form for an event (a new one has id 0): title, when, repeat, reminder, place, notes. Save is the left soft key. */
    private fun eventForm(e0: CalEvent): Form {
        var e = e0
        fun day(d: java.time.LocalDate) = Cal.dayName(d) + " " + d.dayOfMonth + " " + Cal.monthName(d.monthValue).take(3)
        fun brief(s: String) = if (s.isBlank()) "None" else s.lineSequence().first().take(18)
        val rows: () -> List<FormRow> = {
            buildList {
                add(FormRow("Title", { brief(e.title).let { if (e.title.isBlank()) "-" else it } }, { e = e.copy(title = it) }, { e.title }))
                add(FormRow("Date", { day(Cal.startDate(e, zone)) }, onStep = { d, big -> e = Cal.shiftDays(e, (if (big) 7L else 1L) * d, zone) }))
                if (e.allDay) add(FormRow("Last day", { day(Cal.endDate(e, zone)) }, onStep = { d, big -> e = Cal.shiftLastDay(e, (if (big) 7L else 1L) * d) }))
                else {
                    add(FormRow("Starts", { Cal.clock(e.start, zone) }, onStep = { d, big -> e = Cal.shiftTime(e, (if (big) 60L else 15L) * d) }))
                    add(FormRow("Ends", { Cal.clock(e.end, zone) }, onStep = { d, big -> e = Cal.shiftEnd(e, (if (big) 60L else 15L) * d) }))
                }
                add(FormRow("All day", { if (e.allDay) "Yes" else "No" }, onStep = { _, _ -> e = if (e.allDay) Cal.toTimed(e, zone) else Cal.toAllDay(e, zone) }, okStep = true))
                add(FormRow("Repeat", { Cal.repeatName(e.repeat) }, onStep = { d, _ -> e = e.copy(repeat = Cal.stepRepeat(e.repeat, d)) }))
                add(FormRow("Reminder", { Cal.reminderName(e.reminder) }, onStep = { d, _ -> e = e.copy(reminder = Cal.stepReminder(e.reminder, d)) }))
                add(FormRow("Place", { brief(e.place) }, { e = e.copy(place = it) }, { e.place }))
                add(FormRow("Notes", { brief(e.notes) }, { e = e.copy(notes = it) }, { e.notes }))
                if (e.id == 0L && calWritable.size > 1) add(FormRow("Calendar", { calWritable.firstOrNull { it.id == e.calId }?.name ?: calWritable.first().name },
                    onStep = { d, _ -> val i = calWritable.indexOfFirst { it.id == e.calId }.coerceAtLeast(0); e = e.copy(calId = calWritable[(i + d + calWritable.size) % calWritable.size].id) }))
            }
        }
        return Form(if (e0.id == 0L) "New event" else "Edit event", "Save", rows) {
            if (e.title.isBlank()) { notice = "Enter a title"; Action.Redraw }
            else { val out = e; leaveTo(Screen.CALENDAR, 0); notice = "Saved"; Action.CalSave(out) }
        }
    }

    /** Types some text on the write screen, filled with [initial], and runs [done] with it when the owner presses Done. */
    fun startText(head: String, initial: String, back: Screen, exact: Boolean = false, done: (String) -> Action): Action {
        startWrite("-", back)
        writeHead = head; writeDone = done; writeExact = exact
        writeText = initial; cursor = initial.length
        return Action.Redraw
    }
    var writeHead: String? = null
        private set
    private var writeDone: ((String) -> Action)? = null
    private var writeExact = false
    private val exactTyping: Boolean get() = writeWifi != null || writeExact

    // ---- Mail
    /** The address of the account that is set up, or null; the headers kept on the phone; and a short line about the last check. */
    var mailConfig: MailAccount? = null
    val mailAccount: String? get() = mailConfig?.address
    var mailInbox: List<MailHeader> = emptyList()
    var mailSent: List<MailHeader> = emptyList()
    var mailStatus = ""
    private var mailSentBox = false
    private var mailOpen: MailHeader? = null
    private var mailConfirm = false
    private var removeConfirm = false
    private val mailList: List<MailHeader> get() = if (mailSentBox) mailSent else mailInbox
    private val mailUnread: Int get() = mailInbox.count { it.unread }

    private fun openMail(): Action { enter(Screen.MAILBOX); return Action.MailSync(false) }

    private val mailBoxRows: List<String> get() =
        if (mailAccount == null) listOf("Set up email")
        else listOf("Inbox" + (if (mailUnread > 0) " ($mailUnread)" else ""), "Sent", "Write message", "Check mail now" + (if (mailStatus.isNotBlank()) " - $mailStatus" else ""), "Account")

    private fun mailBoxKey(key: Key): Action = move(key, mailBoxRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when {
            mailAccount == null -> { pushForm(accountForm()); Action.Redraw }
            selected == 0 || selected == 1 -> { mailSentBox = selected == 1; enter(Screen.MAILLIST); Action.Redraw }
            selected == 2 -> { pushForm(composeForm("", "", "")); Action.Redraw }
            selected == 3 -> { mailStatus = "Checking..."; Action.MailSync(true) }
            else -> { pushForm(accountForm()); Action.Redraw }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun mailListKey(key: Key): Action = move(key, maxOf(1, mailList.size)) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> mailList.getOrNull(selected)?.let { openMessage(it) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun mailWhen(ms: Long): String {
        val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        return if (t.toLocalDate() == today) Cal.clock(ms, zone) else t.dayOfMonth.toString() + " " + Cal.monthName(t.monthValue).take(3) + " " + Cal.clock(ms, zone)
    }

    private fun openMessage(h: MailHeader): Action {
        mailOpen = h; mailConfirm = false; scroll = 0
        pageHead = (if (mailSentBox) "To " + h.to.ifBlank { h.from } else h.from) + "  " + mailWhen(h.date)
        pageBody = h.subject + "\n\nLoading..."
        enter(Screen.MAILMSG)
        if (h.unread && !mailSentBox) mailInbox = mailInbox.map { if (it.uid == h.uid) it.copy(unread = false) else it }
        return Action.MailFetch(mailSentBox, h.uid)
    }

    /** The text of the open message arrived (or could not be fetched). */
    fun setMailBody(uid: Long, text: String) {
        val h = mailOpen ?: return
        if (h.uid == uid && screen in setOf(Screen.MAILMSG, Screen.MAILMENU)) pageBody = h.subject + "\n\n" + text
    }

    private fun mailMsgKey(key: Key): Action {
        val lines = wrapper(pageBody).size
        return when (key) {
            Key.DOWN -> if (scroll + pageLines < lines) { scroll += pageLines; Action.Redraw } else Action.None
            Key.UP -> if (scroll > 0) { scroll = (scroll - pageLines).coerceAtLeast(0); Action.Redraw } else Action.None
            Key.OK, Key.LEFT, Key.SOFT_LEFT -> { mailConfirm = false; enter(Screen.MAILMENU); Action.Redraw }
            Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
            Key.END -> { goHome(); Action.Redraw }
            else -> Action.None
        }
    }

    private val mailMenuRows: List<String> get() = when {
        mailConfirm -> listOf("Yes, delete it", "No")
        mailSentBox -> listOf("Delete")
        else -> listOf("Reply", "Delete", "Mark unread")
    }

    private fun mailMenuKey(key: Key): Action = move(key, mailMenuRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            val h = mailOpen
            val row = mailMenuRows[selected.coerceIn(0, mailMenuRows.size - 1)]
            when {
                h == null -> Action.None
                row == "Reply" -> { openForm(composeForm(h.addr, if (h.subject.startsWith("Re:", true)) h.subject else "Re: " + h.subject, "")); Action.Redraw }
                row == "Delete" -> { mailConfirm = true; selected = 1; Action.Redraw }
                row == "Yes, delete it" -> { leaveTo(Screen.MAILLIST, 0); val sent = mailSentBox; if (sent) mailSent = mailSent.filter { it.uid != h.uid } else mailInbox = mailInbox.filter { it.uid != h.uid }; notice = "Deleted"; Action.MailDelete(sent, h.uid) }
                row == "No" -> { mailConfirm = false; selected = 1; Action.Redraw }
                else -> { // Mark unread
                    leaveTo(Screen.MAILLIST, 0); mailInbox = mailInbox.map { if (it.uid == h.uid) it.copy(unread = true) else it }; notice = "Marked unread"; Action.MailSeen(h.uid, false)
                }
            }
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { if (mailConfirm) { mailConfirm = false; selected = 1 } else leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    /** Pushes a form on top of this screen (Back returns here), unlike [openForm], which takes this screen's place. */
    private fun pushForm(f: Form) { form = f; enter(Screen.FORM) }

    private fun composeForm(to0: String, subject0: String, body0: String): Form {
        var to = to0; var subject = subject0; var body = body0
        val rows: () -> List<FormRow> = { listOf(
            FormRow("To", { to.ifBlank { "-" } }, { to = it.trim() }, { to }, exact = true),
            FormRow("Subject", { subject.ifBlank { "-" } }, { subject = it }, { subject }),
            FormRow("Message", { body.lineSequence().firstOrNull().orEmpty().take(18).ifBlank { "-" } }, { body = it }, { body }),
        ) }
        return Form("Write message", "Send", rows) {
            val rcpt = to.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            when {
                rcpt.isEmpty() || rcpt.any { !it.contains('@') } -> { notice = "Check the To address"; Action.Redraw }
                body.isBlank() -> { notice = "Write a message first"; Action.Redraw }
                else -> {
                    leaveTo(if (trail.any { it.screen == Screen.MAILLIST }) Screen.MAILLIST else Screen.MAILBOX, 0); notice = "Sending..."
                    Action.MailSend(rcpt, subject.ifBlank { "(no subject)" }, body)
                }
            }
        }
    }

    // ---- Screen lock
    /** Set by the activity: a secure lock exists; the phone can check its PIN; parent control is ready (Family, token active); the line about who set it; a note to show once. */
    var lockSet = false
    var lockKnown = false
    var lockParentReady = false
    var lockBy = ""
    private var lockRemoveConfirm = false

    private fun openLock(): Action {
        if (edition != "family") return Action.OpenAndroidLock // an Open phone has no parent control: Android's own screen does it
        pushForm(lockForm())
        return Action.LockLoad
    }

    /** The PIN could not be saved or removed: come back to the Screen lock page and say why, instead of dropping the person into Settings. */
    fun lockFailed(why: String) { if (screen == Screen.THISPHONE) { pushForm(lockForm()) }; notice = why }

    private fun lockForm(): Form {
        var cur = ""; var pin = ""; var again = ""
        lockRemoveConfirm = false
        val stars: (String) -> String = { if (it.isEmpty()) "-" else "*".repeat(it.length.coerceAtMost(8)) }
        val rows: () -> List<FormRow> = { buildList {
            add(FormRow("PIN", { if (!lockSet) "not set, 4 to 8 digits" else if (lockBy == "parent") "set by your parent" else "set" }))
            if (lockSet && !lockParentReady) add(FormRow("Allow parent help", run = { Action.ConfirmLockForParent }))
            if (lockSet && lockKnown) add(FormRow("Current PIN", { stars(cur) }, { cur = it.filter { ch -> ch.isDigit() }.take(8) }, { cur }, exact = true))
            add(FormRow("New PIN", { stars(pin) }, { pin = it.filter { ch -> ch.isDigit() }.take(8) }, { pin }, exact = true))
            add(FormRow("Repeat PIN", { stars(again) }, { again = it.filter { ch -> ch.isDigit() }.take(8) }, { again }, exact = true))
            if (lockSet) add(FormRow(if (lockRemoveConfirm) "Press again to remove" else "Remove PIN", run = {
                if (!lockRemoveConfirm) { lockRemoveConfirm = true; Action.Redraw }
                else if (lockKnown && cur.isEmpty()) { lockRemoveConfirm = false; notice = "Enter the current PIN"; Action.Redraw }
                else { lockRemoveConfirm = false; leave(); notice = "Removing the PIN..."; Action.RemoveLockPin(cur) }
            }))
        } }
        return Form("Screen lock", "Save", rows) {
            when {
                pin.isEmpty() && again.isEmpty() -> { leave(); Action.Redraw }
                pin.length !in 4..8 -> { notice = "Use 4 to 8 digits"; Action.Redraw }
                pin != again -> { notice = "The two PINs differ"; Action.Redraw }
                lockSet && lockKnown && cur.isEmpty() -> { notice = "Enter the current PIN"; Action.Redraw }
                else -> { leave(); notice = "Saving the PIN..."; Action.SetLockPin(cur, pin) }
            }
        }
    }

    // ---- Mobile data plan
    /** The SIM's data for the home widget and the Data page; the plan being edited, the SIMs, and the renewal date text. Set by the activity. */
    var dataView: io.github.durkimat.phoneguard.core.DataView? = null
    var dataPlan = io.github.durkimat.phoneguard.core.DataPlan()
    var dataSims: List<Pair<Int, String>> = emptyList()
    var dataRenews = ""
    /** The by-app page: the rows (or [dataAppsStatus] while loading or when nothing was used). Set by the activity. */
    var dataApps: List<String> = emptyList()
        private set
    var dataAppsStatus = ""
    fun setDataApps(rows: List<String>) { dataApps = rows; dataAppsStatus = if (rows.isEmpty()) "No mobile data used yet" else ""; selected = 0 }

    private val dataToggleRow: List<String> get() = if (dataShown) listOf("Mobile data: " + (if (connData == 2) "Off" else "On")) else emptyList()

    private val dataRows: List<String> get() {
        val v = dataView ?: return dataToggleRow + listOf(if (dataPlan.set) "No reading yet" else "No data plan set", if (dataPlan.set) "Change plan" else "Set up plan")
        return dataToggleRow + listOf("Used: " + io.github.durkimat.phoneguard.core.DataView.amount(v.used) + " (" + (v.fraction * 100).toInt() + "%)", "Left: " + io.github.durkimat.phoneguard.core.DataView.amount(v.left),
            "Allowance: " + io.github.durkimat.phoneguard.core.DataView.amount(v.allowance), "Renews: " + dataRenews.ifEmpty { "-" }, "By app", "Change plan")
    }

    private fun dataKey(key: Key): Action = move(key, dataRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> when {
            dataShown && selected == 0 -> Action.DataToggle
            selected == dataRows.size - 1 -> { pushForm(dataForm()); Action.Redraw }
            dataView != null && selected == dataRows.size - 2 -> { dataApps = emptyList(); dataAppsStatus = "Loading..."; enter(Screen.DATAAPPS); Action.DataByApp }
            else -> Action.None
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { leave(); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        else -> Action.None
    }

    private fun dataForm(): Form {
        val p0 = dataPlan
        var gb = if (p0.set) io.github.durkimat.phoneguard.core.DataView.amount(p0.allowance).removeSuffix(" GB") else ""
        var day = p0.renewDay; var start = p0.start; var warn = p0.warnPct; var corr = p0.correctionMb.toString(); var sim = p0.subId
        val rows: () -> List<FormRow> = { buildList {
            add(FormRow("Allowance GB", { gb.ifBlank { "-" } }, { gb = it.trim().replace(',', '.') }, { gb }, exact = true))
            add(FormRow("Renews on day", { if (start.isNotBlank()) "(date set)" else day.toString() }, onStep = { d, big -> day = (day + d * (if (big) 5 else 1) - 1).mod(28) + 1; start = "" }))
            add(FormRow("Or start date", { start.ifBlank { "-" } }, { start = it.trim() }, { start }, exact = true))
            add(FormRow("Warn at %", { warn.toString() }, onStep = { d, big -> warn = (warn + d * (if (big) 10 else 1)).coerceIn(1, 50) }))
            add(FormRow("Correction MB", { corr }, { corr = it.trim() }, { corr }, exact = true))
            if (dataSims.size > 1) add(FormRow("SIM", { dataSims.firstOrNull { it.first == sim }?.second ?: "Data SIM" }, onStep = { d, _ ->
                val ids = listOf(-1) + dataSims.map { it.first }; sim = ids[(ids.indexOf(sim).coerceAtLeast(0) + d).mod(ids.size)] }))
        } }
        return Form("Mobile data plan", "Save", rows) {
            val bytes = gb.toDoubleOrNull()?.let { (it * 1e9).toLong() } ?: 0L
            when {
                gb.isNotBlank() && bytes <= 0 -> { notice = "Allowance in GB, like 10 or 7.5"; Action.Redraw }
                start.isNotBlank() && io.github.durkimat.phoneguard.core.DataPlan.parseDate(start) == null -> { notice = "Date like 2026-12-12"; Action.Redraw }
                corr.isNotBlank() && corr.toLongOrNull() == null -> { notice = "Correction is a whole number of MB"; Action.Redraw }
                else -> { leave(); notice = "Saved"; Action.SaveDataPlan(io.github.durkimat.phoneguard.core.DataPlan(bytes, day, start, warn, corr.toLongOrNull() ?: 0L, sim)) }
            }
        }
    }

    private fun accountForm(): Form {
        var address = mailAccount ?: ""; var password = ""; var name = ""; var imap = ""; var smtp = ""
        removeConfirm = false
        val known: () -> Boolean = { MailPresets.forAddress(address) != null || address.endsWith(Mail_DEMO) }
        val rows: () -> List<FormRow> = { buildList {
            if (mailAccount == null) add(FormRow("Address", { address.ifBlank { "-" } }, { address = it.trim() }, { address }, exact = true))
            else add(FormRow("Account", { address }))
            add(FormRow("Password", { if (password.isEmpty()) "-" else "*".repeat(password.length.coerceAtMost(16)) }, { password = it }, { password }, exact = true))
            if (mailAccount == null && address.contains('@') && !known()) {
                add(FormRow("IMAP server", { imap.ifBlank { MailPresets.guess(address)?.imapHost ?: "-" } }, { imap = it.trim() }, { imap.ifBlank { MailPresets.guess(address)?.imapHost ?: "" } }, exact = true))
                add(FormRow("SMTP server", { smtp.ifBlank { MailPresets.guess(address)?.smtpHost ?: "-" } }, { smtp = it.trim() }, { smtp.ifBlank { MailPresets.guess(address)?.smtpHost ?: "" } }, exact = true))
            }
            if (mailAccount == null) add(FormRow("Your name", { name.ifBlank { "-" } }, { name = it }, { name }))
            if (mailAccount != null) add(FormRow(if (removeConfirm) "Press again to remove" else "Remove account", run = { if (removeConfirm) { removeConfirm = false; leaveTo(Screen.MAILBOX, 0); notice = "Account removed"; Action.MailRemove } else { removeConfirm = true; Action.Redraw } }))
        } }
        return Form(if (mailAccount == null) "Set up email" else "Account", "Save", rows) {
            val a = address.trim()
            val base = mailConfig?.takeIf { it.address == a } ?: MailPresets.forAddress(a) ?: if (a.endsWith(Mail_DEMO)) MailAccount(a, imapHost = "demo", smtpHost = "demo") else MailPresets.guess(a)
            when {
                mailAccount != null && password.isEmpty() -> { leaveTo(Screen.MAILBOX, 0); Action.Redraw }
                base == null -> { notice = "Enter your email address"; Action.Redraw }
                password.isEmpty() && !a.endsWith(Mail_DEMO) -> { notice = "Enter the password"; Action.Redraw }
                else -> {
                    val acct = base.copy(name = name.trim(), imapHost = imap.ifBlank { base.imapHost }, smtpHost = smtp.ifBlank { base.smtpHost })
                    leaveTo(Screen.MAILBOX, 0); mailStatus = "Signing in..."
                    Action.MailSetup(acct, password)
                }
            }
        }
    }
    private val Mail_DEMO = "@demo.local"
}

enum class BadgeKind { SMS, WHATSAPP, MISSED, NOTES, MUSIC, MAIL }
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
    /** The next event today or tomorrow ("15:00 Dentist"), or empty. */
    val nextUp: String = "",
    /** Wi-Fi: -1 not shown (the mode hides it), 0 not connected (drawn greyed), 1 connected. */
    val wifi: Int = -1,
    /** Bluetooth: -1 not shown, 0 on but nothing connected (drawn faint), 1 a device is connected, 2 switched off (faint with a slash). */
    val bt: Int = -1,
    /** Mobile data: -1 not shown (no SIM, or the mode hides it), 0 on but not in use, 1 in use, 2 switched off. Wi-Fi above takes 2 for switched off too. */
    val data: Int = -1,
    /** The SIM's mobile data (null when there is no plan, no SIM, or the mode hides it). */
    val dataView: io.github.durkimat.phoneguard.core.DataView? = null,
    /** What is playing (or paused), for the home screen's one-line Now playing; null when nothing is, or the mode has no music. */
    val nowPlaying: io.github.durkimat.phoneguard.music.MusicInfo? = null,
    /** An alarm is switched on or the timer is running: a small bell shows in the top bar. */
    val alarmSet: Boolean = false,
    /** The next alarm's time ("07:00") for the home screen, under the clock; empty when no alarm is on. */
    val alarmText: String = "",
)

/** A way of drawing the launcher. The engine never knows which one is in use. */
interface LauncherSkin {
    val id: String
    fun create(context: android.content.Context): android.view.View
    fun render(model: LauncherModel, info: HomeInfo)
}
