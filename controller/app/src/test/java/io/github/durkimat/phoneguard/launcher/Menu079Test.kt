package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 0.4.79 menu tree: Extras, Phone options, Messages, Sound, This phone, and the Home right key. */
class Menu079Test {
    private fun phone(): LauncherModel {
        val all = listOf(MenuItem("com.android.dialer", "Phone"), MenuItem("com.android.messaging", "Messages"), MenuItem("com.android.contacts", "Contacts"), MenuItem(CALENDAR_PKG, "Calendar"), MenuItem(CLOCK_PKG, "Clock"),
            MenuItem(SETTINGS_PKG, "Settings"), MenuItem(WEATHER_PKG, "Weather"), MenuItem(GAMES_PKG, "Games"), MenuItem(CALC_PKG, "Calculator"), MenuItem("a.pin", "Pinned"), MenuItem("a.other", "Other"))
        return LauncherModel(LauncherData.menu(all, listOf("a.pin")), contactsApp = "com.android.contacts", messagesApp = "com.android.messaging", dialerApp = "com.android.dialer").also {
            it.extrasMenu = LauncherData.extrasMenu(all, listOf("a.pin")); it.extraItems = LauncherData.settingsExtras(all)
        }
    }

    @Test fun pinnedAppsComeBeforeNavigateAndAndroidApps() {
        val all = listOf(MenuItem(WEATHER_PKG, "Weather"), MenuItem(GAMES_PKG, "Games"), MenuItem(CALC_PKG, "Calculator"), MenuItem(WHERE_PKG, "Navigate"), MenuItem("a.pin", "Pinned"), MenuItem("a.other", "Other"))
        assertEquals(listOf("Weather", "Games", "Calculator", "Pinned", "Navigate", "Android Apps"), LauncherData.extrasMenu(all, listOf("a.pin")).map { it.label })
    }

    @Test fun extrasOpensItsEntriesByDigitAndBackLandsOnExtras() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 6)
        assertEquals(Screen.EXTRAS, m.screen); assertEquals(listOf("Weather", "Games", "Calculator", "Pinned", "Android Apps"), m.rows)
        m.onKey(Key.DIGIT, 2); assertEquals(Screen.GAMES, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.EXTRAS, m.screen); assertEquals(1, m.selected)
        assertEquals(Action.Launch("a.pin"), m.onKey(Key.DIGIT, 4))
    }

    @Test fun phoneOptionsOpenCallLogAndContactsAndBackReturnsToThePad() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 1)
        assertEquals(Screen.DIAL, m.screen); assertEquals("Options" to "Back", m.softKeys)
        m.onKey(Key.SOFT_LEFT); assertEquals(Screen.PHONEMENU, m.screen); assertEquals(listOf("Call log", "Contacts"), m.rows)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.DIAL, m.screen)
        m.onKey(Key.SOFT_LEFT); m.onKey(Key.DIGIT, 2); assertEquals(Screen.CONTACTS, m.screen)
    }

    @Test fun messagesListsTextsWriteWhatsAppNoticesAndEmailLastWithNoGaps() {
        val m = phone(); m.waEnabled = true
        m.extraItems = listOf(MenuItem(NOTES_PKG, "Notes"), MenuItem(MAIL_PKG, "Email"))
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 2)
        assertEquals(listOf("Texts", "Write message", "WhatsApp", "Notices", "Email"), m.rows)
        m.waEnabled = false; assertEquals(listOf("Texts", "Write message", "Notices", "Email"), m.rows) // WhatsApp hidden: the rest moves up, no gap
        m.extraItems = emptyList(); assertEquals(listOf("Texts", "Write message"), m.rows)
    }

    @Test fun soundHasRingerCallsMessagesMediaCallVolumeAndTheSoundTest() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 7); m.onKey(Key.DIGIT, 1)
        assertEquals(Screen.SOUNDSET, m.screen); assertEquals("Sound", m.title)
        assertEquals(listOf("Ringer mode", "Calls", "Messages", "Media volume", "Call volume", "Sound test"), m.rows)
    }

    @Test fun vibrateIsAnOnOffPerKindAndSpeakerphoneIsARowInCalls() {
        val m = phone(); val got = ArrayList<Pair<Int, Boolean>>(); var vib = listOf(true, true)
        m.soundInfo = { LauncherModel.SoundInfo(0, null, List(4) { LauncherModel.Vol(3, 7, 3, 7) }, vib) }
        m.onVibrate = { k, on -> got.add(k to on); vib = vib.toMutableList().also { it[k] = on } }
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 7); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 2) // Calls
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); assertEquals("Vibrate: On", m.rows[m.selected])
        m.onKey(Key.OK); assertEquals(listOf(0 to false), got); assertEquals("Vibrate: Off", m.rows[m.selected])
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 3); repeat(3) { m.onKey(Key.DOWN) }; m.onKey(Key.OK) // Messages > Vibrate
        assertEquals(listOf(0 to false, 1 to false), got)
    }

    @Test fun thisPhoneStartsWithBatteryAndHasTyping() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 7); m.onKey(Key.DIGIT, 4)
        assertEquals(Screen.THISPHONE, m.screen); assertEquals("Battery", m.rows[0]); assertEquals("Typing", m.rows[4])
        m.onKey(Key.DIGIT, 5); assertEquals(Screen.TYPING, m.screen)
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 1); assertEquals(Screen.BATTERY, m.screen)
    }

    @Test fun connectionsHasNoBatteryAnyMore() {
        val m = phone(); m.onKey(Key.OK); m.onKey(Key.DIGIT, 7); m.onKey(Key.DIGIT, 3)
        assertEquals(listOf("Wi-Fi: On", "Bluetooth: On", "Mobile data"), m.rows)
    }

    @Test fun homeDpadRightIsContactsUntilAZoneIsChosen() {
        val m = phone()
        m.onKey(Key.RIGHT); assertEquals(Screen.CONTACTS, m.screen); m.goHome()
        m.onKey(Key.DOWN); assertTrue(m.homeFocus >= 0); m.onKey(Key.RIGHT); assertEquals(Screen.HOME, m.screen) // with a zone chosen the arrows navigate the zone
    }
}
