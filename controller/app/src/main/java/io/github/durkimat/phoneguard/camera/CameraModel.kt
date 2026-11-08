package io.github.durkimat.phoneguard.camera

import io.github.durkimat.phoneguard.launcher.Key

/** One thing in the gallery: a photo or a video the camera has saved. */
data class MediaItem(val uri: String, val video: Boolean, val name: String, val time: Long)

enum class CamMode { PHOTO, VIDEO }
enum class FlashMode { OFF, AUTO, ON }
enum class Look { DITHER, TONES }

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
    var look = Look.DITHER
        private set
    var inGallery = false
        private set
    var recording = false
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

    /** The line of settings under the viewfinder. */
    val infoLine: String get() = buildString {
        append(if (zoom == zoom.toInt().toFloat()) "${zoom.toInt()}x" else "${zoom}x")
        if (mode == CamMode.PHOTO) append("  Flash ").append(flash.name.lowercase()) else append("  Torch ").append(if (flash == FlashMode.ON) "on" else "off")
        if (timerSeconds > 0) append("  Timer ${timerSeconds}s")
        append(if (look == Look.DITHER) "  Dots" else "  Tones")
    }

    fun setGallery(list: List<MediaItem>) { items = list; index = index.coerceIn(0, (list.size - 1).coerceAtLeast(0)); inGallery = true; colour = false }

    fun onKey(key: Key, digit: Int): CamAction {
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
        key == Key.DIGIT && digit == 4 -> { look = if (look == Look.DITHER) Look.TONES else Look.DITHER; CamAction.Redraw }
        key == Key.POUND -> if (recording) CamAction.None else { front = !front; zoomIndex = 0; CamAction.Rebind }
        key == Key.SOFT_LEFT -> shutter()
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
