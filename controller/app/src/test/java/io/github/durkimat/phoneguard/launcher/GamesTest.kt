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
        assertEquals(listOf("Theme", "Font", "Predictive text", "Speakerphone", "Android settings", "Battery"), m.rows)
        assertEquals(Action.Launch("com.android.settings"), m.onKey(Key.DIGIT, 5))
        assertEquals(Screen.BATTERY, run { m.onKey(Key.DIGIT, 6); m.screen })
        val school = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings"))); school.onKey(Key.OK); school.onKey(Key.DIGIT, 1)
        assertTrue("Android settings" !in school.rows)
    }

    @Test fun musicScreenIsARemoteForTheMusicApp() {
        val m = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music")))
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1)
        assertEquals(Screen.MUSIC, m.screen)
        assertEquals(Action.None, m.onKey(Key.OK)) // nothing playing, no music app: nothing to do
        m.musicApp = true; assertEquals(Action.MusicOpenApp, m.onKey(Key.OK))
        m.music = io.github.durkimat.phoneguard.music.MusicInfo(true, "Spotify", "Song", "Band", "Album", true, 30_000, 200_000, 40)
        assertEquals("Pause", m.softKeys.first)
        assertEquals(Action.MusicPlayPause, m.onKey(Key.OK)); assertEquals(Action.MusicPlayPause, m.onKey(Key.DIGIT, 5)); assertEquals(Action.MusicPlayPause, m.onKey(Key.SOFT_LEFT))
        assertEquals(Action.MusicNext, m.onKey(Key.RIGHT)); assertEquals(Action.MusicNext, m.onKey(Key.DIGIT, 6))
        assertEquals(Action.MusicPrevious, m.onKey(Key.LEFT)); assertEquals(Action.MusicPrevious, m.onKey(Key.DIGIT, 4))
        assertEquals(Action.MusicVolume(true), m.onKey(Key.UP)); assertEquals(Action.MusicVolume(false), m.onKey(Key.DIGIT, 8))
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MENU, m.screen)
        assertEquals("3:20", io.github.durkimat.phoneguard.music.MusicInfo.clock(200_000)); assertEquals("1:02:03", io.github.durkimat.phoneguard.music.MusicInfo.clock(3_723_000))
    }
}
