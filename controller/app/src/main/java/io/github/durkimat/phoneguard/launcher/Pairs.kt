package io.github.durkimat.phoneguard.launcher

import java.util.Random

/** A memory game on a 4 by 4 board: turn two cards at a time, matching pairs stay up. */
class Pairs(private val rnd: Random = Random()) {
    companion object { const val COLS = 4; const val ROWS = 4; const val SYMBOLS = "ABCDEFGH" }

    var cards = IntArray(COLS * ROWS)
        private set
    /** 0 face down, 1 face up, 2 matched. */
    var shown = IntArray(COLS * ROWS)
        private set
    var cursor = 0
        private set
    var moves = 0
        private set
    private var first = -1
    private var pending: Pair<Int, Int>? = null

    init { reset() }

    fun reset() {
        val deck = (0 until COLS * ROWS).map { it / 2 }.toMutableList()
        deck.shuffle(rnd)
        cards = deck.toIntArray(); shown = IntArray(cards.size); cursor = 0; moves = 0; first = -1; pending = null
    }

    val won: Boolean get() = shown.all { it == 2 }
    /** Two different cards are face up and wait to be turned back. */
    val waiting: Boolean get() = pending != null

    fun move(dx: Int, dy: Int) {
        resolve()
        val x = ((cursor % COLS) + dx + COLS) % COLS; val y = ((cursor / COLS) + dy + ROWS) % ROWS
        cursor = y * COLS + x
    }

    fun select(i: Int) { resolve(); if (i in cards.indices) cursor = i }

    /** Turn over the card under the cursor. */
    fun pick() {
        resolve()
        if (won || shown[cursor] != 0) return
        shown[cursor] = 1
        if (first < 0) { first = cursor; return }
        moves++
        if (cards[first] == cards[cursor]) { shown[first] = 2; shown[cursor] = 2 } else pending = first to cursor
        first = -1
    }

    /** Turn a missed pair back over (the timer calls this, or the next key press does). */
    fun resolve() { pending?.let { shown[it.first] = 0; shown[it.second] = 0 }; pending = null }
}
