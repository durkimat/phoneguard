package io.github.durkimat.phoneguard.launcher

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Small pictures drawn on a 16 by 16 grid of squares, one letter per square: k outline (ink), w paper (the screen's
 * background), g half tone, r a filled part, . nothing. A skin draws them at any whole-number size, so they stay crisp.
 */
object PixelIcons {
    private val ICONS: Map<String, List<String>> = mapOf(
        "music" to listOf("................", ".......kkkkkkkk.", ".......krrrrrrk.", ".......kkkkkkkk.", ".......k......k.", ".......k......k.", ".......k......k.", ".......k......k.", "....kkkk...kkkk.", "...kkrrkk.kkrrkk", "...kkrrkk.kkrrkk", "....kkkk...kkkk.", "................", "................", "................", "................"),
        "calls" to listOf("................", ".kkkkk..........", ".krrrrk.........", ".krrrrrk........", ".kkrrrrk........", "..kkrrrk........", "...kkrrk........", "....krrk........", ".....krrkk......", "......krrrkk....", ".......krrrrkk..", "........kkrrrrk.", "..........kkrrrk", "............krrk", ".............kkk", "................"),
        "messages" to listOf("................", "................", ".kkkkkkkkkkkkkk.", ".kkwwwwwwwwwwkk.", ".kwkwwwwwwwwkwk.", ".kwwkwwwwwwkwwk.", ".kwwwkwwwwkwwwk.", ".kwwwwkwwkwwwwk.", ".kwwwwwkkwwwwwk.", ".kwwwwwwwwwwrrk.", ".kwwwwwwwwwwrrk.", ".kkkkkkkkkkkkkk.", "................", "................", "................", "................"),
        "contacts" to listOf("................", ".....kkkkkk.....", "....kwwwwwwk....", "....kwwwwwwk....", "....kwkwwkwk....", "....kwwwwwwk....", ".....kwwwwk.....", ".....kkkkkk.....", "..kkkrrrrrkkk...", ".krrrrrrrrrrrk..", ".krrrrrrrrrrrk..", ".krrrrrrrrrrrk..", ".kkkkkkkkkkkkk..", "................", "................", "................"),
        "games" to listOf("................", "................", "................", "................", "..kkkkkkkkkkkk..", ".kwwwwwwwwwwwwk.", ".kwwkwwwwwwrwwk.", ".kwkkkwwwwrrrwk.", ".kwwkwwwwwwrwwk.", ".kwwwwwwwwwwwwk.", ".kkwwwwwwwwwwkk.", "..kkkkkkkkkkkk..", "................", "................", "................", "................"),
        "alarm" to listOf("................", "..rrr......rrr..", "..rrr......rrr..", "....kkkkkkkk....", "..kkwwwwwwwwkk..", ".kwwwwwkwwwwwwk.", ".kwwwwwkwwwwwwk.", ".kwwwwwkwwwwwwk.", ".kwwwwwkkkkwwwk.", ".kwwwwwwwwwwwwk.", ".kwwwwwwwwwwwwk.", "..kkwwwwwwwwkk..", "....kkkkkkkk....", "..k..........k..", "................", "................"),
        "settings" to listOf("................", "................", ".........kkkk...", "..ggggggkrrkgg..", ".........kkkk...", "................", "...kkkk.........", "..ggkrrkggggggg.", "...kkkk.........", "................", ".....kkkk.......", "..gggkrrkgggggg.", ".....kkkk.......", "................", "................", "................"),
        "radio" to listOf("................", "..............k.", "............kk..", "..........kk....", "........kk......", ".kkkkkkkkkkkkkk.", ".kwwwwwwwwwwwwk.", ".kwgwgwgwwkkkwk.", ".kwwwwwwwwkrkwk.", ".kwgwgwgwwkkkwk.", ".kwwwwwwwwwwwwk.", ".kwwwwwwkkkkwwk.", ".kkkkkkkkkkkkkk.", "................", "................", "................"),
        "about" to listOf("................", ".kkkkkkkkkkkkkk.", ".kwwwwwwwwwwwwk.", ".kwwwwwrrwwwwwk.", ".kwwwwwrrwwwwwk.", ".kwwwwwwwwwwwwk.", ".kwwwwwrrwwwwwk.", ".kwwwwwrrwwwwwk.", ".kwwwwwrrwwwwwk.", ".kwwwwwrrwwwwwk.", ".kwwwwrrrrwwwwk.", ".kkkkkkkkkkkkkk.", "................", "................", "................", "................"),
        "camera" to listOf("................", "................", "....kkkk........", ".kkkkkkkkkkkkkk.", ".kwwwwwwwwwwwwk.", ".kwwwwkkkkwwwwk.", ".kwwwkwwwwkwwwk.", ".kwwwkwrrwkwwwk.", ".kwwwkwrrwkwwwk.", ".kwwwkwwwwkwwwk.", ".kwwwwkkkkwwwwk.", ".kwwwwwwwwwwwwk.", ".kkkkkkkkkkkkkk.", "................", "................", "................"),
        "apps" to listOf("................", "................", ".kkkkkk..kkkkkk.", ".krrrrk..krrrrk.", ".krrrrk..krrrrk.", ".krrrrk..krrrrk.", ".krrrrk..krrrrk.", ".kkkkkk..kkkkkk.", "................", ".kkkkkk..kkkkkk.", ".krrrrk..kwwwwk.", ".krrrrk..kwwwwk.", ".krrrrk..kwwwwk.", ".krrrrk..kwwwwk.", ".kkkkkk..kkkkkk.", "................"),
        "calculator" to listOf("................", "...kkkkkkkkkk...", "...kwwwwwwwwk...", "...kwkkkkkkwk...", "...kwkwwwwkwk...", "...kwkkkkkkwk...", "...kwwwwwwwwk...", "...kwrwrwrwwk...", "...kwwwwwwwwk...", "...kwrwrwrwwk...", "...kwwwwwwwwk...", "...kwrwrwrwwk...", "...kwwwwwwwwk...", "...kkkkkkkkkk...", "................", "................"),
        "notes" to listOf("................", "................", "...kkkkkkkkkk...", "...kwwwwwwwwk...", "...kwrrrrrrwk...", "...kwwwwwwwwk...", "...kwrrrrrrwk...", "...kwwwwwwwwk...", "...kwrrrrrrwk...", "...kwwwwwwwwk...", "...kwrrrrwwwk...", "...kwwwwwwwwk...", "...kkkkkkkkkk...", "................", "................", "................"),
        "weather" to listOf("................", "................", "..........rr....", ".........rrrr...", "...kkkk..rrrr...", "..kwwwwkk.rr....", ".kwwwwwwwkk.....", ".kwwwwwwwwwkk...", ".kwwwwwwwwwwwk..", ".kwwwwwwwwwwwk..", "..kkkkkkkkkkk...", "................", "................", "................", "................", "................"),
        "chat" to listOf("................", "................", "..kkkkkkkkkkkk..", ".kwwwwwwwwwwwwk.", ".kwwwrwwrwwrwwk.", ".kwwwwwwwwwwwwk.", ".kwwwwwwwwwwwwk.", ".kkkkkkkkkkkkkk.", "..kwwk..........", "...kwk..........", "....kk..........", "................", "................", "................", "................", "................"),
        "mail" to listOf("................", "................", "................", ".kkkkkkkkkkkkkk.", ".kwwwwwwwwwwwwk.", ".kkwwwwwwwwwwkk.", ".kwkwwwwwwwwkwk.", ".kwwkkwwwwkkwwk.", ".kwwwwkkkkwwwwk.", ".kwwwwwwwwwwwwk.", ".kwwwwwwwwwwwwk.", ".kkkkkkkkkkkkkk.", "................", "................", "................", "................"),
        "calendar" to listOf("................", "................", "...kk......kk...", ".kkkkkkkkkkkkkk.", ".krrrrrrrrrrrrk.", ".kkkkkkkkkkkkkk.", ".kwwwwwwwwwwwwk.", ".kwkwkwkwkwkwwk.", ".kwwwwwwwwwwwwk.", ".kwkwkwkwkwkwwk.", ".kwwwwwwwwwwwwk.", ".kwkwkwrrwwwwwk.", ".kwwwwwwwwwwwwk.", ".kkkkkkkkkkkkkk.", "................", "................"),
        "app" to listOf("................", "................", ".kkkkkkkkkkkkkk.", ".krrrrrrrrrrrrk.", ".kkkkkkkkkkkkkk.", ".kwwwwwwwwwwwwk.", ".kwwkkkkkkkkwwk.", ".kwwwwwwwwwwwwk.", ".kwwkkkkkwwwwwk.", ".kwwwwwwwwwwwwk.", ".kwwkkkkkkwwwwk.", ".kwwwwwwwwwwwwk.", ".kkkkkkkkkkkkkk.", "................", "................", "................"),
    )

    private val paint by lazy { Paint().apply { style = Paint.Style.FILL; isAntiAlias = false } } // lazy: unit tests never draw

    /** The names a skin can ask for. */
    val names: Set<String> get() = ICONS.keys

    /** Draws icon [id] with its top left corner at ([x], [y]); each square is [px] pixels. [ink] draws the lines, [lcd] the paper. */
    fun draw(c: Canvas, id: String, x: Float, y: Float, px: Float, ink: Int, lcd: Int, fillInk: Int = ink) {
        val rows = ICONS[id] ?: ICONS.getValue("app")
        val half = blend(ink, lcd)
        for ((r, line) in rows.withIndex()) {
            for (col in 0 until 16) {
                paint.color = when (line.getOrElse(col) { '.' }) {
                    'k' -> ink
                    'r' -> fillInk
                    'g' -> half
                    'w' -> lcd
                    else -> continue
                }
                c.drawRect(x + col * px, y + r * px, x + (col + 1) * px, y + (r + 1) * px, paint)
            }
        }
    }

    private fun blend(a: Int, b: Int): Int {
        fun mix(s: Int) = ((a shr s and 0xFF) + (b shr s and 0xFF)) / 2
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Every icon is 16 rows of 16 squares; used by a test to catch a typo in the drawings. */
    internal fun problems(): List<String> = ICONS.flatMap { (n, rows) ->
        buildList {
            if (rows.size != 16) add("$n has ${rows.size} rows")
            rows.forEachIndexed { i, r -> if (r.length != 16) add("$n row $i has ${r.length} squares") }
            rows.forEachIndexed { i, r -> r.forEach { if (it !in ".kwgr") add("$n row $i has '$it'") } }
        }
    }
}
