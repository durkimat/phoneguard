package io.github.durkimat.phoneguard.launcher

/**
 * A seed-sowing game (mancala, Kalah rules) against the phone. Pits 0 to 5 are yours, left to right on screen, and 6 is
 * your store. Pits 7 to 12 are the phone's with 13 its store. A pit is opposite pit 12 minus its number.
 */
class Seeds(private val perPit: Int = 4) {
    var board = IntArray(14)
        private set
    /** True when it is your go. */
    var yourGo = true
        private set
    var over = false
        private set
    var cursor = 0
        private set
    /** What happened last, for the line under the board. */
    var note = ""
        private set

    init { reset() }

    fun reset() {
        board = IntArray(14) { if (it == 6 || it == 13) 0 else perPit }
        yourGo = true; over = false; cursor = 0; note = "Your go"
    }

    fun moveCursor(d: Int) { if (!over) cursor = (cursor + d + 6) % 6 }
    fun select(i: Int) { if (i in 0..5) cursor = i }

    val yours: Int get() = board[6]
    val theirs: Int get() = board[13]
    val winner: String? get() = if (!over) null else if (yours > theirs) "You win" else if (yours < theirs) "Phone wins" else "A draw"

    /** Play the pit under the cursor. False if it is empty or it is not your go. */
    fun play(): Boolean {
        if (over || !yourGo || board[cursor] == 0) return false
        sow(cursor)
        return true
    }

    /** The phone plays one move: the one that gains the most, taking extra goes first. */
    fun phoneMove() {
        if (over || yourGo) return
        var best = -1; var bestScore = Int.MIN_VALUE
        for (p in 7..12) {
            if (board[p] == 0) continue
            val sim = Seeds(perPit).also { it.board = board.copyOf(); it.yourGo = false }
            val before = sim.board[13]
            val extra = sim.sowTurn(p)
            val s = (sim.board[13] - before) * 10 + (if (extra) 6 else 0) + p // later pits as a gentle tie-break
            if (s > bestScore) { bestScore = s; best = p }
        }
        if (best >= 0) sow(best)
    }

    /** Sow from pit p and apply the rules; returns true if the same player goes again. */
    private fun sowTurn(p: Int): Boolean {
        val mine = p in 0..5
        val store = if (mine) 6 else 13; val skip = if (mine) 13 else 6
        var n = board[p]; board[p] = 0
        var i = p
        while (n > 0) { i = (i + 1) % 14; if (i == skip) continue; board[i]++; n-- }
        if (i == store) return true
        val ownSide = if (mine) i in 0..5 else i in 7..12
        if (ownSide && board[i] == 1 && board[12 - i] > 0) { board[store] += board[i] + board[12 - i]; board[i] = 0; board[12 - i] = 0 }
        return false
    }

    private fun sow(p: Int) {
        val extra = sowTurn(p)
        // the game ends when one side has no seeds: the other side keeps what is left on its side
        if ((0..5).all { board[it] == 0 } || (7..12).all { board[it] == 0 }) {
            for (i in 0..5) { board[6] += board[i]; board[i] = 0 }
            for (i in 7..12) { board[13] += board[i]; board[i] = 0 }
            over = true; note = winner ?: ""; return
        }
        if (extra) note = if (yourGo) "Another go!" else "Phone goes again"
        else { yourGo = !yourGo; note = if (yourGo) "Your go" else "Phone's go" }
        if (yourGo && board[cursor] == 0) cursor = (0..5).firstOrNull { board[it] > 0 } ?: 0
    }

    /** Whether the phone has a move waiting (the timer plays it after a short pause). */
    val phoneToMove: Boolean get() = !over && !yourGo
}
