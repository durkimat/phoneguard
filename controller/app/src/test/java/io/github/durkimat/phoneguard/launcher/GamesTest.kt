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
}
