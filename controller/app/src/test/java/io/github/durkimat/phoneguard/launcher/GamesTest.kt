package io.github.durkimat.phoneguard.launcher

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamesTest {
    @Test fun pairsMatchStaysAndMissTurnsBack() {
        val g = Pairs(Random(3))
        val a = 0; val same = (1 until 16).first { g.cards[it] == g.cards[a] }; val other = (1 until 16).first { g.cards[it] != g.cards[a] }
        g.select(a); g.pick(); g.select(same); g.pick()
        assertEquals(2, g.shown[a]); assertEquals(2, g.shown[same]); assertEquals(1, g.moves)
        val b = (0 until 16).first { g.shown[it] == 0 }; val c = (0 until 16).first { g.shown[it] == 0 && g.cards[it] != g.cards[b] }
        g.select(b); g.pick(); g.select(c); g.pick()
        assertTrue(g.waiting); assertEquals(1, g.shown[b])
        g.resolve(); assertEquals(0, g.shown[b]); assertEquals(0, g.shown[c]); assertEquals(2, g.moves)
        assertTrue(other >= 0)
    }

    @Test fun pairsCanBeFinished() {
        val g = Pairs(Random(5))
        for (s in 0 until 8) { val idx = (0 until 16).filter { g.cards[it] == s }; g.select(idx[0]); g.pick(); g.select(idx[1]); g.pick() }
        assertTrue(g.won); assertEquals(8, g.moves)
    }

    private fun blaster(): Shooter { val g = Shooter(rnd = Random(1)); g.fireDown(); g.fireUp(); return g }

    @Test fun theGunFiresByItselfAndDiagonalKeysMoveTwoWays() {
        val g = blaster(); repeat(7) { g.tick() }
        assertTrue(g.shots.isNotEmpty())
        val m = LauncherModel(listOf(MenuItem(GAMES_PKG, "Games"))); m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 3); m.onKey(Key.OK)
        assertEquals(Screen.SHOOTER, m.screen)
        val y = m.shooter.shipY; val x = m.shooter.shipX
        m.onKey(Key.DIGIT, 3); m.shooter.tick(); m.shooter.tick()
        assertTrue(m.shooter.shipY < y && m.shooter.shipX > x)
        val y2 = m.shooter.shipY; m.onRelease(Key.DIGIT, 3); m.shooter.tick(); assertEquals(y2, m.shooter.shipY)
    }

    @Test fun aShotKillsAnEnemyInItsLane() {
        val g = blaster(); g.fireDown(); g.fireUp()
        g.addEnemy(Shooter.Enemy(g.shipX + 14, g.shipY - 1, 7, 6, 1, 1, 0)) // a tank flies straight; drones weave
        repeat(6) { g.tick() }
        assertEquals(50, g.score)
    }

    @Test fun aChargedBeamKillsEverythingInTheLane() {
        val g = blaster(); g.fireDown()
        repeat(g.chargeNeeded + 1) { g.tick() }
        g.fireUp(); assertTrue(g.beam > 0)
        g.addEnemy(Shooter.Enemy(g.shipX + 12, g.shipY, 4, 3, 1, 0, g.shipY)); g.addEnemy(Shooter.Enemy(g.shipX + 24, g.shipY, 7, 6, 3, 1, 0))
        g.tick()
        assertTrue(g.enemies.isEmpty()); assertEquals(60, g.score)
    }

    @Test fun heldKeysMoveTheShipUntilReleased() {
        val g = blaster(); val y = g.shipY
        g.hold(0, true); repeat(3) { g.tick() }; assertEquals(y - 3, g.shipY)
        g.hold(0, false); repeat(3) { g.tick() }; assertEquals(y - 3, g.shipY)
    }

    @Test fun anEnemyCostsALifeThenTheShipBlinks() {
        val g = blaster()
        g.addEnemy(Shooter.Enemy(g.shipX, g.shipY, 4, 3, 1, 0, g.shipY)); g.tick()
        assertEquals(2, g.lives)
        g.addEnemy(Shooter.Enemy(g.shipX, g.shipY, 4, 3, 1, 0, g.shipY)); g.tick()
        assertEquals(2, g.lives)
    }

    @Test fun aCratePutsAPodOnTheShipWhichSmashesDrones() {
        val g = blaster(); g.giveCrate(g.shipX + 1, g.shipY); g.tick()
        assertTrue(g.hasPod)
        g.addEnemy(Shooter.Enemy(g.shipX + g.shipW + 1, g.shipY, 4, 3, 1, 0, g.shipY)); repeat(3) { g.tick() }
        assertEquals(10, g.score); assertEquals(3, g.lives)
    }

    @Test fun podLaunchesAheadAndComesBackAndWeaponCratesChangeTheGun() {
        val g = blaster(); g.giveCrate(g.shipX + 1, g.shipY, 0); g.tick()
        assertEquals(Shooter.PodMode.ATTACHED, g.podMode)
        g.podToggle(); repeat(10) { g.tick() }
        assertEquals(Shooter.PodMode.LAUNCHED, g.podMode); assertTrue(g.podX > g.shipX + g.shipW + 10)
        g.podToggle(); repeat(30) { g.tick() }
        assertEquals(Shooter.PodMode.ATTACHED, g.podMode)
        g.giveCrate(g.shipX + 1, g.shipY, 1); g.tick(); assertEquals(Shooter.Weapon.SPREAD, g.weapon)
        g.fireDown(); assertTrue(g.shots.size >= 3)
        g.giveCrate(g.shipX + 1, g.shipY, 3); g.tick(); assertEquals(1, g.speed)
    }

    @Test fun bouncingShotsTurnAtTheWall() {
        val g = blaster(); g.giveCrate(g.shipX + 1, g.shipY, 2); g.tick(); g.fireDown(); g.fireUp()
        repeat(8) { g.tick() }
        assertTrue(g.shots.any { it[2] != 0 && it[3] < 3 && it[1] in 0 until g.rows })
    }

    @Test fun killingTheBossClearsTheStage() {
        val g = blaster(); g.addEnemy(Shooter.Enemy(g.shipX + 12, g.shipY - 6, 12, 16, 1, 2, 0))
        g.fireDown(); g.fireUp(); repeat(6) { g.tick() }
        assertEquals(2, g.stage); assertEquals(500, g.score); assertTrue(g.banner > 0)
    }

    @Test fun seedsFirstMoveFromThirdPitGivesAnotherGo() {
        val g = Seeds(); g.select(2); assertTrue(g.play())
        assertEquals(1, g.yours); assertTrue(g.yourGo); assertEquals("Another go!", g.note)
    }

    @Test fun seedsCaptureAndTotalsAddUp() {
        val g = Seeds()
        g.select(0); g.play() // sows 1..4, last in pit 4 (not empty): turn passes
        assertFalse(g.yourGo)
        while (!g.over) {
            if (g.yourGo) { val i = (0..5).first { g.board[it] > 0 }; g.select(i); g.play() } else g.phoneMove()
        }
        assertEquals(48, g.yours + g.theirs)
        assertTrue(g.winner != null)
    }

    @Test fun gamesListOpensEachGame() {
        val m = LauncherModel(listOf(MenuItem(GAMES_PKG, "Games")))
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1)
        for ((n, s) in listOf(2 to Screen.PAIRS, 3 to Screen.SHOOTER, 4 to Screen.SEEDS)) {
            m.onKey(Key.DIGIT, n); assertEquals(Screen.GAMEHELP, m.screen); assertTrue(m.helpLines.isNotEmpty())
            m.onKey(Key.OK); assertEquals(s, m.screen)
            m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.GAMES, m.screen); assertEquals(n - 1, m.selected)
        }
    }

    @Test fun touchChoosesRowsAndSoftKeys() {
        val m = LauncherModel(LauncherData.menu(listOf(MenuItem("com.android.dialer", "Phone"), MenuItem("com.android.messaging", "Messages"), MenuItem(GAMES_PKG, "Games"), MenuItem(SETTINGS_PKG, "Settings")), emptyList()))
        assertEquals(Action.Redraw, m.tap(40f, 600f)); assertEquals(Screen.MENU, m.screen) // left soft key: Menu
        m.tap(100f, 104f + 62f * 2 + 10f) // third row
        assertEquals(Screen.GAMES, m.screen)
        m.tap(400f, 610f); assertEquals(Screen.MENU, m.screen) // right soft key: Back
        assertEquals(Action.None, m.tap(100f, 80f))
    }
}

class HomeBadgeTest {
    @Test fun tappingASymbolOnTheHomeScreenOpensItsInboxAndBackGoesHome() {
        val m = LauncherModel(listOf(MenuItem("p.phone", "Phone")))
        m.badges = listOf(Badge(BadgeKind.SMS, 2), Badge(BadgeKind.MISSED, 1))
        m.tap(m.badgeX(0, 2), 510f); assertEquals(Screen.INBOX, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen)
        m.tap(m.badgeX(1, 2), 510f); assertEquals(Screen.CALLLOG, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen)
        m.badges = emptyList(); assertEquals(Action.None, m.tap(240f, 510f))
    }

    @Test fun swipingScrollsALongListAndTapsStillPickTheRowShown() {
        val apps = (1..12).map { MenuItem("p.$it", "App $it") }
        val m = LauncherModel(apps); m.onKey(Key.OK)
        assertEquals(0, m.listFirst(LIST_VISIBLE))
        assertTrue(m.scrollBy(3)); assertEquals(3, m.listFirst(LIST_VISIBLE))
        assertTrue(m.selected in 3..9)
        assertTrue(m.scrollBy(10)); assertEquals(5, m.listFirst(LIST_VISIBLE)) // 12 rows, 7 visible: the last page starts at row 5
        assertEquals(false, m.scrollBy(1))
        assertEquals(Action.Launch("p.8"), m.tap(100f, 104f + 62f * 2 + 10f)) // the third row shown is App 8
        m.scrollBy(-10); assertEquals(0, m.listFirst(LIST_VISIBLE))
    }

    @Test fun gamesIgnoreTouch() {
        val m = LauncherModel(listOf(MenuItem(GAMES_PKG, "Games"))); m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 1); m.onKey(Key.OK)
        assertEquals(Screen.SNAKE, m.screen)
        assertEquals(Action.None, m.tap(40f, 610f)); assertEquals(Screen.SNAKE, m.screen)
    }

    @Test fun androidAppsAreHiddenWhereTouchIsOff() {
        val all = listOf(MenuItem("com.android.dialer", "Phone"), MenuItem("a.maps", "Maps"), MenuItem(SETTINGS_PKG, "Settings"))
        assertTrue(LauncherData.menu(all, emptyList(), androidApps = true).any { it.label == "Android Apps" })
        assertTrue(LauncherData.menu(all, emptyList(), androidApps = false).none { it.pkg == APPS_PKG })
        assertTrue(LauncherData.menu(all, listOf("a.maps"), androidApps = false).any { it.label == "Maps" }) // a pinned app still shows
    }

    @Test fun androidSettingsOpensAndroidsOwnSettingsWhereTouchWorks() {
        val m = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")))
        m.androidSettings = true
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1) // the menu's only entry: Settings
        assertEquals(listOf("Theme", "Font", "Menu style", "Predictive text", "Speakerphone", "Sound test", "Connections", "Android settings", "Battery"), m.rows)
        assertEquals(Action.Launch("com.android.settings"), m.onKey(Key.DIGIT, 8))
        assertEquals(Screen.BATTERY, run { m.onKey(Key.DIGIT, 9); m.screen })
        val school = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings"))); school.onKey(Key.OK); school.onKey(Key.DIGIT, 1)
        assertTrue("Android settings" !in school.rows)
    }

    @Test fun runningAppsKillsWithTheLeftKeyAndOkGoesToOurOwnScreens() {
        fun atRunning(): LauncherModel {
            val m = LauncherModel(listOf(MenuItem(LOGSET_PKG, "Logs & Settings"), MenuItem(MUSIC_PKG, "Music"), MenuItem(CAMERA_PKG, "Camera")), "com.android.contacts", "com.android.messaging")
            m.logItems = listOf(MenuItem(RUNNING_PKG, "Running Apps"))
            m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.OK)
            return m
        }
        val m = atRunning(); assertEquals(Screen.RUNNING, m.screen)
        assertEquals(listOf("Nothing running"), m.rows); assertEquals("" to "Back", m.softKeys)
        val apps = listOf(RunningApp("com.spotify.music", "Spotify", "paused"), RunningApp("org.lineageos.aperture", "Camera", "4m"), RunningApp("com.example.game", "Game", "12m"))
        m.running = apps
        assertEquals(listOf("Spotify (paused)", "Camera (4m)", "Game (12m)"), m.rows); assertEquals("Kill" to "Back", m.softKeys)
        assertEquals(Action.KillApp("com.spotify.music"), m.onKey(Key.SOFT_LEFT))
        assertEquals(Action.Redraw, m.onKey(Key.OK)); assertEquals(Screen.MUSIC, m.screen) // Spotify opens our Music screen, not the app
        val c = atRunning(); c.running = apps; c.onKey(Key.DOWN); assertEquals(Action.OpenCamera, c.onKey(Key.OK))
        val g = atRunning(); g.running = apps; g.onKey(Key.DOWN); g.onKey(Key.DOWN); assertEquals(Action.Launch("com.example.game"), g.onKey(Key.OK)) // no screen of ours for it
        g.onKey(Key.SOFT_RIGHT); assertEquals(Screen.LOGSET, g.screen)
    }

    @Test fun homeRightKeyOpensMusicAndBackComesHome() {
        val m = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music"), MenuItem("com.android.contacts", "Contacts")), "com.android.contacts")
        assertEquals("Menu" to "Music", m.softKeys)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MUSICMENU, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen)
        val none = LauncherModel(listOf(MenuItem("com.android.contacts", "Contacts")), "com.android.contacts")
        assertEquals("Menu" to "Contacts", none.softKeys) // no music in this mode: the right key stays Contacts
        none.onKey(Key.SOFT_RIGHT); assertEquals(Screen.CONTACTS, none.screen)
    }

    @Test fun wifiScreensListNetworksAndForgetOrJoinThem() {
        val m = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")))
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1) // Settings
        assertEquals(Screen.SETTINGS, m.screen)
        m.wifiOn = true
        m.wifiNets = listOf(WifiNet("Home", 3, true, true, 4, true, true, "[WPA2-PSK]"), WifiNet("Shed", 5, true, true, -1, false, false, ""), WifiNet("Cafe", -1, false, true, 2, false, true, "[WPA2-PSK]"), WifiNet("Open", -1, false, false, 1, false, true, "[ESS]"))
        m.onKey(Key.DIGIT, 7); assertEquals(Screen.CONNECTIONS, m.screen); assertEquals(listOf("Wi-Fi: On", "Bluetooth: On"), m.rows)
        assertEquals(Action.WifiLoad, m.onKey(Key.DIGIT, 1)); assertEquals(Screen.WIFI, m.screen)
        assertEquals(listOf("Wi-Fi: On", "> Home", "* Shed", "  Cafe", "  Open"), m.rows)
        assertEquals(Action.WifiToggle, m.onKey(Key.OK)) // the first row switches Wi-Fi
        m.onKey(Key.DIGIT, 2); assertEquals(Screen.WIFINET, m.screen); assertEquals(listOf("Change password", "Forget"), m.rows) // the connected network
        assertEquals(Action.WifiForget(3), m.onKey(Key.DIGIT, 2)); assertEquals(Screen.WIFI, m.screen)
        m.onKey(Key.DIGIT, 4); assertEquals(listOf("Join with password"), m.rows)
        m.onKey(Key.OK); assertEquals(Screen.WRITE, m.screen) // the password is typed on the keypad
        m.onKey(Key.DIGIT, 2); m.onKey(Key.DIGIT, 3); m.onKey(Key.POUND); m.onKey(Key.POUND); assertEquals("123", m.modeName) // no word prediction while typing a password
        assertEquals(Action.WifiJoin("Cafe", "a" + "", "[WPA2-PSK]", -1).copy(password = m.writeText), m.onKey(Key.OK)); assertEquals(Screen.WIFI, m.screen)
        m.onKey(Key.DIGIT, 5); assertEquals(listOf("Join"), m.rows) // an open network
        assertEquals(Action.WifiJoin("Open", "", "[ESS]", -1), m.onKey(Key.OK))
    }

    @Test fun bluetoothScreensPairConnectAndForget() {
        val m = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")))
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.btOn = true
        m.btDevs = listOf(BtDev("A", "AYIMA", true, false), BtDev("B", "Speaker", false, false))
        m.onKey(Key.DIGIT, 7); assertEquals(Screen.CONNECTIONS, m.screen); assertEquals(Action.BtLoad, m.onKey(Key.DIGIT, 2)); assertEquals(Screen.BT, m.screen)
        assertEquals(listOf("Bluetooth: On", "Search for devices", "* AYIMA", "  Speaker"), m.rows)
        assertEquals(Action.BtToggle, m.onKey(Key.DIGIT, 1)); assertEquals(Action.BtSearch, m.onKey(Key.DIGIT, 2))
        m.onKey(Key.DIGIT, 3); assertEquals(listOf("Connect", "Forget"), m.rows)
        assertEquals(Action.BtConnect("A", true), m.onKey(Key.DIGIT, 1)); assertEquals(Screen.BT, m.screen)
        m.onKey(Key.DIGIT, 3); assertEquals(Action.BtForget("A"), m.onKey(Key.DIGIT, 2))
        m.onKey(Key.DIGIT, 4); assertEquals(listOf("Pair"), m.rows); assertEquals(Action.BtPair("B"), m.onKey(Key.OK))
    }

    @Test fun touchOnNowPlayingPressesPlayPauseAndTheTrackWords() {
        val m = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music")))
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 1); assertEquals(Screen.MUSIC, m.screen)
        assertEquals(Action.None, m.tap(240f, 450f)) // nothing playing: nothing to touch
        m.music = io.github.durkimat.phoneguard.music.MusicInfo(true, "Spotify", "Song", "Band", "Album", true, 1000, 200_000, 40)
        assertEquals(Action.MusicPlayPause, m.tap(240f, 450f))
        assertEquals(Action.MusicPrevious, m.tap(60f, 460f)); assertEquals(Action.MusicNext, m.tap(420f, 460f))
        assertEquals(Action.None, m.tap(240f, 200f))
    }

    @Test fun aMusicSymbolOnTheHomeScreenOpensNowPlaying() {
        val m = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music")))
        m.badges = listOf(Badge(BadgeKind.MUSIC, 1))
        assertEquals(Action.Redraw, m.tap(m.badgeX(0, 1), 500f)); assertEquals(Screen.MUSIC, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen) // opened from the home screen, so Back goes straight home (not to the Music menu or the main menu)
    }

    @Test fun connectionsGathersWifiBluetoothAndMobileData() {
        val m = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")))
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 7)
        assertEquals(Screen.CONNECTIONS, m.screen); assertEquals(listOf("Wi-Fi: On", "Bluetooth: On"), m.rows) // no data row without a SIM
        m.dataShown = true; m.connWifi = 2; m.connData = 2
        assertEquals(listOf("Wi-Fi: Off", "Bluetooth: On", "Mobile data: Off"), m.rows)
        assertEquals(Action.DataToggle, m.onKey(Key.DIGIT, 3))
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.SETTINGS, m.screen)
    }

    @Test fun theStatusSymbolsOpenConnectionsAndBluetooth() {
        val m = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings"))); m.dataShown = true
        assertEquals(Action.Redraw, m.tap(80f, 20f)); assertEquals(Screen.CONNECTIONS, m.screen)
        m.goHome(); assertEquals(Action.BtLoad, m.tap(190f, 20f)); assertEquals(Screen.BT, m.screen)
    }

    private fun musicModel(): LauncherModel = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music"))).also { it.radioNames = listOf("BBC Radio 1", "Heart", "Classic FM"); it.spSignedIn = true }

    @Test fun theMusicMenuListsProvidersThenTheCommonSettings() {
        val m = musicModel()
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MUSICMENU, m.screen)
        assertEquals(listOf("Radio", "Spotify", "Sleep timer: off", "Bar shows: progress"), m.rows)
        m.provStatus = mapOf(Provider.RADIO to 2, Provider.SPOTIFY to 1); assertEquals(listOf(2, 1, 0, 0), m.rowMarks) // a symbol per provider: playing, paused
        m.radioMode = "off"; assertEquals(listOf("Spotify", "Sleep timer: off", "Bar shows: progress"), m.rows) // radio off in this mode: no Radio row
        assertEquals(Action.SleepStep, m.onKey(Key.DIGIT, 2)); assertEquals(Action.SpectrumToggle, m.onKey(Key.DIGIT, 3))
    }

    @Test fun aProviderScreenIsTheProvidersAppAndItsMenuHoldsItsFunctions() {
        val m = musicModel()
        m.onKey(Key.SOFT_RIGHT) // Music menu
        assertEquals(Action.SpLoad("playlists"), m.onKey(Key.DIGIT, 2)); assertEquals(Screen.PLAYLISTS, m.screen) // Spotify, nothing playing: straight to the playlists
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.SPOTIFYMENU, m.screen); m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MUSIC, m.screen)
        assertEquals(Provider.SPOTIFY, m.provider); assertEquals("Spotify", m.title); assertEquals("Menu" to "Back", m.softKeys)
        assertEquals(Action.SpLoad("playlists"), m.onKey(Key.OK)); assertEquals(Screen.PLAYLISTS, m.screen) // nothing playing: OK picks a playlist
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MUSIC, m.screen)
        m.music = io.github.durkimat.phoneguard.music.MusicInfo(true, "Spotify", "Song", "Band", "Album", true, 30_000, 200_000, 40)
        assertEquals(Action.MusicPlayPause, m.onKey(Key.OK)); assertEquals(Action.MusicPlayPause, m.onKey(Key.DIGIT, 5))
        assertEquals(Action.MusicNext, m.onKey(Key.RIGHT)); assertEquals(Action.MusicNext, m.onKey(Key.DIGIT, 6))
        assertEquals(Action.MusicPrevious, m.onKey(Key.LEFT)); assertEquals(Action.MusicPrevious, m.onKey(Key.DIGIT, 4))
        assertEquals(Action.MusicVolume(true), m.onKey(Key.UP)); assertEquals(Action.MusicVolume(false), m.onKey(Key.DIGIT, 8))
        assertEquals(Action.SleepStep, m.onKey(Key.DIGIT, 0)); assertEquals(Action.SpectrumToggle, m.onKey(Key.DIGIT, 7))
        assertEquals(Action.SpShuffle, m.onKey(Key.STAR)); assertEquals(Action.SpRepeat, m.onKey(Key.DIGIT, 1))
        // the left key opens the menu: Spotify's own functions and Change source (sleep and the bar are in the Music menu only)
        m.onKey(Key.SOFT_LEFT); assertEquals(Screen.SPOTIFYMENU, m.screen)
        assertEquals(listOf("Playlists", "Search", "Play on...", "Change source", "Sign out of Spotify"), m.rows)
        m.onKey(Key.DIGIT, 4); assertEquals(Screen.MUSICMENU, m.screen) // Change source opens the Music menu
        assertEquals(Action.SpLoad("playlists"), m.onKey(Key.DIGIT, 2)); m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.SPOTIFYMENU, m.screen)
        assertEquals(Action.SpLoad("playlists"), m.onKey(Key.DIGIT, 1)); assertEquals(Screen.PLAYLISTS, m.screen)
        m.spPlaylists = listOf(io.github.durkimat.phoneguard.music.SpItem("spotify:playlist:1", "Chill", "10 tracks"))
        assertEquals(Action.SpPlay("spotify:playlist:1"), m.onKey(Key.OK)); assertEquals(Screen.MUSIC, m.screen)
        m.onKey(Key.SOFT_LEFT); assertEquals(Action.SpLoad("devices"), m.onKey(Key.DIGIT, 3)); assertEquals(Screen.DEVICES, m.screen)
        m.spDevices = listOf(io.github.durkimat.phoneguard.music.SpDevice("d1", "Lounge", "Speaker", false, 30), io.github.durkimat.phoneguard.music.SpDevice("d2", "Phone", "Smartphone", true, 50))
        m.spIsThisPhone = { it.id == "d2" }
        assertEquals(listOf("  Lounge", "> Phone (this phone)"), m.rows); assertEquals(Action.SpTransfer("d1", "Lounge"), m.onKey(Key.OK)); assertEquals(Screen.MUSIC, m.screen)
        m.onKey(Key.SOFT_LEFT); m.onKey(Key.DIGIT, 2) // Search: the text entry, not a message
        assertEquals(Screen.WRITE, m.screen); assertTrue(m.writeSearch)
        m.onKey(Key.DIGIT, 2); m.onKey(Key.DIGIT, 2); val a = m.onKey(Key.OK); assertTrue(a is Action.SpSearch); assertEquals(Screen.SEARCHRES, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.SPOTIFYMENU, m.screen) // Back from the lists returns to the menu, and from the menu to the screen
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen) // Now playing is a destination: Back is always home
    }

    @Test fun theHashKeyOnNowPlayingChangesSourceAndBackIsAlwaysHome() {
        val m = musicModel(); m.provStatus = mapOf(Provider.RADIO to 2, Provider.SPOTIFY to 0)
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 1); assertEquals(Screen.MUSIC, m.screen); assertEquals(Provider.RADIO, m.provider) // playing: Now playing
        m.onKey(Key.POUND); assertEquals(Screen.MUSICMENU, m.screen) // # picks a source
        m.provStatus = mapOf(Provider.RADIO to 2, Provider.SPOTIFY to 1); m.onKey(Key.DIGIT, 2); assertEquals(Screen.MUSIC, m.screen); assertEquals(Provider.SPOTIFY, m.provider) // paused: Now playing
        m.onKey(Key.POUND); assertEquals(Screen.MUSICMENU, m.screen) // the same on Spotify
        m.onKey(Key.DIGIT, 1); assertEquals(Provider.RADIO, m.provider); m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen)
    }

    @Test fun spotifyNotSignedInOffersSigningIn() {
        val m = musicModel(); m.spSignedIn = false; m.spConfigured = true
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 2); m.onKey(Key.SOFT_LEFT)
        assertEquals(listOf("Sign in to Spotify", "Change source"), m.rows); assertEquals(Action.SpSignIn, m.onKey(Key.DIGIT, 1))
        m.spConfigured = false; assertEquals("Spotify: set up in HA", m.rows[0])
    }

    @Test fun theRadioScreenHasPresetKeysAndAMenuForFindAndBrowse() {
        val m = musicModel()
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 1) // Music menu, Radio
        assertEquals(Screen.RADIO, m.screen); assertEquals(listOf("BBC Radio 1", "Heart", "Classic FM"), m.rows) // nothing playing: straight to the presets
        assertEquals(Action.RadioPlay(1), m.onKey(Key.DIGIT, 2)); assertEquals(Screen.MUSIC, m.screen); assertEquals(Provider.RADIO, m.provider); assertEquals("Radio", m.title)
        m.music = io.github.durkimat.phoneguard.music.MusicInfo(true, "Radio", "Heart", "", "", true, 5_000, 0, 40, source = "radio")
        assertEquals(Action.RadioPlay(2), m.onKey(Key.DIGIT, 3)); assertEquals(Action.None, m.onKey(Key.DIGIT, 9)) // the digits are presets
        assertEquals(Action.MusicNext, m.onKey(Key.RIGHT)); assertEquals(Action.MusicPrevious, m.onKey(Key.LEFT)); assertEquals(Action.MusicPlayPause, m.onKey(Key.OK))
        assertEquals(Action.MusicVolume(true), m.onKey(Key.UP)); assertEquals(Action.SleepStep, m.onKey(Key.DIGIT, 0)); assertEquals(Action.SpectrumToggle, m.onKey(Key.STAR))
        m.onKey(Key.SOFT_LEFT); assertEquals(Screen.RADIOMENU, m.screen)
        assertEquals(listOf("Presets", "Find station", "Browse stations", "Change source"), m.rows)
        // Browse: popular, genres, countries
        m.onKey(Key.DIGIT, 3); assertEquals(Screen.RADIOBROWSE, m.screen); assertEquals(listOf("Popular in the UK", "Genres", "Countries"), m.rows)
        assertEquals(Action.RadioLoad("top", "GB"), m.onKey(Key.OK)); assertEquals(Screen.RADIORES, m.screen); assertEquals(listOf("Loading..."), m.rows)
        m.radioRes = listOf(io.github.durkimat.phoneguard.music.RadioItem("Jazz FM", "MP3 128k GB", "https://j/s"), io.github.durkimat.phoneguard.music.RadioItem("Smooth", "AAC 48k GB", "https://s/s"))
        assertEquals(Action.RadioFav("Jazz FM", "https://j/s"), m.onKey(Key.STAR))
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.RADIOBROWSE, m.screen); m.onKey(Key.DOWN); m.onKey(Key.OK); assertTrue("Pop" in m.rows)
        assertEquals(Action.RadioLoad("tag", "pop"), m.onKey(Key.OK)); m.radioRes = listOf(io.github.durkimat.phoneguard.music.RadioItem("Pop FM", "", "https://p/s"))
        assertEquals(Action.RadioPlayUrl("Pop FM", "https://p/s"), m.onKey(Key.OK)); assertEquals(Screen.MUSIC, m.screen)
        // Find: type a name
        m.onKey(Key.SOFT_LEFT); m.onKey(Key.DIGIT, 2); assertEquals(Screen.WRITE, m.screen); assertTrue(m.writeSearch)
        m.onKey(Key.DIGIT, 4); m.onKey(Key.DIGIT, 3); assertTrue(m.onKey(Key.OK) is Action.RadioSearch); assertEquals(Screen.RADIORES, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.RADIOMENU, m.screen) // Back from a search returns to the Radio menu
    }

    @Test fun radioModeLimitsWhatTheRadioMenuOffers() {
        val m = musicModel(); m.radioMode = "presets"
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 1); m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.RADIOMENU, m.screen) // idle Radio opens the presets; the right key is their menu
        assertEquals(listOf("Presets", "Change source"), m.rows) // the parent's list only: no Find or Browse
    }

    @Test fun homeMusicKeyGoesStraightToTheProviderThatIsPlayingAndBackReturnsHome() {
        val m = musicModel()
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MUSICMENU, m.screen) // nothing playing: the Music menu
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen)
        m.badges = listOf(Badge(BadgeKind.MUSIC, 1)); m.playingProvider = Provider.RADIO
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MUSIC, m.screen); assertEquals(Provider.RADIO, m.provider) // playing: straight into that provider's screen
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen) // and Back straight out
        m.badges = emptyList(); m.provStatus = mapOf(Provider.SPOTIFY to 1); m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 2); assertEquals(Screen.MUSIC, m.screen); assertEquals(Provider.SPOTIFY, m.provider)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen) // even through the Music menu, Back from Now playing is home
    }
}
