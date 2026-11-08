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

    @Test fun shotKillsAnEnemyInItsRowAndEnemiesCostALife() {
        val g = Shooter(rnd = Random(1)); g.fire(); g.fire()
        g.enemies.add(g.shipX + 5 to g.shipY)
        repeat(4) { g.tick() }
        assertEquals(1, g.score); assertTrue(g.enemies.none { it.second == g.shipY && it.first <= g.shipX + 5 })
        g.enemies.add(g.shipX to g.shipY); g.tick()
        assertEquals(2, g.lives)
        g.enemies.add(g.shipX to g.shipY); g.tick()
        assertEquals(2, g.lives) // blinking: cannot be hit again straight away
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
            m.onKey(Key.DIGIT, n); assertEquals(s, m.screen)
            m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.GAMES, m.screen); assertEquals(n - 1, m.selected)
        }
    }
}
