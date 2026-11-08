package io.github.durkimat.phoneguard.launcher

import java.util.Random

/** The classic snake game on a small grid: eat to grow, hitting a wall or yourself ends it. No drawing here, so it can be tested. */
class Snake(val cols: Int = 22, val rows: Int = 20, private val rnd: Random = Random()) {
    enum class State { READY, RUNNING, PAUSED, OVER }

    /** 0 up, 1 right, 2 down, 3 left. */
    private val dx = intArrayOf(0, 1, 0, -1)
    private val dy = intArrayOf(-1, 0, 1, 0)

    var state = State.READY
        private set
    /** The snake's cells as (x, y), head first. */
    var body: List<Pair<Int, Int>> = emptyList()
        private set
    var food: Pair<Int, Int> = 0 to 0
        private set
    var score = 0
        private set
    private var dir = 1
    private var queued = 1

    init { reset() }

    fun reset() {
        val y = rows / 2
        body = listOf(5 to y, 4 to y, 3 to y)
        dir = 1; queued = 1; score = 0
        state = State.READY
        placeFood()
    }

    /** Turn towards a direction; turning straight back on yourself is ignored. */
    fun steer(d: Int) {
        if (d !in 0..3 || (d + 2) % 4 == dir) return
        queued = d
        if (state == State.READY) state = State.RUNNING
    }

    /** OK or the left key: start, pause, resume or play again. */
    fun toggle() {
        state = when (state) {
            State.READY -> State.RUNNING
            State.RUNNING -> State.PAUSED
            State.PAUSED -> State.RUNNING
            State.OVER -> { reset(); State.RUNNING }
        }
    }

    /** Faster as the score grows, but never faster than 70 ms a step. */
    val stepMs: Long get() = (220 - score * 6).coerceAtLeast(70).toLong()

    fun step() {
        if (state != State.RUNNING) return
        dir = queued
        val h = body.first()
        val n = (h.first + dx[dir]) to (h.second + dy[dir])
        val eats = n == food
        // the tail moves away this step unless we are eating, so it is not a collision
        val solid = if (eats) body else body.dropLast(1)
        if (n.first !in 0 until cols || n.second !in 0 until rows || n in solid) { state = State.OVER; return }
        body = listOf(n) + (if (eats) body else body.dropLast(1))
        if (eats) { score++; if (body.size >= cols * rows) state = State.OVER else placeFood() }
    }

    private fun placeFood() {
        val free = ArrayList<Pair<Int, Int>>()
        for (x in 0 until cols) for (y in 0 until rows) if (x to y !in body) free.add(x to y)
        if (free.isNotEmpty()) food = free[rnd.nextInt(free.size)]
    }
}
