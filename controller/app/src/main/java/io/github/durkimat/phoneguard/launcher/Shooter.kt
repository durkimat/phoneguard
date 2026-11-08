package io.github.durkimat.phoneguard.launcher

import java.util.Random
import kotlin.math.sin

/**
 * A side-scrolling blaster in the spirit of the classic arcade shoot-em-ups. The ship flies in four directions; the gun fires by itself,
 * holding the fire key charges a piercing beam, and a pod picked up from a drifting crate clings to the front of the ship,
 * blocking shots and smashing small enemies. The pod can be launched ahead of the ship and called back. Crates also bring
 * a spread or a bouncing weapon and more speed. The ordinary gun fires by itself. Each stage ends in a boss. Positions are in small cells ([cols] by [rows]);
 * the skin scales them.
 */
class Shooter(val cols: Int = 44, val rows: Int = 40, private val rnd: Random = Random()) {
    enum class State { READY, RUNNING, PAUSED, OVER }
    enum class Weapon(val label: String) { NORMAL("Blaster"), SPREAD("Spread"), BOUNCE("Bounce") }
    enum class PodMode { OFF, ATTACHED, LAUNCHED, RECALL }

    /** kind 0 is a weaving drone, 1 a tank that shoots, 2 the stage boss. */
    class Enemy(var x: Int, var y: Int, val w: Int, val h: Int, var hp: Int, val kind: Int, val baseY: Int) {
        var age = 0
        val tank get() = kind == 1
    }

    val shipW = 6
    val shipH = 4
    val podW = 3
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
    var stage = 1
        private set
    var weapon = Weapon.NORMAL
        private set
    var speed = 0
        private set
    var podMode = PodMode.OFF
        private set
    var podX = 0
        private set
    var podY = 0
        private set
    val hasPod: Boolean get() = podMode != PodMode.OFF
    /** How long the fire key has been held, in ticks; 0 when not held. */
    var charge = 0
        private set
    /** Ticks the piercing beam still has to run. */
    var beam = 0
        private set
    var invulnerable = 0
        private set
    /** Ticks of "stage clear" still to show. */
    var banner = 0
        private set

    val shots = ArrayList<IntArray>()      // x, y, dy, bounces left
    val enemyShots = ArrayList<IntArray>() // x, y, dy
    val enemies = ArrayList<Enemy>()
    val crates = ArrayList<IntArray>()     // x, y, type (0 pod, 1 spread, 2 bounce, 3 speed)
    val stars = ArrayList<IntArray>()      // x, y, ticks per cell

    private var up = false; private var down = false; private var left = false; private var right = false
    private var fireHeld = false
    private var ticks = 0
    private var nextWave = 30
    private var waveInStage = 0
    private var wave = 0
    private var bossOut = false

    /** Milliseconds per tick. */
    val tickMs: Long = 60
    val chargeNeeded = 14
    val wavesPerStage = 8

    init { reset() }

    fun reset() {
        state = State.READY; shipX = 4; shipY = rows / 2; lives = 3; score = 0; stage = 1; weapon = Weapon.NORMAL; speed = 0
        podMode = PodMode.OFF; charge = 0; beam = 0; invulnerable = 0; banner = 0
        shots.clear(); enemyShots.clear(); enemies.clear(); crates.clear(); stars.clear()
        ticks = 0; nextWave = 30; waveInStage = 0; wave = 0; bossOut = false; releaseAll()
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
            State.RUNNING -> { fireHeld = true; charge = 0 }
        }
    }

    /** The fire key comes up: a long enough hold lets the beam go. */
    fun fireUp() {
        if (state == State.RUNNING && fireHeld) { if (charge >= chargeNeeded) beam = 6 else fireShot() }
        fireHeld = false; charge = 0
    }

    /** Send the pod ahead of the ship, or call it back. */
    fun podToggle() {
        if (state != State.RUNNING) return
        when (podMode) {
            PodMode.ATTACHED -> { podMode = PodMode.LAUNCHED; podX = shipX + shipW; podY = shipY }
            PodMode.LAUNCHED -> podMode = PodMode.RECALL
            else -> {}
        }
    }

    private fun addShot(x: Int, y: Int, dy: Int, bounces: Int = 0) { shots.add(intArrayOf(x, y, dy, bounces)) }

    private fun fireShot() {
        if (shots.size >= 9) return
        val x = shipX + shipW; val y = shipY + 1
        when (weapon) {
            Weapon.NORMAL -> addShot(x, y, 0)
            Weapon.SPREAD -> { addShot(x, y, 0); addShot(x, y, -1); addShot(x, y, 1) }
            Weapon.BOUNCE -> { addShot(x, y, 0); addShot(x, y, -1, 3); addShot(x, y, 1, 3) }
        }
        if (podMode == PodMode.ATTACHED) addShot(x + podW, shipY + 2, 0)
    }

    private fun hit(ax: Int, ay: Int, aw: Int, ah: Int, bx: Int, by: Int, bw: Int, bh: Int) = ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah

    private fun kill(e: Enemy) {
        enemies.remove(e)
        score += when (e.kind) { 2 -> 500; 1 -> 50; else -> 10 }
        if (e.kind == 2) { bossOut = false; stage++; waveInStage = 0; banner = 50; nextWave = ticks + 60; enemyShots.clear() }
    }
    private fun damage(e: Enemy, n: Int) { e.hp -= n; if (e.hp <= 0) kill(e) }

    private fun loseLife() {
        if (invulnerable > 0) return
        lives--; invulnerable = 25; weapon = Weapon.NORMAL
        if (lives <= 0) state = State.OVER
    }

    /** The cell the attached pod holds, in front of the ship's nose. */
    private fun slotX() = shipX + shipW
    private fun slotY() = shipY

    fun tick() {
        if (state != State.RUNNING) return
        ticks++
        if (invulnerable > 0) invulnerable--
        if (banner > 0) banner--
        val pace = 1 + speed
        if (up) shipY -= pace; if (down) shipY += pace; if (left) shipX -= pace; if (right) shipX += pace
        shipY = shipY.coerceIn(0, rows - shipH); shipX = shipX.coerceIn(0, cols / 2)
        if (fireHeld) charge = (charge + 1).coerceAtMost(40)
        // the gun fires by itself, so the keys are free for flying: the fire key only charges the beam (a quick tap adds a shot)
        if (ticks % 6 == 0) fireShot()

        // the pod: stuck to the nose, flying ahead on its own, or coming home
        when (podMode) {
            PodMode.ATTACHED -> { podX = slotX(); podY = slotY() }
            PodMode.LAUNCHED -> {
                if (podX < cols - 12) podX += 2
                if (ticks % 12 == 0 && shots.size < 9) addShot(podX + podW, podY + 2, 0)
            }
            PodMode.RECALL -> {
                podX += (slotX() - podX).coerceIn(-3, 3); podY += (slotY() - podY).coerceIn(-3, 3)
                if (Math.abs(podX - slotX()) <= 1 && Math.abs(podY - slotY()) <= 1) podMode = PodMode.ATTACHED
            }
            PodMode.OFF -> {}
        }

        for (s in stars) if (ticks % s[2] == 0) { s[0]--; if (s[0] < 0) { s[0] = cols - 1; s[1] = rnd.nextInt(rows) } }

        // our shots, three cells a tick, checked cell by cell so they cannot skip over an enemy
        val liveShots = ArrayList<IntArray>()
        for (s in shots) {
            var spent = false
            for (step in 1..3) {
                s[0]++; s[1] += s[2]
                if (s[1] < 0 || s[1] >= rows) {
                    if (s[3] > 0) { s[3]--; s[2] = -s[2]; s[1] = s[1].coerceIn(0, rows - 1) } else { spent = true; break }
                }
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
            when (e.kind) {
                2 -> {
                    if (e.x > cols - e.w - 2 && e.age % 3 == 0) e.x--
                    e.y = (rows / 2 - e.h / 2 + Math.round((rows - e.h) / 2.2 * sin(e.age * 0.07))).toInt().coerceIn(0, rows - e.h)
                    if (e.age % 22 == 0 && e.x <= cols - e.w) {
                        val aim = if (shipY + 2 > e.y + e.h / 2) 1 else -1
                        enemyShots.add(intArrayOf(e.x - 1, e.y + e.h / 2, aim)); enemyShots.add(intArrayOf(e.x - 1, e.y + e.h / 2, 0))
                        if (e.hp < 20) enemyShots.add(intArrayOf(e.x - 1, e.y + e.h / 2, -aim))
                    }
                }
                1 -> {
                    if (e.age % 4 == 0) e.x--
                    if (e.age % 26 == 0 && e.x < cols - 4) enemyShots.add(intArrayOf(e.x - 1, e.y + e.h / 2, 0))
                }
                else -> {
                    if (e.age % 2 == 0) e.x--
                    e.y = (e.baseY + Math.round(6 * sin(e.age * 0.22))).toInt().coerceIn(0, rows - e.h)
                }
            }
            if (e.x + e.w < 0) { enemies.remove(e); continue }
            if (hasPod && ticks % 3 == 0 && hit(podX, podY, podW, shipH, e.x, e.y, e.w, e.h)) { damage(e, if (e.kind == 0) 1 else 1); if (!enemies.contains(e)) continue }
            if (hit(shipX, shipY, shipW, shipH, e.x, e.y, e.w, e.h) && invulnerable == 0) { if (e.kind != 2) enemies.remove(e); loseLife() }
        }

        // enemy shots fly left two cells a tick; the pod catches them
        val liveEnemy = ArrayList<IntArray>()
        for (s in enemyShots) {
            s[0] -= 2; s[1] += s[2]
            if (hasPod && hit(s[0], s[1], 2, 1, podX, podY, podW, shipH)) continue
            if (hit(s[0], s[1], 2, 1, shipX, shipY, shipW, shipH)) { loseLife(); continue }
            if (s[0] > -2 && s[1] in 0 until rows) liveEnemy.add(s)
        }
        enemyShots.clear(); enemyShots.addAll(liveEnemy)

        // crates drift in; flying into one picks it up
        for (cr in crates.toList()) {
            if (ticks % 3 == 0) cr[0]--
            if (hit(shipX, shipY, shipW, shipH, cr[0], cr[1], 3, 3)) { crates.remove(cr); collect(cr[2]) } else if (cr[0] < -3) crates.remove(cr)
        }

        if (state == State.RUNNING && banner == 0 && ticks >= nextWave) spawnWave()
    }

    private fun collect(type: Int) {
        when (type) {
            0 -> if (hasPod) score += 25 else { podMode = PodMode.ATTACHED; podX = slotX(); podY = slotY() }
            1 -> if (weapon == Weapon.SPREAD) score += 25 else weapon = Weapon.SPREAD
            2 -> if (weapon == Weapon.BOUNCE) score += 25 else weapon = Weapon.BOUNCE
            else -> if (speed >= 2) score += 25 else speed++
        }
    }

    private fun spawnWave() {
        if (bossOut) return
        wave++; waveInStage++
        nextWave = ticks + (46 - wave * 2).coerceAtLeast(24)
        val y = 2 + rnd.nextInt(rows - 10)
        when {
            waveInStage > wavesPerStage -> {
                bossOut = true
                enemies.add(Enemy(cols, rows / 2 - 8, 12, 16, 28 + 12 * stage, 2, 0))
            }
            waveInStage % 4 == 0 -> enemies.add(Enemy(cols, rows / 2 - 3 + rnd.nextInt(9) - 4, 7, 6, 7 + stage, 1, 0))
            waveInStage % 3 == 2 -> { crates.add(intArrayOf(cols, 4 + rnd.nextInt(rows - 12), rnd.nextInt(if (speed >= 2) 3 else 4))); for (i in 0 until 3) enemies.add(Enemy(cols + i * 9, y, 4, 3, 1, 0, y)) }
            else -> for (i in 0 until 4 + stage / 3) enemies.add(Enemy(cols + i * 9, y, 4, 3, 1, 0, y))
        }
    }

    /** For tests: place things exactly. */
    internal fun addEnemy(e: Enemy) { enemies.add(e) }
    internal fun giveCrate(x: Int, y: Int, type: Int = 0) { crates.add(intArrayOf(x, y, type)) }
    internal fun skipToBoss() { waveInStage = wavesPerStage; nextWave = ticks }
}
