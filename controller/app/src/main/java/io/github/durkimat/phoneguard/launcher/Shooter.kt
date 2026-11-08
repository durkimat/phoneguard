package io.github.durkimat.phoneguard.launcher

import java.util.Random

/** A side-scrolling shooter: the ship moves up and down on the left, shots go right, enemies come from the right. */
class Shooter(val cols: Int = 22, val rows: Int = 14, private val rnd: Random = Random()) {
    enum class State { READY, RUNNING, PAUSED, OVER }

    var state = State.READY
        private set
    var shipY = rows / 2
        private set
    var lives = 3
        private set
    var score = 0
        private set
    /** Cells as (x, y). */
    val bullets = ArrayList<Pair<Int, Int>>()
    val enemies = ArrayList<Pair<Int, Int>>()
    private var ticks = 0
    val shipX = 1
    /** The ship flashes for a few ticks after being hit, and cannot be hit again while it does. */
    var invulnerable = 0
        private set

    fun reset() { state = State.READY; shipY = rows / 2; lives = 3; score = 0; bullets.clear(); enemies.clear(); ticks = 0; invulnerable = 0 }

    fun toggle() {
        state = when (state) {
            State.READY -> State.RUNNING
            State.RUNNING -> State.PAUSED
            State.PAUSED -> State.RUNNING
            State.OVER -> { reset(); State.RUNNING }
        }
    }

    fun move(dy: Int) { if (state == State.RUNNING) shipY = (shipY + dy).coerceIn(0, rows - 1) }

    fun fire() {
        if (state == State.READY) state = State.RUNNING
        else if (state == State.RUNNING && bullets.size < 3) bullets.add(shipX + 1 to shipY)
    }

    /** Milliseconds per tick. */
    val tickMs: Long = 90

    fun tick() {
        if (state != State.RUNNING) return
        ticks++
        if (invulnerable > 0) invulnerable--
        // shots fly two cells a tick, checking each cell so they cannot skip over an enemy
        val live = ArrayList<Pair<Int, Int>>()
        for (b in bullets) {
            var hit = false
            for (nx in b.first + 1..b.first + 2) if (enemies.remove(nx to b.second)) { score++; hit = true; break }
            if (!hit && b.first + 2 < cols) live.add(b.first + 2 to b.second)
        }
        bullets.clear(); bullets.addAll(live)
        // enemies drift left every other tick, a little faster as the score grows
        val every = if (score >= 20) 1 else 2
        if (ticks % every == 0) {
            val moved = enemies.map { it.first - 1 to it.second }
            enemies.clear(); enemies.addAll(moved.filter { it.first >= 0 })
        }
        // a shot that now overlaps an enemy it just met also counts
        for (b in bullets.toList()) if (enemies.remove(b)) { bullets.remove(b); score++ }
        val spawnEvery = (12 - score / 5).coerceAtLeast(5)
        if (ticks % spawnEvery == 0) { val y = rnd.nextInt(rows); if ((cols - 1 to y) !in enemies) enemies.add(cols - 1 to y) }
        if (invulnerable == 0 && enemies.remove(shipX to shipY)) {
            lives--
            invulnerable = 12
            if (lives <= 0) state = State.OVER
        }
    }
}
