package io.github.durkimat.phoneguard.core

/**
 * The configuration of an Open phone (docs/plan-two-editions.md): one permissive mode, with the few options a person may set on the phone itself.
 * The same Engine reads it as it reads a parent's configuration, so every feature that asks "what is allowed now" works unchanged and nothing is enforced.
 */
object OpenConfig {
    const val MODE = "open"

    /** One option: its key, a name for the screen, the values it cycles through and what each is called. */
    class Option(val key: String, val label: String, val values: List<String>, val names: List<String>, val default: String)

    val options = listOf(
        Option("sound", "Sound", listOf("normal", "vibrate", "silent"), listOf("Normal", "Vibrate", "Silent"), "normal"),
        Option("notes", "Notifications", listOf("show", "off"), listOf("Show", "Off"), "show"),
        Option("wa", "WhatsApp checks", listOf("open", "30", "60", "off"), listOf("When opened", "Every 30 min", "Every 60 min", "Off"), "open"),
        Option("camera", "Camera", listOf("on", "off"), listOf("On", "Off"), "on"),
        Option("radio", "Internet radio", listOf("any", "off"), listOf("On", "Off"), "any"),
        Option("sleep", "Power saver radios", listOf("on", "off"), listOf("On", "Off"), "on"),
    )

    fun valueOf(opts: Map<String, String>, o: Option): String = opts[o.key]?.takeIf { it in o.values } ?: o.default

    /** The value after [o]'s current one, stepping [dir] (1 or -1) and wrapping round. */
    fun step(opts: Map<String, String>, o: Option, dir: Int): String {
        val i = o.values.indexOf(valueOf(opts, o))
        return o.values[(i + dir + o.values.size) % o.values.size]
    }

    fun nameOf(opts: Map<String, String>, o: Option): String = o.names[o.values.indexOf(valueOf(opts, o))]

    fun build(opts: Map<String, String>): Config {
        fun v(k: String) = valueOf(opts, options.first { it.key == k })
        val m = ModeDef(
            MODE, "Open", allowAll = true, allow = emptySet(), gray = false, launcher = "nokia", touch = true, shade = true, kiosk = false,
            sound = v("sound"), inbound = true, wa = v("wa"), waApp = "auto", camera = v("camera") == "on", location = "off",
            notes = v("notes"), idleMin = 0, screenOffMin = 0, radio = v("radio"), radiosSleep = v("sleep") == "on",
        )
        return Config(
            version = 1, modes = mapOf(MODE to m), base = MODE, segments = emptyList(), windowEnd = 0, pattern = null,
            protectExtra = emptySet(), unprotect = emptySet(), restrictions = emptySet(),
            whatsapp = WaConfig(all = true, perMinute = 20, perDay = 1000),
            spotifyClientId = opts["spotifyClientId"].orEmpty(), spotifyRefresh = opts["spotifyRefresh"].orEmpty(),
        )
    }
}
