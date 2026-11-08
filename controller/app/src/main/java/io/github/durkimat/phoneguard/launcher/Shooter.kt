package io.github.durkimat.phoneguard.launcher

import java.util.Random
import kotlin.math.sin

/**
 * A side-scrolling blaster in the spirit of the classic arcade shoot-em-ups. The ship flies in four directions; a tap fires,
 * holding the fire key charges a piercing beam, and a pod picked up from a drifting crate clings to the front of the ship,
 * blocking shots and smashing small enemies. Sizes and positions are in small cells ([cols] by [rows]) and the skin scales them.
 */
class Shooter(val cols: Int = 44, val rows: Int = 40, private val rnd: Random = Random()) {
    enum class State { READY, RUNNING, PAUSED, OVER }

    class Enemy(var x: Int, var y: Int, val w: Int, val h: Int, var hp: Int, val tank: Boolean, val baseY: Int) { var age = 0 }

    val shipW = 6
    val shipH = 4
    var shipX = 4
        private set
    var shipY = rows / 2
        private set
    var state = State.READY
        private set
    var lives = 3
        private set
    var score = 0
        private set
    var hasPod = false
        private set
    /** How long the fire key has been held, in ticks; 0 when not held. */
    var charge = 0
        private set
    /** Ticks the piercing beam still has to run. */
    var beam = 0
        private set
    var invulnerable = 0
        private set

    val shots = ArrayList<IntArray>()      // x, y
    val enemyShots = ArrayList<IntArray>() // x, y
    val enemies = ArrayList<Enemy>()
    val crates = ArrayList<IntArray>()     // x, y
    val stars = ArrayList<IntArray>()      // x, y, speed (ticks per cell)

    private var up = false; private var down = false; private var left = false; private var right = false
    private var fireHeld = false
    private var ticks = 0
    private var nextWave = 30
    private var wave = 0

    /** Milliseconds per tick. */
    val tickMs: Long = 60
    val chargeNeeded = 14

    init { reset() }

    fun reset() {
        state = State.READY; shipX = 4; shipY = rows / 2; lives = 3; score = 0; hasPod = false; charge = 0; beam = 0; invulnerable = 0
        shots.clear(); enemyShots.clear(); enemies.clear(); crates.clear(); stars.clear()
        ticks = 0; nextWave = 30; wave = 0; releaseAll()
        repeat(24) { stars.add(intArrayOf(rnd.nextInt(cols), rnd.nextInt(rows), 1 + rnd.nextInt(3))) }
    }

    fun toggle() {
        releaseAll()
        state = when (state) {
            State.READY -> State.RUNNING
            State.RUNNING -> State.PAUSED
            State.PAUSED -> State.RUNNING
            State.OVER -> { reset(); State.RUNNING }
        }
    }

    private fun releaseAll() { up = false; down = false; left = false; right = false; fireHeld = false; charge = 0 }

    /** dir: 0 up, 1 right, 2 down, 3 left. Held until released. */
    fun hold(dir: Int, on: Boolean) { when (dir) { 0 -> up = on; 1 -> right = on; 2 -> down = on; 3 -> left = on } }

    /** The fire key goes down: any key starts the game from the ready screen. */
    fun fireDown() {
        when (state) {
            State.READY, State.PAUSED, State.OVER -> toggle()
            State.RUNNING -> { fireHeld = true; charge = 0; fireShot() }
        }
    }

    /** The fire key comes up: a long enough hold lets the beam go. */
    fun fireUp() {
        if (state == State.RUNNING && fireHeld && charge >= chargeNeeded) beam = 6
        fireHeld = false; charge = 0
    }

    private fun fireShot() {
        if (shots.size >= (if (hasPod) 8 else 4)) return
        shots.add(intArrayOf(shipX + shipW, shipY + 1))
        if (hasPod) shots.add(intArrayOf(shipX + shipW + 3, shipY + 2))
    }

    private fun hit(ax: Int, ay: Int, aw: Int, ah: Int, bx: Int, by: Int, bw: Int, bh: Int) = ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah

    private fun kill(e: Enemy) { enemies.remove(e); score += if (e.tank) 50 else 10 }
    private fun damage(e: Enemy, n: Int) { e.hp -= n; if (e.hp <= 0) kill(e) }

    private val podX get() = shipX + shipW
    private val podY get() = shipY

    private fun loseLife() {
        if (invulnerable > 0) return
        lives--; invulnerable = 25; hasPod = false
        if (lives <= 0) state = State.OVER
    }

    fun tick() {
        if (state != State.RUNNING) return
        ticks++
        if (invulnerable > 0) invulnerable--
        if (up) shipY--; if (down) shipY++; if (left) shipX--; if (right) shipX++
        shipY = shipY.coerceIn(0, rows - shipH); shipX = shipX.coerceIn(0, cols / 2)
        if (fireHeld) charge = (charge + 1).coerceAtMost(40)

        for (s in stars) if (ticks % s[2] == 0) { s[0]--; if (s[0] < 0) { s[0] = cols - 1; s[1] = rnd.nextInt(rows) } }

        // our shots
        val liveShots = ArrayList<IntArray>()
        for (s in shots) {
            var spent = false
            for (step in 1..3) {
                s[0]++
                val e = enemies.firstOrNull { hit(s[0], s[1], 1, 1, it.x, it.y, it.w, it.h) }
                if (e != null) { damage(e, 1); spent = true; break }
            }
            if (!spent && s[0] < cols) liveShots.add(s)
        }
        shots.clear(); shots.addAll(liveShots)

        // the beam goes right through everything in its lane
        if (beam > 0) {
            beam--
            for (e in enemies.toList()) if (hit(shipX + shipW, shipY, cols, shipH, e.x, e.y, e.w, e.h)) damage(e, 4)
            enemyShots.removeAll { hit(shipX + shipW, shipY, cols, shipH, it[0], it[1], 1, 1) }
        }

        // enemies
        for (e in enemies.toList()) {
            e.age++
            if (e.age % (if (e.tank) 4 else 2) == 0) e.x--
            if (!e.tank) e.y = (e.baseY + Math.round(6 * sin(e.age * 0.22))).toInt().coerceIn(0, rows - e.h)
            if (e.tank && e.age % 26 == 0 && e.x < cols - 4) enemyShots.add(intArrayOf(e.x - 1, e.y + e.h / 2))
            if (e.x + e.w < 0) { enemies.remove(e); continue }
            if (hasPod && hit(podX, podY, 3, shipH, e.x, e.y, e.w, e.h)) { damage(e, 1); continue }
            if (hit(shipX, shipY, shipW, shipH, e.x, e.y, e.w, e.h)) { if (invulnerable == 0) { enemies.remove(e); loseLife() } }
        }

        // enemy shots fly left two cells a tick; the pod catches them
        val liveEnemy = ArrayList<IntArray>()
        for (s in enemyShots) {
            s[0] -= 2
            if (hasPod && hit(s[0], s[1], 2, 1, podX, podY, 3, shipH)) continue
            if (hit(s[0], s[1], 2, 1, shipX, shipY, shipW, shipH)) { loseLife(); continue }
            if (s[0] > -2) liveEnemy.add(s)
        }
        enemyShots.clear(); enemyShots.addAll(liveEnemy)

        // crates drift in; flying into one grabs the pod
        for (cr in crates.toList()) {
            if (ticks % 3 == 0) cr[0]--
            if (hit(shipX, shipY, shipW + (if (hasPod) 3 else 0), shipH, cr[0], cr[1], 3, 3)) { crates.remove(cr); if (hasPod) score += 25 else hasPod = true }
            else if (cr[0] < -3) crates.remove(cr)
        }

        if (state == State.RUNNING && ticks >= nextWave) spawnWave()
    }

    private fun spawnWave() {
        wave++
        val gap = (46 - wave * 2).coerceAtLeast(22)
        nextWave = ticks + gap
        val y = 2 + rnd.nextInt(rows - 10)
        when {
            wave % 4 == 0 -> enemies.add(Enemy(cols, rows / 2 - 3 + rnd.nextInt(9) - 4, 7, 6, 8 + wave / 4, true, 0))
            wave % 5 == 2 -> { crates.add(intArrayOf(cols, 4 + rnd.nextInt(rows - 12))); for (i in 0 until 3) enemies.add(Enemy(cols + i * 9, y, 4, 3, 1, false, y)) }
            else -> for (i in 0 until 4) enemies.add(Enemy(cols + i * 9, y, 4, 3, 1, false, y))
        }
    }

    /** For tests: place an enemy exactly. */
    internal fun addEnemy(e: Enemy) { enemies.add(e) }
    internal fun giveCrate(x: Int, y: Int) { crates.add(intArrayOf(x, y)) }
}
