package io.github.durkimat.phoneguard.launcher

/**
 * Remembers on which screen each key went down, so a key UP is acted on only when its own DOWN was seen on the screen that is showing now.
 * Keys that act when they come up (the Home right key: short press Contacts, hold Music; OK on Now playing; tap to skip a track) would
 * otherwise treat a stray UP as a press: Back is handled on DOWN (the screen changes to Home), and the matching UP then arrives on Home
 * and used to open Contacts, which on its own Back did the same, in a loop.
 */
class DownGate {
    private val downOn = HashMap<Int, Screen>()

    /** A key went down (first event, not a repeat) while [screen] was showing. */
    fun down(code: Int, screen: Screen) { downOn[code] = screen }

    /** The key came up while [screen] is showing: true only if it went down on this same screen. Always forgets the key. */
    fun up(code: Int, screen: Screen): Boolean = downOn.remove(code) == screen
}
