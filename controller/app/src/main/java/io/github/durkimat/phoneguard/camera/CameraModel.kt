package io.github.durkimat.phoneguard.camera

import io.github.durkimat.phoneguard.launcher.Key

/** One thing in the gallery: a photo or a video the camera has saved. */
data class MediaItem(val uri: String, val video: Boolean, val name: String, val time: Long)

enum class CamMode { PHOTO, VIDEO }
enum class FlashMode { OFF, AUTO, ON }
/** What the viewfinder shows and which extra copy a photo gets next to the colour original. NONE: plain viewfinder, no extra copy. */
enum class Effect(val label: String, val extra: String) {
    NONE("Normal", ""), DOTS("Dots", "dots"), TONES("Tones", "tones"), MONO("Mono", "mono"), WARM("Warm", "warm"),
    HALFTONE("Newsprint", "newsprint"), POSTER("Poster", "poster"), OUTLINE("Outline", "outline"), SCAN("Scan lines", "scan"), FRAME("Dated frame", "dated"),
}

sealed class CamAction {
    object None : CamAction()
    object Redraw : CamAction()
    object Shoot : CamAction()
    object ToggleRecording : CamAction()
    object Rebind : CamAction()
    object OpenGallery : CamAction()
    object ShowItem : CamAction()
    object Exit : CamAction()
    object Home : CamAction()
}

/**
 * Behaviour of the themed camera, with no camera in it: what each key does on the viewfinder and in the gallery. The activity
 * carries out the actions and tells the model what happened (saved, recording, a countdown).
 */
class CameraModel {
    var mode = CamMode.PHOTO
        private set
    var flash = FlashMode.OFF
        private set
    var front = false
        private set
    /** Index into [zooms]. */
    var zoomIndex = 0
        private set
    var timerSeconds = 0
        private set
    var effect = Effect.DOTS
        private set
    var inGallery = false
        private set
    var recording = false
    /** The help page (what every key does) is showing; any key closes it. */
    var help = false
        private set
    /** Seconds left of a self-timer count, or 0. */
    var countdown = 0
    var recordingSeconds = 0
    /** A short message shown for a moment: "Saved", "No camera". */
    var notice: String? = null
    var items: List<MediaItem> = emptyList()
    var index = 0
        private set
    /** True when the gallery shows the colour photo instead of the dithered one. */
    var colour = false
        private set

    companion object { val zooms = floatArrayOf(1f, 1.5f, 2f, 3f); val timers = intArrayOf(0, 3, 10) }

    val zoom: Float get() = zooms[zoomIndex]
    val current: MediaItem? get() = items.getOrNull(index)

    /** What every key does, one line each: shown by the Help key. */
    val helpLines: List<String> = listOf(
        "OK or side button: take photo,",
        "   or start / stop a video",
        "Up, Down: zoom in and out",
        "Left, Right: photo or video",
        "1  Gallery          2  Flash",
        "3  Self-timer       #  Front/back",
        "4  Next effect      6  Last effect",
        "Effects: Normal, Dots, Tones, Mono,",
        "Warm, Newsprint, Poster, Outline,",
        "Scan lines, Dated frame. All but",
        "Normal save a second picture too.",
        "Gallery: Left, Right: other photo,",
        "OK: colour or dots.  Back: leave.",
    )

    /** The line of settings under the viewfinder. */
    val infoLine: String get() = buildString {
        append(if (zoom == zoom.toInt().toFloat()) "${zoom.toInt()}x" else "${zoom}x")
        if (mode == CamMode.PHOTO) append("  Flash ").append(flash.name.lowercase()) else append("  Torch ").append(if (flash == FlashMode.ON) "on" else "off")
        if (timerSeconds > 0) append("  Timer ${timerSeconds}s")
        append("  ").append(effect.label)
    }

    fun setGallery(list: List<MediaItem>) { items = list; index = index.coerceIn(0, (list.size - 1).coerceAtLeast(0)); inGallery = true; colour = false }

    fun onKey(key: Key, digit: Int): CamAction {
        if (help) { help = false; return if (key == Key.END) CamAction.Home else CamAction.Redraw } // any key closes the help
        if (countdown > 0) { countdown = 0; notice = "Cancelled"; return CamAction.Redraw } // any key stops a self-timer
        return if (inGallery) gallery(key, digit) else finder(key, digit)
    }

    private fun finder(key: Key, digit: Int): CamAction = when {
        key == Key.OK || (key == Key.DIGIT && digit == 5) -> shutter()
        key == Key.UP -> { if (zoomIndex < zooms.size - 1) zoomIndex++; CamAction.Redraw }
        key == Key.DOWN -> { if (zoomIndex > 0) zoomIndex--; CamAction.Redraw }
        key == Key.LEFT || key == Key.RIGHT -> if (recording) CamAction.None else { mode = if (mode == CamMode.PHOTO) CamMode.VIDEO else CamMode.PHOTO; CamAction.Rebind }
        key == Key.DIGIT && digit == 1 -> CamAction.OpenGallery
        key == Key.DIGIT && digit == 2 -> { flash = FlashMode.values()[(flash.ordinal + 1) % (if (mode == CamMode.PHOTO) 3 else 3)]; CamAction.Redraw }
        key == Key.DIGIT && digit == 3 -> { timerSeconds = timers[(timers.indexOf(timerSeconds) + 1) % timers.size]; CamAction.Redraw }
        key == Key.DIGIT && (digit == 4 || digit == 6) -> {
            val before = effect
            val n = Effect.values().size
            effect = Effect.values()[(effect.ordinal + (if (digit == 4) 1 else n - 1)) % n]
            // the colour viewfinder is a different camera set-up from the drawn ones, so crossing Normal rebinds
            if (before == Effect.NONE || effect == Effect.NONE) CamAction.Rebind else CamAction.Redraw
        }
        key == Key.POUND -> if (recording) CamAction.None else { front = !front; zoomIndex = 0; CamAction.Rebind }
        // the left key under the screen is Help (OK, the side button and the volume keys take the picture); while recording it stops
        key == Key.SOFT_LEFT -> if (recording) CamAction.ToggleRecording else { help = true; CamAction.Redraw }
        key == Key.SOFT_RIGHT -> if (recording) CamAction.None else CamAction.Exit
        key == Key.END -> CamAction.Home
        else -> CamAction.None
    }

    private fun shutter(): CamAction = when {
        mode == CamMode.VIDEO -> CamAction.ToggleRecording
        timerSeconds > 0 -> { countdown = timerSeconds; CamAction.Shoot }
        else -> CamAction.Shoot
    }

    private fun gallery(key: Key, digit: Int): CamAction = when {
        key == Key.LEFT || key == Key.UP -> { if (items.isNotEmpty()) { index = (index - 1 + items.size) % items.size; colour = false }; CamAction.ShowItem }
        key == Key.RIGHT || key == Key.DOWN -> { if (items.isNotEmpty()) { index = (index + 1) % items.size; colour = false }; CamAction.ShowItem }
        key == Key.OK || (key == Key.DIGIT && digit == 5) -> { colour = !colour; CamAction.ShowItem }
        key == Key.SOFT_RIGHT -> { inGallery = false; CamAction.Redraw }
        key == Key.END -> CamAction.Home
        else -> CamAction.None
    }
}
