package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The restructured menus (0.4.16): a short main menu with the digit shortcuts the owner asked for, Settings as numbered groups, and lists that remember their place. */
class MenuRestructureTest {
    private val builtIns = listOf(
        MenuItem("com.android.dialer", "Phone"), MenuItem("com.android.messaging", "Messages"), MenuItem("com.android.contacts", "Contacts"),
        MenuItem(NOTES_PKG, "Notifications"), MenuItem(RUNNING_PKG, "Running Apps"), MenuItem(CAMERA_PKG, "Camera"), MenuItem(MAIL_PKG, "Email"),
        MenuItem(CALENDAR_PKG, "Calendar"), MenuItem(CLOCK_PKG, "Clock"), MenuItem(MUSIC_PKG, "Music"), MenuItem(GAMES_PKG, "Classic Games"), MenuItem(CALLLOG_PKG, "Call log"),
        MenuItem(CALC_PKG, "Calculator"), MenuItem(WEATHER_PKG, "Weather"), MenuItem(WHERE_PKG, "Navigate"), MenuItem(SETTINGS_PKG, "Settings"),
    )
    private val all = builtIns + MenuItem("a.maps", "Maps")

    private fun phone(edition: String = "family"): LauncherModel = LauncherModel(LauncherData.menu(all, emptyList()), contactsApp = "com.android.contacts", messagesApp = "com.android.messaging", dialerApp = "com.android.dialer").also {
        it.edition = edition; it.extraItems = LauncherData.settingsExtras(all); it.extrasMenu = LauncherData.extrasMenu(all, emptyList())
    }

    @Test fun theMainMenuIsNineEntriesAndTheOnesAModeCanHideComeLast() {
        assertEquals(listOf("Phone", "Messages", "Contacts", "Calendar", "Clock", "Extras", "Settings", "Camera", "Music"), LauncherData.menu(all, emptyList()).map { it.label })
        assertEquals(listOf("Notifications", "Running Apps", "Email", "Call log"), LauncherData.settingsExtras(all).map { it.label })
        assertEquals(listOf("Weather", "Classic Games", "Calculator", "Navigate", "Android Apps"), LauncherData.extrasMenu(all, emptyList()).map { it.label })
        // a mode with no camera and no music: nothing left a gap, the others keep their places
        val school = all.filter { it.pkg != CAMERA_PKG && it.pkg != MUSIC_PKG }
        assertEquals(listOf("Phone", "Messages", "Contacts", "Calendar", "Clock", "Extras", "Settings"), LauncherData.menu(school, emptyList()).map { it.label })
    }

    @Test fun settingsIsNumberedGroups() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 7)
        assertEquals(Screen.SETTINGS, m.screen)
        assertEquals(listOf("Sound", "Look", "Connections", "This phone", "Running Apps"), m.rows)
    }

    @Test fun theKeyPressGoalsAreReached() {
        fun keys(vararg d: Int): LauncherModel { val m = phone(); m.onKey(Key.OK); d.forEach { m.onKey(Key.DIGIT, it) }; return m }
        val log = phone(); log.onKey(Key.OK); log.onKey(Key.DIGIT, 1); log.onKey(Key.SOFT_LEFT); log.onKey(Key.DIGIT, 1)
        assertEquals(Screen.CALLLOG, log.screen) // Menu, Phone, Options, Call log
        assertEquals(Screen.DATA, keys(7, 3, 3).screen) // Menu, Settings, Connections, Mobile data
        assertEquals(Screen.FORM, keys(7, 4, 2).screen) // Menu, Settings, This phone, Screen lock
        assertEquals(Screen.THEME, keys(7, 2, 1).screen) // Menu, Settings, Look, Theme
        assertEquals(Screen.NOTES, keys(2, 3).screen); assertEquals(Screen.MAILBOX, keys(2, 4).screen) // Messages: Notices, Email (WhatsApp is off here)
        assertEquals(Screen.RUNNING, keys(7, 5).screen); assertEquals(Screen.WEATHER, keys(6, 1).screen)
        assertEquals(Screen.GAMES, keys(6, 2).screen); assertEquals(Screen.CALC, keys(6, 3).screen)
    }

    @Test fun thisPhoneListsBatteryFirstAndFollowsTheEdition() {
        val open = phone("open"); open.onKey(Key.OK); open.onKey(Key.DIGIT, 7); open.onKey(Key.DIGIT, 4)
        assertEquals(listOf("Battery", "Screen lock", "Time zone", "Language", "Typing", "Phone options", "Phone type"), open.rows)
        val fam = phone(); fam.androidSettings = true; fam.onKey(Key.OK); fam.onKey(Key.DIGIT, 7); fam.onKey(Key.DIGIT, 4)
        assertEquals(listOf("Battery", "Screen lock", "Time zone", "Language", "Typing", "Phone type", "Android settings"), fam.rows)
    }

    @Test fun aListOpensWhereItWasLeftUntilTheNextTimeHome() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 7); m.onKey(Key.DIGIT, 3)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); assertEquals(2, m.selected) // Mobile data
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.SETTINGS, m.screen); assertEquals(2, m.selected)
        m.onKey(Key.OK); assertEquals(Screen.CONNECTIONS, m.screen); assertEquals(2, m.selected) // back in Connections, on Mobile data
        m.onKey(Key.END); m.onKey(Key.OK); m.onKey(Key.DIGIT, 7); m.onKey(Key.OK) // home forgets: Settings, first row, Sound
        assertEquals(Screen.SOUNDSET, m.screen)
    }
}
