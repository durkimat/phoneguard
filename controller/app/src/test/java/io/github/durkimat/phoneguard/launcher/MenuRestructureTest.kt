package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The restructured menus (0.4.16): a short main menu with the digit shortcuts the owner asked for, Settings as numbered groups, and lists that remember their place. */
class MenuRestructureTest {
    private val builtIns = listOf(
        MenuItem(NOTES_PKG, "Notifications"), MenuItem(RUNNING_PKG, "Running Apps"), MenuItem(CAMERA_PKG, "Camera"), MenuItem(MAIL_PKG, "Email"),
        MenuItem(CALENDAR_PKG, "Calendar"), MenuItem(MUSIC_PKG, "Music"), MenuItem(GAMES_PKG, "Classic Games"), MenuItem(CALLLOG_PKG, "Call log"),
        MenuItem(CALC_PKG, "Calculator"), MenuItem(SETTINGS_PKG, "Settings"),
    )
    private val all = builtIns + MenuItem("a.maps", "Maps")

    private fun phone(edition: String = "family"): LauncherModel = LauncherModel(LauncherData.menu(all, emptyList())).also {
        it.edition = edition; it.extraItems = LauncherData.settingsExtras(all)
    }

    @Test fun theMainMenuIsShortAndHasNoLogsFolder() {
        assertEquals(listOf("Email", "Calendar", "Music", "Camera", "Android Apps", "Classic Games", "Calculator", "Call log", "Settings"), LauncherData.menu(all, emptyList()).map { it.label })
        assertEquals(listOf("Notifications", "Running Apps"), LauncherData.settingsExtras(all).map { it.label })
    }

    @Test fun settingsIsNumberedGroups() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 9)
        assertEquals(Screen.SETTINGS, m.screen)
        assertEquals(listOf("Look", "Sound and typing", "Connections", "This phone", "Notifications", "Running Apps"), m.rows)
    }

    @Test fun theKeyPressGoalsAreReached() {
        fun keys(vararg d: Int): LauncherModel { val m = phone(); m.onKey(Key.OK); d.forEach { m.onKey(Key.DIGIT, it) }; return m }
        assertEquals(Screen.CALLLOG, keys(8).screen) // Menu, 8
        assertEquals(Screen.DATA, keys(9, 3, 3).screen) // Menu, 9, 3, 3
        assertEquals(Screen.FORM, keys(9, 4, 1).screen) // Menu, 9, 4, 1: Screen lock
        assertEquals(Screen.THEME, keys(9, 1, 1).screen) // Menu, 9, 1, 1
        assertEquals(Screen.NOTES, keys(9, 5).screen); assertEquals(Screen.RUNNING, keys(9, 6).screen)
    }

    @Test fun thisPhoneListsScreenLockFirstAndFollowsTheEdition() {
        val open = phone("open"); open.onKey(Key.OK); open.onKey(Key.DIGIT, 9); open.onKey(Key.DIGIT, 4)
        assertEquals(listOf("Screen lock", "Time zone", "Language", "Phone options", "Phone type"), open.rows)
        val fam = phone(); fam.waEnabled = true; fam.androidSettings = true; fam.onKey(Key.OK); fam.onKey(Key.DIGIT, 9); fam.onKey(Key.DIGIT, 4)
        assertEquals(listOf("Screen lock", "Time zone", "Language", "Phone type", "WhatsApp", "Android settings"), fam.rows)
    }

    @Test fun aListOpensWhereItWasLeftUntilTheNextTimeHome() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 9); m.onKey(Key.DIGIT, 3)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); m.onKey(Key.DOWN); assertEquals(3, m.selected) // Battery
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.SETTINGS, m.screen); assertEquals(2, m.selected)
        m.onKey(Key.OK); assertEquals(Screen.CONNECTIONS, m.screen); assertEquals(3, m.selected) // back in Connections, on Battery
        m.onKey(Key.END); m.onKey(Key.OK); m.onKey(Key.DIGIT, 9); m.onKey(Key.OK) // home forgets: Settings, first row, Look
        assertEquals(Screen.LOOK, m.screen)
    }
}
