package io.github.durkimat.phoneguard.launcher

/**
 * The launcher's behaviour, with no drawing and no Android classes, so it is unit tested and any skin
 * can sit on top of it. A skin shows [screen], [items] and [selected], and tells the model which key
 * was pressed. The model says what should happen next.
 */
enum class Screen { HOME, MENU, SETTINGS, THEME, FONT, CONTACTS, CONTACT, MESSAGES, INBOX, THREAD, WRITE, TYPING, CALC, CALLLOG, DIAL, CALL, CALLMENU, SPEAKER, WHATSAPP, WAPAIR, WASET }

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
data class Msg(val body: String, val date: Long, val mine: Boolean)

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
    /** Place a call to the number just dialled. */
    data class PlaceCall(val number: String) : Action()
    /** Send this text message. */
    data class Send(val to: String, val body: String) : Action()
}

class LauncherModel(
    items: List<MenuItem> = emptyList(),
    val contactsApp: String? = "com.android.contacts",
    val messagesApp: String? = "com.android.messaging",
) {
    var screen = Screen.HOME
        private set
    var items: List<MenuItem> = items
        private set
    var selected = 0
        private set

    fun setItems(list: List<MenuItem>) {
        items = list
        selected = selected.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    /** The id of the theme in use. The activity sets it from storage at start. */
    var theme: String = Themes.DEFAULT

    var font: String = Fonts.DEFAULT

    /** The phone's contacts, sorted by name. The activity fills this in. */
    var contacts: List<Contact> = emptyList()
    private var contact: Contact? = null
    private var contactsFrom = Screen.HOME

    private val settingsRows: List<String> get() = listOf("Theme", "Font", "Predictive text", "Speakerphone") + (if (waEnabled) listOf("WhatsApp") else emptyList())

    // ---- Messages state
    /** Conversations, newest first. The activity fills this in. */
    var convos: List<Convo> = emptyList()
    /** WhatsApp: whether the account is linked, the chats (newest first), the count unread and the bridge's state. */
    var waEnabled = false
    /** Whether an account is linked. Not linked: the WhatsApp entry offers to link one. */
    var waLinked = false
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

    fun goHome() { screen = Screen.HOME; selected = 0 }

    val title: String get() = when (screen) {
        Screen.SETTINGS -> "Settings"
        Screen.THEME -> "Theme"
        Screen.FONT -> "Font"
        Screen.CONTACTS -> "Contacts"
        Screen.CONTACT -> contact?.name ?: "Contact"
        Screen.MESSAGES -> "Messages"
        Screen.INBOX -> "Inbox"
        Screen.WHATSAPP -> "WhatsApp"
        Screen.THREAD -> convo?.name ?: "Messages"
        Screen.WRITE -> "Write message"
        Screen.TYPING -> "Predictive text"
        Screen.SPEAKER -> "Speakerphone"
        Screen.WAPAIR -> "Link WhatsApp"
        Screen.WASET -> "WhatsApp"
        Screen.CALC -> "Calculator"
        Screen.CALLLOG -> "Call log"
        Screen.DIAL -> "Call"
        Screen.CALLMENU -> "Options"
        Screen.CALL -> "Call"
        else -> "Menu"
    }

    /** The two labels under the screen, which the two keys beneath it and D-pad Left/Right carry out. */
    val softKeys: Pair<String, String> get() = when (screen) {
        Screen.HOME -> "Menu" to "Contacts"
        Screen.CALC -> "Equals" to "Clear"
        Screen.CALLLOG -> "Call" to "Back"
        Screen.DIAL -> "Call" to "Clear"
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
        Screen.WRITE -> (if (writeField == 0) (if (writeTo.isEmpty()) "Contacts" else "Next") else "Send") to "Clear"
        else -> "Select" to "Back"
    }

    /** The lines of whichever list is showing. */
    val rows: List<String> get() = when (screen) {
        Screen.MENU -> items.map { it.label }
        Screen.SETTINGS -> settingsRows
        Screen.THEME -> Themes.all.map { it.name + if (it.id == theme) " *" else "" }
        Screen.FONT -> Fonts.all.map { it.name + if (it.id == font) " *" else "" }
        Screen.CONTACTS -> contacts.map { it.name }
        Screen.CONTACT -> (contact?.numbers ?: emptyList()).map { "Call $it" } + "Message"
        Screen.CALLLOG -> calls.map { (when (it.type) { 2 -> "> "; 3 -> "! "; else -> "< " }) + it.name }
        Screen.CALC, Screen.DIAL, Screen.CALL, Screen.WAPAIR -> emptyList()
        Screen.CALLMENU -> (if (speakerShown) listOf(if (call?.speaker == true) "Speaker off" else "Speaker on") else emptyList()) + (if (call?.muted == true) "Unmute" else "Mute")
        Screen.WASET -> listOf(if (waLinked) "Unlink account" else "Link account")
        Screen.SPEAKER -> listOf("Show" + if (speakerShown) " *" else "", "Hide" + if (!speakerShown) " *" else "")
        Screen.TYPING -> listOf("On" + if (predictiveOn) " *" else "", "Off" + if (!predictiveOn) " *" else "")
        Screen.MESSAGES -> messagesRows
        Screen.INBOX -> convos.map { (if (it.unread > 0) "* " else "") + it.name }
        Screen.WHATSAPP -> if (!waLinked) listOf("Link account") else waConvos.map { (if (it.unread > 0) "* " else "") + it.name }
        Screen.THREAD, Screen.WRITE, Screen.HOME -> emptyList()
    }

    /** `digit` is only used with [Key.DIGIT]. */
    fun onKey(key: Key, digit: Int = -1, nowMs: Long = System.currentTimeMillis()): Action {
        notice = null
        return dispatch(key, digit, nowMs)
    }

    /** The lists whose numbered entries are chosen by pressing that number, like a Nokia menu. */
    private val numbered = setOf(Screen.SETTINGS, Screen.THEME, Screen.FONT, Screen.TYPING, Screen.SPEAKER, Screen.INBOX, Screen.WHATSAPP, Screen.WASET, Screen.CONTACTS, Screen.CONTACT, Screen.CALLLOG, Screen.CALLMENU)

    private fun dispatch(key: Key, digit: Int, nowMs: Long): Action {
        if (key == Key.DIGIT && screen in numbered && digit in 1..minOf(9, rows.size)) { selected = digit - 1; return route(Key.OK, -1, nowMs) }
        return route(key, digit, nowMs)
    }

    private fun route(key: Key, digit: Int, nowMs: Long): Action = when (screen) {
        Screen.HOME -> home(key, digit)
        Screen.MENU -> menu(key, digit)
        Screen.SETTINGS -> settings(key, digit)
        Screen.THEME -> themes(key, digit)
        Screen.FONT -> fonts(key, digit)
        Screen.CONTACTS -> contactList(key, digit)
        Screen.CONTACT -> contactDetail(key, digit)
        Screen.MESSAGES -> messagesMenu(key, digit)
        Screen.INBOX -> inbox(key, digit)
        Screen.WHATSAPP -> whatsapp(key, digit)
        Screen.WAPAIR -> waPair(key, digit)
        Screen.WASET -> waSettings(key, digit)
        Screen.THREAD -> thread(key, digit)
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
            Key.OK, Key.LEFT, Key.SOFT_LEFT, Key.CALL -> return if (dialNumber.isEmpty()) openCallLog(Screen.HOME) else Action.PlaceCall(dialNumber)
            Key.RIGHT, Key.SOFT_RIGHT -> { dialNumber = dialNumber.dropLast(1); if (dialNumber.isEmpty()) goHome() }
            Key.END -> { dialNumber = ""; goHome() }
            else -> return Action.None
        }
        return Action.Redraw
    }

    private fun openCallLog(from: Screen): Action { callsFrom = from; screen = Screen.CALLLOG; selected = 0; return Action.Redraw }

    private fun callLog(key: Key, digit: Int): Action = move(key, calls.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT, Key.CALL -> calls.getOrNull(selected)?.let { Action.Call(it.number) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { if (callsFrom == Screen.MENU) { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == CALLLOG_PKG }.coerceAtLeast(0) } else goHome(); Action.Redraw }
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

    private fun typing(key: Key, digit: Int): Action = move(key, 2) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { predictiveOn = selected == 0; writeMode = 0; screen = Screen.SETTINGS; selected = 2; Action.SavePredictive(predictiveOn) }
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

    private fun waSettings(key: Key, digit: Int): Action = move(key, 1) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (waLinked) { screen = Screen.SETTINGS; selected = 4; Action.UnlinkWa } else startPair(Screen.WASET)
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
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> if (!waLinked) startPair(Screen.WHATSAPP) else waConvos.getOrNull(selected)?.let { convo = it; threadWa = true; screen = Screen.THREAD; selected = (it.msgs.size - 1).coerceAtLeast(0); scroll = 0; Action.WaRead(it.address) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MESSAGES; selected = 1; threadWa = false; Action.Redraw }
        Key.END -> { threadWa = false; goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun inbox(key: Key, digit: Int): Action = move(key, convos.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> convos.getOrNull(selected)?.let { convo = it; screen = Screen.THREAD; selected = (it.msgs.size - 1).coerceAtLeast(0); scroll = 0; Action.Redraw } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MESSAGES; selected = 0; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> convos.getOrNull(selected)?.let { Action.Call(it.address) } ?: Action.CallKey
        else -> Action.None
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

    private fun startWrite(to: String, back: Screen, label: String = "", wa: Boolean = false): Action {
        writeTo = to; writeText = ""; cursor = 0; writeBack = back; writeMode = 0; lastDigit = -1; commit()
        writeWa = wa; writeLabel = label
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
        Key.OK, Key.SOFT_LEFT -> { commit(); if (writeText.isBlank() || writeTo.isBlank()) Action.None else {
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

    private fun contactList(key: Key, digit: Int): Action = move(key, contacts.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> contacts.getOrNull(selected)?.let {
            if (picking) { picking = false; writeTo = it.numbers.firstOrNull().orEmpty(); screen = Screen.WRITE; writeField = 1; Action.Redraw }
            else { contact = it; screen = Screen.CONTACT; selected = 0; Action.Redraw }
        } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT -> {
            when {
                picking -> { picking = false; screen = Screen.WRITE }
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
        item.pkg == SETTINGS_PKG -> { screen = Screen.SETTINGS; selected = 0; Action.Redraw }
        item.pkg == contactsApp -> openContacts(Screen.MENU)
        item.pkg == messagesApp -> openMessages()
        item.pkg == CALC_PKG -> { calc.allClear(); screen = Screen.CALC; Action.Redraw }
        item.pkg == CALLLOG_PKG -> openCallLog(Screen.MENU)
        else -> Action.Launch(item.pkg)
    }

    private fun settings(key: Key, digit: Int): Action = move(key, settingsRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            when (selected) {
                0 -> { screen = Screen.THEME; selected = Themes.all.indexOfFirst { it.id == theme }.coerceAtLeast(0) }
                1 -> { screen = Screen.FONT; selected = Fonts.all.indexOfFirst { it.id == font }.coerceAtLeast(0) }
                2 -> { screen = Screen.TYPING; selected = if (predictiveOn) 0 else 1 }
                3 -> { screen = Screen.SPEAKER; selected = if (speakerShown) 0 else 1 }
                else -> { screen = Screen.WASET; selected = 0 }
            }
            Action.Redraw
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == SETTINGS_PKG }.coerceAtLeast(0); Action.Redraw }
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
        Key.RIGHT, Key.SOFT_RIGHT -> openContacts(Screen.HOME)
        Key.CALL -> openCallLog(Screen.HOME)
        Key.DIGIT -> if (digit in 0..9) { dialNumber = digit.toString(); screen = Screen.DIAL; Action.Redraw } else Action.None
        Key.STAR -> { dialNumber = "*"; screen = Screen.DIAL; Action.Redraw }
        Key.POUND -> { dialNumber = "#"; screen = Screen.DIAL; Action.Redraw }
        else -> Action.None
    }

    private fun menu(key: Key, digit: Int): Action = when (key) {
        Key.UP -> if (items.isEmpty()) Action.None else { selected = (selected - 1 + items.size) % items.size; Action.Redraw }
        Key.DOWN -> if (items.isEmpty()) Action.None else { selected = (selected + 1) % items.size; Action.Redraw }
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> open(items.getOrNull(selected))
        Key.RIGHT, Key.SOFT_RIGHT, Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> openCallLog(Screen.MENU)
        // 1 to 9 jump straight to that entry, like a Nokia menu
        Key.DIGIT -> if (digit in 1..9) items.getOrNull(digit - 1)?.let { selected = digit - 1; open(it) } ?: Action.None else Action.None
        else -> Action.None
    }
}

/** What the home screen shows besides the menu. Plain data so a skin can draw it any way it likes. */
data class HomeInfo(
    val time: String,
    val date: String,
    val modeLine: String,
    val missedCalls: Int,
    val newMessages: Int,
    val batteryPct: Int,
    val charging: Boolean,
    /** -1 means no SIM, otherwise 0 to 4 bars. */
    val signalBars: Int,
    val softLeft: String,
    val softRight: String,
)

/** A way of drawing the launcher. The engine never knows which one is in use. */
interface LauncherSkin {
    val id: String
    fun create(context: android.content.Context): android.view.View
    fun render(model: LauncherModel, info: HomeInfo)
}
