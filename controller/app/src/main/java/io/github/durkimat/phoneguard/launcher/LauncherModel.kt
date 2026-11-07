package io.github.durkimat.phoneguard.launcher

/**
 * The launcher's behaviour, with no drawing and no Android classes, so it is unit tested and any skin
 * can sit on top of it. A skin shows [screen], [items] and [selected], and tells the model which key
 * was pressed. The model says what should happen next.
 */
enum class Screen { HOME, MENU, SETTINGS, THEME, FONT }

/** A colour scheme: LCD background and ink. Plain ints so the model stays free of Android classes. */
data class Theme(val id: String, val name: String, val lcd: Int, val ink: Int)

object Themes {
    val all = listOf(
        Theme("green", "Green", 0xFF9DC25A.toInt(), 0xFF1A2A08.toInt()),
        Theme("grey", "Grey", 0xFFCFD3CC.toInt(), 0xFF1A1A1A.toInt()),
        Theme("amber", "Amber", 0xFF1A1200.toInt(), 0xFFFFB52E.toInt()),
    )
    const val DEFAULT = "green"
    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }
}

/** A typeface choice. The skin maps the id to a real typeface. */
data class FontChoice(val id: String, val name: String)

object Fonts {
    val all = listOf(FontChoice("mono", "Monospace"), FontChoice("sans", "Sans-serif"), FontChoice("vt323", "Pixel VT323"), FontChoice("pixelify", "Pixelify"))
    const val DEFAULT = "mono"
    fun byId(id: String?): FontChoice = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }
}

/** The entry the launcher adds to its own menu; it opens the launcher's Settings, not an app. */
const val SETTINGS_PKG = "pg:settings"

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
    /** The user chose a theme: the activity saves it and redraws. */
    data class SaveTheme(val id: String) : Action()
    data class SaveFont(val id: String) : Action()
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

    /** The id of the theme in use. The activity sets it from storage at start. */
    var theme: String = Themes.DEFAULT

    var font: String = Fonts.DEFAULT

    private val settingsRows = listOf("Theme", "Font")

    fun goHome() { screen = Screen.HOME; selected = 0 }

    val title: String get() = when (screen) {
        Screen.SETTINGS -> "Settings"
        Screen.THEME -> "Theme"
        Screen.FONT -> "Font"
        else -> "Menu"
    }

    /** The lines of whichever list is showing. */
    val rows: List<String> get() = when (screen) {
        Screen.MENU -> items.map { it.label }
        Screen.SETTINGS -> settingsRows
        Screen.THEME -> Themes.all.map { it.name + if (it.id == theme) " *" else "" }
        Screen.FONT -> Fonts.all.map { it.name + if (it.id == font) " *" else "" }
        Screen.HOME -> emptyList()
    }

    /** `digit` is only used with [Key.DIGIT]. */
    fun onKey(key: Key, digit: Int = -1): Action = when (screen) {
        Screen.HOME -> home(key, digit)
        Screen.MENU -> menu(key, digit)
        Screen.SETTINGS -> settings(key, digit)
        Screen.THEME -> themes(key, digit)
        Screen.FONT -> fonts(key, digit)
    }

    private fun move(key: Key, size: Int): Action? = when (key) {
        Key.UP -> if (size == 0) Action.None else { selected = (selected - 1 + size) % size; Action.Redraw }
        Key.DOWN -> if (size == 0) Action.None else { selected = (selected + 1) % size; Action.Redraw }
        else -> null
    }

    private fun open(item: MenuItem?): Action = when {
        item == null -> Action.None
        item.pkg == SETTINGS_PKG -> { screen = Screen.SETTINGS; selected = 0; Action.Redraw }
        else -> Action.Launch(item.pkg)
    }

    private fun settings(key: Key, digit: Int): Action = move(key, settingsRows.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> {
            if (selected == 0) { screen = Screen.THEME; selected = Themes.all.indexOfFirst { it.id == theme }.coerceAtLeast(0) }
            else { screen = Screen.FONT; selected = Fonts.all.indexOfFirst { it.id == font }.coerceAtLeast(0) }
            Action.Redraw
        }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.MENU; selected = items.indexOfFirst { it.pkg == SETTINGS_PKG }.coerceAtLeast(0); Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun fonts(key: Key, digit: Int): Action = move(key, Fonts.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { font = Fonts.all[selected].id; screen = Screen.SETTINGS; selected = 1; Action.SaveFont(font) }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SETTINGS; selected = 1; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    private fun themes(key: Key, digit: Int): Action = move(key, Themes.all.size) ?: when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { theme = Themes.all[selected].id; screen = Screen.SETTINGS; selected = 0; Action.SaveTheme(theme) }
        Key.RIGHT, Key.SOFT_RIGHT -> { screen = Screen.SETTINGS; selected = 0; Action.Redraw }
        Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        else -> Action.None
    }

    // The two labels at the bottom of the screen belong to the two keys under the screen (left key =
    // left label, right key = right label), like a real Nokia. D-pad Left and Right do the same.
    private fun home(key: Key, digit: Int): Action = when (key) {
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> { screen = Screen.MENU; selected = 0; Action.Redraw }
        Key.RIGHT, Key.SOFT_RIGHT -> contactsPkg?.let { Action.Launch(it) } ?: Action.None
        Key.CALL -> Action.CallKey
        Key.DIGIT -> if (digit in 0..9) Action.Dial(digit.toString()) else Action.None
        Key.STAR -> Action.Dial("*")
        Key.POUND -> Action.Dial("#")
        else -> Action.None
    }

    private fun menu(key: Key, digit: Int): Action = when (key) {
        Key.UP -> if (items.isEmpty()) Action.None else { selected = (selected - 1 + items.size) % items.size; Action.Redraw }
        Key.DOWN -> if (items.isEmpty()) Action.None else { selected = (selected + 1) % items.size; Action.Redraw }
        Key.OK, Key.LEFT, Key.SOFT_LEFT -> open(items.getOrNull(selected))
        Key.RIGHT, Key.SOFT_RIGHT, Key.END -> { goHome(); Action.Redraw }
        Key.CALL -> Action.CallKey
        // 1 to 9 jump straight to that entry, like a Nokia menu
        Key.DIGIT -> if (digit in 1..9) items.getOrNull(digit - 1)?.let { selected = digit - 1; open(it) } ?: Action.None else Action.None
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
