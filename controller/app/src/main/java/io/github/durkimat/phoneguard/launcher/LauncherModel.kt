package io.github.durkimat.phoneguard.launcher

/**
 * The launcher's behaviour, with no drawing and no Android classes, so it is unit tested and any skin
 * can sit on top of it. A skin shows [screen], [items] and [selected], and tells the model which key
 * was pressed. The model says what should happen next.
 */
enum class Screen { HOME, MENU }

/** The keys a keypad phone has, however the hardware reports them. */
enum class Key { UP, DOWN, LEFT, RIGHT, OK, SOFT_LEFT, SOFT_RIGHT, CALL, END, STAR, POUND, DIGIT }

data class MenuItem(val pkg: String, val label: String)

sealed class Action {
    /** Nothing to do, or the key is not ours. */
    object None : Action()
    /** The screen changed: redraw. */
    object Redraw : Action()
    data class Launch(val pkg: String) : Action()
    /** Open the dialler with these digits already typed. */
    data class Dial(val digits: String) : Action()
    /** Open the call log or dialler (the green key). */
    object CallKey : Action()
}

class LauncherModel(items: List<MenuItem> = emptyList(), var contactsPkg: String? = null) {
    var screen = Screen.HOME
        private set
    var items: List<MenuItem> = items
        private set
    var selected = 0
        private set

    fun setItems(list: List<MenuItem>) {
        items = list
        selected = selected.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    fun goHome() { screen = Screen.HOME; selected = 0 }

    /** `digit` is only used with [Key.DIGIT]. */
    fun onKey(key: Key, digit: Int = -1): Action = when (screen) {
        Screen.HOME -> home(key, digit)
        Screen.MENU -> menu(key, digit)
    }

    // The two labels at the bottom of the screen belong to D-pad Left and Right. The keys under the
    // screen do other things on this phone (the left one opens Android's own menus, the right one is
    // Back), so the physical left key is ignored and Back does nothing on the home screen.
    private fun home(key: Key, digit: Int): Action = when (key) {
        Key.OK, Key.LEFT -> { screen = Screen.MENU; selected = 0; Action.Redraw }
        Key.RIGHT -> contactsPkg?.let { Action.Launch(it) } ?: Action.None
        Key.CALL -> Action.CallKey
        Key.DIGIT -> if (digit in 0..9) Action.Dial(digit.toString()) else Action.None
        Key.STAR -> Action.Dial("*")
        Key.POUND -> Action.Dial("#")
        else -> Action.None
    }

    private fun menu(key: Key, digit: Int): Action = when (key) {
        Key.UP -> if (items.isEmpty()) Action.None else { selected = (selected - 1 + items.size) % items.size; Action.Redraw }
        Key.DOWN -> if (items.isEmpty()) Action.None else { selected = (selected + 1) % items.size; Action.Redraw }
        Key.OK, Key.LEFT -> items.getOrNull(selected)?.let { Action.Launch(it.pkg) } ?: Action.None
        Key.RIGHT, Key.SOFT_RIGHT, Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        // 1 to 9 jump straight to that entry, like a Nokia menu
        Key.DIGIT -> if (digit in 1..9) items.getOrNull(digit - 1)?.let { selected = digit - 1; Action.Launch(it.pkg) } ?: Action.None else Action.None
        else -> Action.None
    }
}

/** What the home screen shows besides the menu. Plain data so a skin can draw it any way it likes. */
data class HomeInfo(
    val time: String,
    val date: String,
    val modeLine: String,
    val missedCalls: Int,
    val newMessages: Int,
    val batteryPct: Int,
    val charging: Boolean,
    /** -1 means no SIM, otherwise 0 to 4 bars. */
    val signalBars: Int,
    val softLeft: String,
    val softRight: String,
)

/** A way of drawing the launcher. The engine never knows which one is in use. */
interface LauncherSkin {
    val id: String
    fun create(context: android.content.Context): android.view.View
    fun render(model: LauncherModel, info: HomeInfo)
}
