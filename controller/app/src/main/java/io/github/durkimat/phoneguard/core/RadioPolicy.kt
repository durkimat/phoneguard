package io.github.durkimat.phoneguard.core

/** What the child has chosen for each radio (kept apart from whether it is on at this moment). */
data class RadioIntent(val wifi: Boolean = true, val bt: Boolean = true, val data: Boolean = true)

/** Everything the radio decision needs for one moment. */
data class RadioInputs(
    /** The mode sleeps the radios (and is not live-tracking, which needs them on). */
    val sleepEnabled: Boolean,
    /** The screen is on and the phone unlocked: someone is using it. */
    val inUse: Boolean,
    /** Milliseconds since the screen went off (0 while it is on). */
    val offForMs: Long,
    /** How long the screen must be off before the radios rest. */
    val graceMs: Long,
    /** Something that needs the radios while locked: charging, a call, music playing, a download, just booted. */
    val hold: Boolean,
    /** A check-in window is open (a job is syncing, reporting a position or checking WhatsApp), and how long it has been open. */
    val windowOpen: Boolean, val windowAgeMs: Long,
    /** Wi-Fi has connected to a network (so the window need not fall back to mobile data). */
    val wifiConnected: Boolean,
    /** The mode reports the phone's position: windows then use a radio even if the child switched it off. */
    val locationReporting: Boolean,
    /** The phone has a SIM whose data we can switch (otherwise data is never touched). */
    val dataSupported: Boolean,
    val intent: RadioIntent,
)

/** The state each radio should be in now. */
data class RadioWanted(val wifi: Boolean, val bt: Boolean, val data: Boolean)

object RadioPolicy {
    /** How long a window waits for Wi-Fi before bringing mobile data up too. */
    const val WIFI_WAIT_MS = 20_000L

    fun sleeping(i: RadioInputs): Boolean = i.sleepEnabled && !i.inUse && !i.hold && i.offForMs >= i.graceMs

    fun decide(i: RadioInputs): RadioWanted {
        var wifi = i.intent.wifi; var bt = i.intent.bt; var data = i.intent.data
        if (sleeping(i)) { wifi = false; bt = false; data = false }
        if (i.windowOpen && !i.inUse) {
            // a check-in needs a way out: Wi-Fi first, and mobile data if Wi-Fi has not come up in time; Bluetooth is never needed
            if (i.intent.wifi || i.locationReporting) wifi = true
            if (i.dataSupported && (i.intent.data || i.locationReporting) && i.windowAgeMs >= WIFI_WAIT_MS && !i.wifiConnected) data = true
            if (i.dataSupported && !wifi && (i.intent.data || i.locationReporting)) data = true // Wi-Fi is switched off by the child and the mode does not need it: use data straight away
        }
        if (!i.dataSupported) data = i.intent.data // never touched
        return RadioWanted(wifi, bt, data)
    }

    /** When the next decision could change on its own (window fallback, end of the grace period), or null. */
    fun nextCheckMs(i: RadioInputs): Long? = when {
        !i.inUse && i.sleepEnabled && !i.hold && i.offForMs < i.graceMs -> i.graceMs - i.offForMs
        i.windowOpen && i.windowAgeMs < WIFI_WAIT_MS && !i.wifiConnected -> WIFI_WAIT_MS - i.windowAgeMs
        else -> null
    }
}
