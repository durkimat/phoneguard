package io.github.durkimat.phoneguard.launcher

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnakeTest {
    private fun game() = Snake(10, 10, Random(1))

    @Test fun waitsToStartThenMovesOneCellEachStep() {
        val g = game()
        g.step(); assertEquals(3, g.body.size); val head = g.body.first()
        g.toggle(); g.step()
        assertEquals(head.first + 1 to head.second, g.body.first())
    }

    @Test fun cannotTurnStraightBack() {
        val g = game(); g.toggle(); g.steer(3); g.step()
        assertEquals(Snake.State.RUNNING, g.state); assertEquals(6, g.body.first().first)
    }

    @Test fun hittingTheWallEndsTheGameAndAgainStartsFresh() {
        val g = game(); g.toggle()
        repeat(10) { g.step() }
        assertEquals(Snake.State.OVER, g.state)
        g.toggle()
        assertEquals(Snake.State.RUNNING, g.state); assertEquals(3, g.body.size); assertEquals(0, g.score)
    }

    @Test fun eatingGrowsScoresAndMovesTheFood() {
        val g = game(); g.toggle()
        // walk until the food is eaten by steering at it
        var guard = 0
        while (g.score == 0 && g.state == Snake.State.RUNNING && guard++ < 200) {
            val h = g.body.first(); val f = g.food
            if (f.second < h.second) g.steer(0) else if (f.second > h.second) g.steer(2) else if (f.first > h.first) g.steer(1) else g.steer(3)
            g.step()
        }
        assertEquals(1, g.score); assertEquals(4, g.body.size); assertTrue(g.food !in g.body)
    }

    @Test fun pauseStopsTheSteps() {
        val g = game(); g.toggle(); g.toggle()
        val b = g.body; g.step()
        assertEquals(Snake.State.PAUSED, g.state); assertEquals(b, g.body)
    }

    @Test fun gamesMenuOpensSnakeWhichPlaysAndBackLeaves() {
        val m = LauncherModel(listOf(MenuItem(GAMES_PKG, "Games")))
        m.onKey(Key.OK); m.onKey(Key.DIGIT, 1)
        assertEquals(Screen.GAMES, m.screen)
        m.onKey(Key.OK); assertEquals(Screen.GAMEHELP, m.screen); m.onKey(Key.OK); assertEquals(Screen.SNAKE, m.screen)
        m.onKey(Key.OK); assertEquals(Action.Redraw, m.gameTick())
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.GAMES, m.screen)
        assertEquals(Action.None, m.gameTick())
    }
}
