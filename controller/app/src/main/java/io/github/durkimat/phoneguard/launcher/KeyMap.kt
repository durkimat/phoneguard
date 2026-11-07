package io.github.durkimat.phoneguard.launcher

import android.view.KeyEvent

/** Maps what the hardware reports to the keys the launcher model understands (shared by every keypad screen). */
object KeyMap {
    fun of(code: Int): Pair<Key, Int>? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Key.UP to -1
        KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN to -1
        KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT to -1
        KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT to -1
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Key.OK to -1
        KeyEvent.KEYCODE_MENU -> Key.SOFT_LEFT to -1
        KeyEvent.KEYCODE_BACK -> Key.SOFT_RIGHT to -1
        KeyEvent.KEYCODE_CALL -> Key.CALL to -1
        KeyEvent.KEYCODE_ENDCALL -> Key.END to -1
        KeyEvent.KEYCODE_STAR -> Key.STAR to -1
        KeyEvent.KEYCODE_POUND -> Key.POUND to -1
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> Key.DIGIT to (code - KeyEvent.KEYCODE_0)
        else -> null
    }
}
