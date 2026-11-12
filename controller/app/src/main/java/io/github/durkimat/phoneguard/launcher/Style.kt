package io.github.durkimat.phoneguard.launcher

/**
 * The finer points of how the phone looks, beyond the theme: window frames, colour, wallpaper, shadows, the analyser's peaks and a
 * typeface for each of three roles. Plain data, like [Look]; a skin reads it and draws. Every field has the same ids on the phone,
 * in the Home Assistant integration and in the card (a test in `ha/tests` keeps the three lists identical).
 */
data class Style(
    /** Mac window frame: stripes (System 7), platinum (OS 8) or flat. */
    val windows: String = "stripes",
    /** mono, red (icons and marks in the accent) or colour (a coloured selection and a colour for each icon). */
    val colour: String = "mono",
    /** What shows behind the Mac window: dither, plain, blue or green. */
    val wall: String = "dither",
    /** on: windows and boxes cast a hard shadow; off: none. */
    val shadow: String = "on",
    /** The analyser: red (held peaks in the accent), same (peaks in ink), grad (a colour that climbs with the level) or none. */
    val bars: String = "red",
    /** A typeface for the clock, for body text and for titles and soft keys. null: the phone's one chosen font. */
    val fontDisplay: String? = null,
    val fontText: String? = null,
    val fontLabel: String? = null,
) {
    operator fun get(key: String): String? = when (key) {
        "windows" -> windows; "colour" -> colour; "wall" -> wall; "shadow" -> shadow; "bars" -> bars
        "font_display" -> fontDisplay; "font_text" -> fontText; "font_label" -> fontLabel
        else -> null
    }

    fun with(key: String, id: String?): Style = when (key) {
        "windows" -> copy(windows = id ?: windows); "colour" -> copy(colour = id ?: colour); "wall" -> copy(wall = id ?: wall)
        "shadow" -> copy(shadow = id ?: shadow); "bars" -> copy(bars = id ?: bars)
        "font_display" -> copy(fontDisplay = id); "font_text" -> copy(fontText = id); "font_label" -> copy(fontLabel = id)
        else -> this
    }
}

/** One entry in a list of choices. */
data class Opt(val id: String, val name: String)

object Choices {
    /** The settings, in the order the Appearance screen lists them, with what each is called. */
    val keys = listOf("windows", "colour", "wall", "shadow", "bars", "font_display", "font_text", "font_label")
    private val titles = mapOf(
        "windows" to "Windows", "colour" to "Colour", "wall" to "Wallpaper", "shadow" to "Shadow", "bars" to "Spectrum",
        "font_display" to "Clock font", "font_text" to "Text font", "font_label" to "Title font", "preset" to "Presets",
    )
    fun title(key: String) = titles[key] ?: key

    val windows = listOf(Opt("stripes", "Stripes (System 7)"), Opt("platinum", "Platinum (OS 8)"), Opt("flat", "Flat"))
    val colour = listOf(Opt("mono", "Mono"), Opt("red", "Red"), Opt("colour", "Colourful"))
    val wall = listOf(Opt("dither", "Dither"), Opt("plain", "Plain"), Opt("blue", "Blue"), Opt("green", "Green"))
    val shadow = listOf(Opt("on", "Hard"), Opt("off", "None"))
    val bars = listOf(Opt("red", "Red peaks"), Opt("same", "Same colour"), Opt("grad", "Gradient"), Opt("none", "No peaks"))
    /** "Same as Font" (null) or one of the typefaces. */
    val fonts: List<Opt> get() = Fonts.all.map { Opt(it.id, it.name) }

    /** The choices for a setting, or empty for a name that is not one. */
    fun of(key: String): List<Opt> = when (key) {
        "windows" -> windows; "colour" -> colour; "wall" -> wall; "shadow" -> shadow; "bars" -> bars
        "font_display", "font_text", "font_label" -> fonts
        else -> emptyList()
    }

    fun valid(key: String, id: String?): Boolean = id != null && of(key).any { it.id == id }
    fun isFont(key: String) = key.startsWith("font_")

    /** What a setting is showing now, for the Appearance list. */
    fun valueName(s: Style, key: String): String {
        val id = s[key]
        return if (id == null) "Same as Font" else of(key).firstOrNull { it.id == id }?.name ?: id
    }
}

/** A look in one go: theme, main menu and every Appearance setting. Choosing one sets them all (except what a parent holds). */
data class Preset(val id: String, val name: String, val theme: String, val menuStyle: String, val style: Style)

object Presets {
    val all = listOf(
        Preset("favourites", "My favourites", "mac", "list", Style("stripes", "colour", "blue", "on", "red", "inst", "inter", "silk")),
        Preset("pixelify", "All Pixelify, mono", "mac", "list", Style("stripes", "mono", "dither", "on", "red", "pixelify", "pixelify", "pixelify")),
        Preset("arcade", "Arcade grid (night)", "macdark", "grid", Style("stripes", "colour", "dither", "on", "red", "j10", "j15", "ps2p")),
        Preset("platinum", "Platinum grid", "mac", "grid", Style("platinum", "colour", "blue", "on", "red", "pixelify", "pixelify", "silk")),
        Preset("terminal", "Terminal green", "macdark", "list", Style("flat", "colour", "green", "off", "red", "vt323", "vt323", "vt323")),
        Preset("dotmatrix", "Dot-matrix (Doto)", "macdark", "list", Style("flat", "red", "plain", "off", "red", "doto", "dot", "doto")),
    )
    fun byId(id: String?): Preset? = all.firstOrNull { it.id == id }
}

/** A typeface choice. The skin loads [asset] from the app's assets (none: a system face) and scales text by the per-role factors. */
data class FontChoice(
    val id: String, val name: String, val asset: String? = null,
    val display: Float = 1f, val text: Float = 1f, val label: Float = 1f,
    /** The label role is set in capitals (small pixel faces read better that way). */
    val caps: Boolean = false,
    /** Letter spacing for the clock, in ems. */
    val tracking: Float = 0f,
)

object Fonts {
    val all = listOf(
        FontChoice("mono", "Monospace"), FontChoice("sans", "Sans-serif"),
        FontChoice("vt323", "Pixel VT323", "VT323-Regular.ttf", 1.3f, 1.3f, 1.3f),
        FontChoice("pixelify", "Pixelify", "PixelifySans.ttf"),
        FontChoice("inst", "Instrument Serif", "InstrumentSerif.ttf", 1f, 1.05f, 1.05f, tracking = -.035f),
        FontChoice("inter", "Inter", "Inter.ttf", .9f, 1f, 1f),
        FontChoice("silk", "Silkscreen", "Silkscreen.ttf", .6f, .8f, 1f, caps = true),
        FontChoice("dot", "DotGothic16", "DotGothic16.ttf", .85f, .95f, .95f),
        FontChoice("j15", "Jersey 15", "Jersey15.ttf", 1.1f, 1.2f, 1.2f),
        FontChoice("j10", "Jersey 10", "Jersey10.ttf", 1.2f, 1.35f, 1.35f),
        FontChoice("tiny", "Tiny5", "Tiny5.ttf", .95f, 1.05f, 1.05f),
        FontChoice("micro", "Micro 5", "Micro5.ttf", 1.3f, 1.5f, 1.5f),
        FontChoice("hand", "Handjet", "Handjet.ttf", 1.3f, 1.4f, 1.4f),
        FontChoice("doto", "Doto", "Doto.ttf"),
        FontChoice("bit", "Bitcount Single", "BitcountSingle.ttf", .95f, 1f, 1f),
        FontChoice("ps2p", "Press Start 2P", "PressStart2P.ttf", .6f, .7f, .65f, caps = true),
        FontChoice("bench", "Workbench", "Workbench.ttf", .9f, 1f, 1f),
        FontChoice("jac", "Jacquard 12", "Jacquard12.ttf", 1.35f, 1.5f, 1.5f),
        FontChoice("sixty", "Sixtyfour", "Sixtyfour.ttf", .7f, .8f, .75f),
    )
    const val DEFAULT = "mono"
    fun byId(id: String?): FontChoice = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }
}
