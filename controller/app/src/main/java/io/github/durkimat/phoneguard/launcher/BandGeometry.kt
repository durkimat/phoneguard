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

    /** Where the temperature's ink must end. */
    fun tempInkRight(div: Float) = div - GAP
    /** Where the right-hand block starts (its ink edge). */
    fun rightEdge(div: Float) = div + GAP
    fun rainIconCx(div: Float) = rightEdge(div) + RAIN_ICON / 2
    /** Where the rain-due text lines start; dry text starts at [rightEdge]. */
    fun rainTextX(div: Float) = rightEdge(div) + RAIN_ICON + TEXT_AFTER_ICON
}
