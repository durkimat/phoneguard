package io.github.durkimat.phoneguard.launcher

/**
 * Where things sit on the home screen around its centre axis, which is the clock's colon (measured at draw time). The weather band is one group centred on the
 * axis (no divider line); the data bar starts on the axis and the data words end [GAP] before it. Design pixels on the 480 wide grid.
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

    /** Where the data words' ink ends (and the data bar starts the other side of [GAP]): symmetric about the axis, whatever the weather group is doing. */
    fun tempInkRight(div: Float) = div - GAP

    // ---- the weather group: [symbol temperature H/L] spacer [rain due / dry], one group centred on the axis (no divider line)
    /** The standard space between the current weather and the rain/dry block. */
    const val WEATHER_SPACER = 22f
    const val SYMBOL = 62f
    const val SYMBOL_GAP = 12f
    const val STACK_GAP = 10f
    /** The least the group may come near the screen's edges. */
    const val EDGE = 14f

    /** Symbol + temperature ink + (when there is a high/low) the stack. */
    fun leftWidth(tempInk: Float, stackInk: Float) = SYMBOL + SYMBOL_GAP + tempInk + if (stackInk > 0f) STACK_GAP + stackInk else 0f
    /** The rain-due block (symbol, time/chance text) or the dry text; 0 without a forecast. */
    fun rightWidth(due: Boolean, textInk: Float) = if (due) RAIN_ICON + TEXT_AFTER_ICON + textInk else textInk
    fun groupWidth(left: Float, right: Float) = left + if (right > 0f) WEATHER_SPACER + right else 0f
    /** The group's left edge when it is centred on [axis]; never nearer the screen edge than [EDGE]. */
    fun groupLeft(axis: Float, left: Float, right: Float) = maxOf(EDGE, axis - groupWidth(left, right) / 2)
    /** The temperature's size: 46, or smaller when the group (everything but the temperature is [rest]) would not fit between the screen edges. */
    fun tempSize(tempInk: Float, rest: Float): Float {
        val room = 480f - 2 * EDGE - rest
        return if (tempInk > room && tempInk > 0f) 46f * maxOf(room, 20f) / tempInk else 46f
    }
}
