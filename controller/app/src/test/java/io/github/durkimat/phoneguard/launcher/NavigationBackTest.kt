package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A record of where the right soft key (Back) goes from each screen, and which row it lands on, under the rule in
 * docs/navigation-rules.md (Back retraces the way the person came).
 */
class NavigationBackTest {
    private val all = listOf(
        MenuItem("com.android.dialer", "Phone"), MenuItem("com.android.messaging", "Messages"), MenuItem("com.android.contacts", "Contacts"),
        MenuItem(CALENDAR_PKG, "Calendar"), MenuItem(CLOCK_PKG, "Clock"), MenuItem(MUSIC_PKG, "Music"), MenuItem(WEATHER_PKG, "Weather"), MenuItem(GAMES_PKG, "Games"),
        MenuItem(CALC_PKG, "Calculator"), MenuItem(CALLLOG_PKG, "Calls"), MenuItem(SETTINGS_PKG, "Settings"), MenuItem(NOTES_PKG, "Notes"), MenuItem(RUNNING_PKG, "Running"),
        MenuItem(MAIL_PKG, "Email"), MenuItem("a.maps", "Maps"),
    )
    private val items = LauncherData.menu(all, emptyList()) // Phone, Messages, Contacts, Calendar, Clock, Extras, Settings, Music

    /** Presses the keys in [path]: names of [Key], "D7" for the digit 7, and "DOWN*3" to repeat. */
    private fun run(m: LauncherModel, path: String) {
        for (tok in path.split(" ").filter { it.isNotEmpty() }) {
            val (name, n) = tok.split("*").let { it[0] to (it.getOrNull(1)?.toInt() ?: 1) }
            repeat(n) { if (name.length == 2 && name[0] == 'D' && name[1].isDigit()) m.onKey(Key.DIGIT, name[1] - '0') else m.onKey(Key.valueOf(name)) }
        }
    }

    private val cases = listOf(
        "menu" to "OK",
        "phone" to "OK D1",
        "phoneOptions" to "OK D1 LEFT",
        "callLogFromPhoneOptions" to "OK D1 LEFT D1",
        "contactsFromPhoneOptions" to "OK D1 LEFT D2",
        "messages" to "OK D2",
        "texts" to "OK D2 D1",
        "writeMessage" to "OK D2 D2",
        "notices" to "OK D2 D3",
        "email" to "OK D2 D4",
        "contacts" to "OK D3",
        "calendar" to "OK D4",
        "clock" to "OK D5",
        "extras" to "OK D6",
        "weather" to "OK D6 D1",
        "games" to "OK D6 D2",
        "calculator" to "OK D6 D3",
        "androidApps" to "OK D6 D4",
        "settings" to "OK D7",
        "sound" to "OK D7 D1",
        "ringerMode" to "OK D7 D1 D1",
        "soundCalls" to "OK D7 D1 D2",
        "soundMessages" to "OK D7 D1 D3",
        "mediaVolume" to "OK D7 D1 D4",
        "callVolume" to "OK D7 D1 D5",
        "soundTest" to "OK D7 D1 D6",
        "look" to "OK D7 D2",
        "theme" to "OK D7 D2 D1",
        "font" to "OK D7 D2 D2",
        "menuStyle" to "OK D7 D2 D3",
        "appearance" to "OK D7 D2 D4",
        "appearanceChoice" to "OK D7 D2 D4 OK",
        "connections" to "OK D7 D3",
        "mobileData" to "OK D7 D3 D3",
        "thisPhone" to "OK D7 D4",
        "battery" to "OK D7 D4 D1",
        "screenLock" to "OK D7 D4 D2",
        "timeZone" to "OK D7 D4 D3",
        "language" to "OK D7 D4 D4",
        "typing" to "OK D7 D4 D5",
        "runningFromSettings" to "OK D7 D5",
        "dialFromHome" to "D5",
        "callLogFromHome" to "CALL CALL",
        "snakeHelp" to "OK D6 D2 OK",
        "contactsFromHome" to "RIGHT",
        "callKeyInMenu" to "OK CALL", // Back returns to the row the green key was pressed on
        "callKeyOnHome" to "CALL",
        "musicFromMenu" to "OK D8", // (the long press of the right soft key on Home is timed by the activity)
        "nowPlaying" to "OK D8 D1",
    )

    private fun model() = LauncherModel(items, contactsApp = "com.android.contacts", messagesApp = "com.android.messaging", dialerApp = "com.android.dialer")

    private fun line(name: String, path: String, back: Key): String {
        val m = model()
        m.extraItems = LauncherData.settingsExtras(all); m.extrasMenu = LauncherData.extrasMenu(all, emptyList())
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
phoneOptions: PHONEMENU -SOFT_RIGHT-> DIAL/0
callLogFromPhoneOptions: CALLLOG -SOFT_RIGHT-> PHONEMENU/0
contactsFromPhoneOptions: CONTACTS -SOFT_RIGHT-> PHONEMENU/1
messages: MESSAGES -SOFT_RIGHT-> MENU/1
texts: INBOX -SOFT_RIGHT-> MESSAGES/0
writeMessage: WRITE -SOFT_RIGHT-> MESSAGES/1
notices: NOTES -SOFT_RIGHT-> MESSAGES/2
email: MAILBOX -SOFT_RIGHT-> MESSAGES/3
contacts: CONTACTS -SOFT_RIGHT-> MENU/2
calendar: CALENDAR -SOFT_RIGHT-> MENU/3
clock: CLOCK -SOFT_RIGHT-> MENU/4
extras: EXTRAS -SOFT_RIGHT-> MENU/5
weather: WEATHER -SOFT_RIGHT-> EXTRAS/0
games: GAMES -SOFT_RIGHT-> EXTRAS/1
calculator: CALC -SOFT_RIGHT-> EXTRAS/2
androidApps: APPS -SOFT_RIGHT-> EXTRAS/3
settings: SETTINGS -SOFT_RIGHT-> MENU/6
sound: SOUNDSET -SOFT_RIGHT-> SETTINGS/0
ringerMode: FORM -SOFT_RIGHT-> SOUNDSET/0
soundCalls: FORM -SOFT_RIGHT-> SOUNDSET/1
soundMessages: FORM -SOFT_RIGHT-> SOUNDSET/2
mediaVolume: FORM -SOFT_RIGHT-> SOUNDSET/3
callVolume: FORM -SOFT_RIGHT-> SOUNDSET/4
soundTest: SOUNDTEST -SOFT_RIGHT-> SOUNDSET/5
look: LOOK -SOFT_RIGHT-> SETTINGS/1
theme: THEME -SOFT_RIGHT-> LOOK/0
font: FONT -SOFT_RIGHT-> LOOK/1
menuStyle: MENUSTYLE -SOFT_RIGHT-> LOOK/2
appearance: APPEARANCE -SOFT_RIGHT-> LOOK/3
appearanceChoice: CHOICE -SOFT_RIGHT-> APPEARANCE/0
connections: CONNECTIONS -SOFT_RIGHT-> SETTINGS/2
mobileData: DATA -SOFT_RIGHT-> CONNECTIONS/2
thisPhone: THISPHONE -SOFT_RIGHT-> SETTINGS/3
battery: BATTERY -SOFT_RIGHT-> THISPHONE/0
screenLock: THISPHONE -SOFT_RIGHT-> SETTINGS/3
timeZone: TIMEZONE -SOFT_RIGHT-> THISPHONE/2
language: LANGUAGE -SOFT_RIGHT-> THISPHONE/3
typing: TYPING -SOFT_RIGHT-> THISPHONE/4
runningFromSettings: RUNNING -SOFT_RIGHT-> SETTINGS/4
dialFromHome: DIAL -SOFT_RIGHT-> DIAL/0
callLogFromHome: CALLLOG -SOFT_RIGHT-> DIAL/0
snakeHelp: GAMEHELP -SOFT_RIGHT-> GAMES/0
contactsFromHome: CONTACTS -SOFT_RIGHT-> HOME/0
callKeyInMenu: CALLLOG -SOFT_RIGHT-> MENU/0
callKeyOnHome: DIAL -SOFT_RIGHT-> HOME/0
musicFromMenu: MUSICMENU -SOFT_RIGHT-> MENU/7
nowPlaying: MUSIC -SOFT_RIGHT-> MUSICMENU/0
"""
        const val GOLDEN_END = """
menu: MENU -END-> HOME/0
phone: DIAL -END-> HOME/0
phoneOptions: PHONEMENU -END-> HOME/0
callLogFromPhoneOptions: CALLLOG -END-> HOME/0
contactsFromPhoneOptions: CONTACTS -END-> HOME/0
messages: MESSAGES -END-> HOME/0
texts: INBOX -END-> HOME/0
writeMessage: WRITE -END-> HOME/0
notices: NOTES -END-> HOME/0
email: MAILBOX -END-> HOME/0
contacts: CONTACTS -END-> HOME/0
calendar: CALENDAR -END-> HOME/0
clock: CLOCK -END-> HOME/0
extras: EXTRAS -END-> HOME/0
weather: WEATHER -END-> HOME/0
games: GAMES -END-> HOME/0
calculator: CALC -END-> HOME/0
androidApps: APPS -END-> HOME/0
settings: SETTINGS -END-> HOME/0
sound: SOUNDSET -END-> HOME/0
ringerMode: FORM -END-> HOME/0
soundCalls: FORM -END-> HOME/0
soundMessages: FORM -END-> HOME/0
mediaVolume: FORM -END-> HOME/0
callVolume: FORM -END-> HOME/0
soundTest: SOUNDTEST -END-> HOME/0
look: LOOK -END-> HOME/0
theme: THEME -END-> HOME/0
font: FONT -END-> HOME/0
menuStyle: MENUSTYLE -END-> HOME/0
appearance: APPEARANCE -END-> HOME/0
appearanceChoice: CHOICE -END-> HOME/0
connections: CONNECTIONS -END-> HOME/0
mobileData: DATA -END-> HOME/0
thisPhone: THISPHONE -END-> HOME/0
battery: BATTERY -END-> HOME/0
screenLock: THISPHONE -END-> HOME/0
timeZone: TIMEZONE -END-> HOME/0
language: LANGUAGE -END-> HOME/0
typing: TYPING -END-> HOME/0
runningFromSettings: RUNNING -END-> HOME/0
snakeHelp: GAMEHELP -END-> HOME/0
contactsFromHome: CONTACTS -END-> HOME/0
callKeyInMenu: CALLLOG -END-> HOME/0
musicFromMenu: MUSICMENU -END-> HOME/0
nowPlaying: MUSIC -END-> HOME/0
"""
    }
}
