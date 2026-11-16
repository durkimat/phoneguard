package io.github.durkimat.phoneguard.launcher

/**
 * Where things sit in the home screen's weather band, around the divider. The divider stands on the clock's colon; the current weather ends [GAP] before it
 * and the forecast block starts [GAP] after it, so both sides have the same space. Design pixels on the 480 wide grid.
 */
object BandGeometry {
    const val GAP = 22f
    /** The colon of the clock in the default font; other fonts (or proportional digits) are measured at draw time and kept near this. */
    const val DEFAULT_DIV = 242f
    const val RAIN_ICON = 44f
    const val TEXT_AFTER_ICON = 10f

    /** The divider's x: the measured colon centre when it is believable, else the default. */
    fun divider(measured: Float?): Float = if (measured != null && measured in 232f..252f) measured else DEFAULT_DIV

    /** The screen's centre axis is the divider (the clock's colon). Elements drawn centred at 240 are nudged onto it: how far their ink sits left of the axis in the default font (measured on pg480, 0.4.20), plus how far this font's colon is from the default one. */
    const val DATE_NUDGE = 1.5f
    const val BADGE_NUDGE = 2.5f
    fun axisShift(div: Float) = div - DEFAULT_DIV
    /** Where the data bar's outline is drawn: its first ink column is the divider's. */
    fun dataBarLeft(div: Float) = div
    const val DATA_BAR_RIGHT = 466f

    const val DATA_TEXT_SIZE = 26f
    /** Where the data words' ink ends: with the weather group's, 22 px before the bar. */
    fun dataTextRight(div: Float) = tempInkRight(div)
    /** The words' size: [DATA_TEXT_SIZE] unless their ink is wider than the room (from x 14 to the right edge), then shrunk to fit. */
    fun dataTextSize(inkWidth: Float, room: Float) = if (inkWidth > room && inkWidth > 0f) DATA_TEXT_SIZE * room / inkWidth else DATA_TEXT_SIZE

    /** Where the temperature's ink must end. */
    fun tempInkRight(div: Float) = div - GAP
    /** Where the right-hand block starts (its ink edge). */
    fun rightEdge(div: Float) = div + GAP
    fun rainIconCx(div: Float) = rightEdge(div) + RAIN_ICON / 2
    /** The dry text's ink is drawn two columns right of [rightEdge]: measured on the emulator, the ink lands one column left of where the outline says, and the line's own pixels end one column after [divider]. */
    fun dryTextX(div: Float) = rightEdge(div) + 2f
    /** Where the rain-due text lines start. */
    fun rainTextX(div: Float) = rightEdge(div) + RAIN_ICON + TEXT_AFTER_ICON
}
