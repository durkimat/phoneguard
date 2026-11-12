package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A record of where the right soft key (Back) goes from each screen, and which row it lands on. It pins today's behaviour
 * so the navigation can be reworked (a back stack instead of per-screen "from" variables) without changing what the user sees.
 */
class NavigationBackTest {
    private val items = listOf(
        MenuItem("com.android.dialer", "Phone"), MenuItem("com.android.messaging", "Messages"), MenuItem("com.android.contacts", "Contacts"),
        MenuItem(CALLLOG_PKG, "Calls"), MenuItem(NOTES_PKG, "Notes"), MenuItem(WEATHER_PKG, "Weather"), MenuItem(GAMES_PKG, "Games"),
        MenuItem(APPS_PKG, "Apps"), MenuItem(CALC_PKG, "Calculator"), MenuItem(MUSIC_PKG, "Music"), MenuItem(SETTINGS_PKG, "Settings"), MenuItem(LOGSET_PKG, "Logs"),
    )

    /** Presses the keys in [path]: names of [Key], "D7" for the digit 7, and "DOWN*3" to repeat. */
    private fun run(m: LauncherModel, path: String) {
        for (tok in path.split(" ").filter { it.isNotEmpty() }) {
            val (name, n) = tok.split("*").let { it[0] to (it.getOrNull(1)?.toInt() ?: 1) }
            repeat(n) { if (name.length == 2 && name[0] == 'D' && name[1].isDigit()) m.onKey(Key.DIGIT, name[1] - '0') else m.onKey(Key.valueOf(name)) }
        }
    }

    private val cases = listOf(
        "menu" to "OK",
        "phone" to "OK OK",
        "messages" to "OK DOWN OK",
        "contacts" to "OK DOWN*2 OK",
        "calls" to "OK DOWN*3 OK",
        "notes" to "OK DOWN*4 OK",
        "weather" to "OK DOWN*5 OK",
        "games" to "OK DOWN*6 OK",
        "apps" to "OK DOWN*7 OK",
        "calculator" to "OK DOWN*8 OK",
        "musicMenu" to "OK DOWN*9 OK",
        "settings" to "OK DOWN*10 OK",
        "theme" to "OK DOWN*10 OK OK",
        "font" to "OK DOWN*10 OK DOWN OK",
        "typing" to "OK DOWN*10 OK DOWN*2 OK",
        "dialFromHome" to "D5",
        "callLogFromHome" to "CALL CALL",
        "snakeHelp" to "OK DOWN*6 OK OK",
        "speaker" to "OK DOWN*10 OK DOWN*3 OK",
        "soundTest" to "OK DOWN*10 OK DOWN*4 OK",
        "connections" to "OK DOWN*10 OK DOWN*5 OK",
        "battery" to "OK DOWN*10 OK DOWN*6 OK",
        "logSet" to "OK DOWN*11 OK",
        "callsFromLogSet" to "OK DOWN*11 OK OK",
        "notesFromLogSet" to "OK DOWN*11 OK DOWN OK",
        "settingsFromLogSet" to "OK DOWN*11 OK DOWN*2 OK",
        "themeFromLogSet" to "OK DOWN*11 OK DOWN*2 OK OK",
        "padToCalls" to "OK OK LEFT",
        "padToContacts" to "OK OK LEFT LEFT",
        "contactsBackToPad" to "OK OK LEFT LEFT LEFT",
        "callKeyInMenu" to "OK CALL", // Back returns to the row the green key was pressed on (it used to jump to the Calls entry)
        "callKeyOnHome" to "CALL",
        "musicMenuFromHome" to "RIGHT",
        "nowPlaying" to "OK DOWN*9 OK OK",
        "sourcePicker" to "OK DOWN*9 OK OK POUND",
    )

    private fun model() = LauncherModel(items, contactsApp = "com.android.contacts", messagesApp = "com.android.messaging", dialerApp = "com.android.dialer")

    private fun line(name: String, path: String, back: Key): String {
        val m = model()
        m.logItems = listOf(MenuItem(CALLLOG_PKG, "Calls"), MenuItem(NOTES_PKG, "Notes"), MenuItem(SETTINGS_PKG, "Settings"))
        run(m, path)
        val before = "${m.screen}"
        m.onKey(back)
        return "$name: $before -${back.name}-> ${m.screen}/${m.selected}"
    }

    @Test fun softRightGoesBackToWhereYouCameFrom() {
        val actual = cases.joinToString("\n") { (n, p) -> line(n, p, Key.SOFT_RIGHT) }
        assertEquals(GOLDEN_BACK.trim(), actual.trim())
    }

    @Test fun theDialPadOpenedFromHomeGoesHomeEvenAfterTheMenuOpenedItEarlier() {
        val m = model()
        run(m, "OK OK END STAR SOFT_RIGHT SOFT_RIGHT") // Menu > Phone, Home, then * on Home: Back clears the star, then leaves
        assertEquals(Screen.HOME, m.screen)
    }

    @Test fun endGoesHomeFromEverywhereItIsHandled() {
        val actual = cases.filter { it.first !in setOf("dialFromHome", "callLogFromHome", "callKeyOnHome") }.joinToString("\n") { (n, p) -> line(n, p, Key.END) }
        assertEquals(GOLDEN_END.trim(), actual.trim())
    }

    private companion object {
        const val GOLDEN_BACK = """
menu: MENU -SOFT_RIGHT-> HOME/0
phone: DIAL -SOFT_RIGHT-> MENU/0
messages: MESSAGES -SOFT_RIGHT-> MENU/1
contacts: CONTACTS -SOFT_RIGHT-> MENU/2
calls: CALLLOG -SOFT_RIGHT-> MENU/3
notes: NOTES -SOFT_RIGHT-> MENU/4
weather: WEATHER -SOFT_RIGHT-> MENU/5
games: GAMES -SOFT_RIGHT-> MENU/6
apps: APPS -SOFT_RIGHT-> MENU/7
calculator: CALC -SOFT_RIGHT-> MENU/8
musicMenu: MUSICMENU -SOFT_RIGHT-> MENU/9
settings: SETTINGS -SOFT_RIGHT-> MENU/10
theme: THEME -SOFT_RIGHT-> SETTINGS/0
font: FONT -SOFT_RIGHT-> SETTINGS/1
typing: TYPING -SOFT_RIGHT-> SETTINGS/2
dialFromHome: DIAL -SOFT_RIGHT-> DIAL/0
callLogFromHome: CALLLOG -SOFT_RIGHT-> HOME/0
snakeHelp: GAMEHELP -SOFT_RIGHT-> GAMES/0
speaker: SPEAKER -SOFT_RIGHT-> SETTINGS/3
soundTest: SOUNDTEST -SOFT_RIGHT-> SETTINGS/4
connections: CONNECTIONS -SOFT_RIGHT-> SETTINGS/5
battery: BATTERY -SOFT_RIGHT-> SETTINGS/6
logSet: LOGSET -SOFT_RIGHT-> MENU/11
callsFromLogSet: CALLLOG -SOFT_RIGHT-> LOGSET/0
notesFromLogSet: NOTES -SOFT_RIGHT-> LOGSET/1
settingsFromLogSet: SETTINGS -SOFT_RIGHT-> LOGSET/2
themeFromLogSet: THEME -SOFT_RIGHT-> SETTINGS/0
padToCalls: CALLLOG -SOFT_RIGHT-> MENU/0
padToContacts: CONTACTS -SOFT_RIGHT-> MENU/0
contactsBackToPad: DIAL -SOFT_RIGHT-> MENU/0
callKeyInMenu: CALLLOG -SOFT_RIGHT-> MENU/0
callKeyOnHome: DIAL -SOFT_RIGHT-> HOME/0
musicMenuFromHome: MUSICMENU -SOFT_RIGHT-> HOME/0
nowPlaying: MUSIC -SOFT_RIGHT-> HOME/0
sourcePicker: MUSICMENU -SOFT_RIGHT-> HOME/0
"""
        const val GOLDEN_END = """
menu: MENU -END-> HOME/0
phone: DIAL -END-> HOME/0
messages: MESSAGES -END-> HOME/0
contacts: CONTACTS -END-> HOME/0
calls: CALLLOG -END-> HOME/0
notes: NOTES -END-> HOME/0
weather: WEATHER -END-> HOME/0
games: GAMES -END-> HOME/0
apps: APPS -END-> HOME/0
calculator: CALC -END-> HOME/0
musicMenu: MUSICMENU -END-> HOME/0
settings: SETTINGS -END-> HOME/0
theme: THEME -END-> HOME/0
font: FONT -END-> HOME/0
typing: TYPING -END-> HOME/0
snakeHelp: GAMEHELP -END-> HOME/0
speaker: SPEAKER -END-> HOME/0
soundTest: SOUNDTEST -END-> HOME/0
connections: CONNECTIONS -END-> HOME/0
battery: BATTERY -END-> HOME/0
logSet: LOGSET -END-> HOME/0
callsFromLogSet: CALLLOG -END-> HOME/0
notesFromLogSet: NOTES -END-> HOME/0
settingsFromLogSet: SETTINGS -END-> HOME/0
themeFromLogSet: THEME -END-> HOME/0
padToCalls: CALLLOG -END-> HOME/0
padToContacts: CONTACTS -END-> HOME/0
contactsBackToPad: DIAL -END-> HOME/0
callKeyInMenu: CALLLOG -END-> HOME/0
musicMenuFromHome: MUSICMENU -END-> HOME/0
nowPlaying: MUSIC -END-> HOME/0
sourcePicker: MUSICMENU -END-> HOME/0
"""
    }
}
