package io.github.durkimat.phoneguard.core

/**
 * What to press next while the phone gets an app through the Aurora Store by itself ("storeinstall"). A pure decision, tested without a phone: it is given
 * what is on the screen (the front window's package and the words of its buttons and labels) and says what to do. It follows the reference script that
 * works over adb on the J36: Aurora's first-run pages (Anonymous, Next, Finish, Allow), then the app's store page (Install), then Android's installer (Install).
 */
class StoreDrive(private val update: Boolean = false) {
    sealed class Step {
        data class Tap(val label: String) : Step()
        /** Nothing to do yet: look again in a few seconds. */
        object Wait : Step()
        /** Open the app's store page (again). */
        object Open : Step()
        /** Aurora is asking for permission to install apps: ask the phone's helper to set it, then look again. */
        object AskInstallPermission : Step()
        data class Fail(val detail: String) : Step()
        /** Update mode: the store page shows the app as installed with no Update button, so there is nothing to do. */
        object UpToDate : Step()
    }

    private var steps = 0
    private var installTaps = 0
    private var onStorePage = false
    private var sawInstaller = false
    private var sawReady = false
    private var opens = 0
    private var homeSteps = 0
    private var grantSteps = 0
    private var retries = 0
    /** True once Aurora's own Install button has been pressed. */
    val pressedInstall: Boolean get() = installTaps > 0

    fun step(screenPkg: String, texts: List<String>): Step {
        steps++
        if (steps > MAX_STEPS) return Step.Fail(when {
            sawInstaller -> "Android did not let the install go ahead"
            installTaps > 0 -> "the download did not finish in time"
            else -> "the app store did not get as far as the Install button"
        })
        val low = texts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        fun has(w: String) = low.any { it == w }
        fun contains(w: String) = low.any { it.contains(w) }

        if (screenPkg.contains("packageinstaller")) {
            sawInstaller = true
            return when { has("install") -> Step.Tap("Install"); has("update") -> Step.Tap("Update"); else -> Step.Wait }
        }
        if (contains("installer permission") || (has("grant") && contains("permission"))) {
            grantSteps++
            return if (grantSteps > 6) Step.Fail("Android did not let the app store install apps (this phone's software may need updating)") else Step.AskInstallPermission
        }
        if (has("anonymous")) return Step.Tap("Anonymous")
        if (has("finish")) return Step.Tap("Finish")
        if (has("next")) return Step.Tap("Next")
        if (has("allow")) return Step.Tap("Allow")
        if (contains("continue to aurora") && has("continue")) return Step.Tap("Continue")
        if (contains("not found") || has("unavailable")) return Step.Fail("the app was not found in the store")
        if (update) {
            // an installed app's page offers Update when a newer version exists, otherwise Open/Uninstall; a plain Install means the store does not know it as installed
            if (has("update") && installTaps < 4) { installTaps++; onStorePage = true; return Step.Tap("Update") }
            if (installTaps == 0 && (has("open") || contains("uninstall")) && !has("install")) return Step.UpToDate
            if (installTaps == 0 && has("install")) return Step.Fail("the store does not list it as installed here")
        }
        if (!update && has("install") && installTaps < 4) { installTaps++; onStorePage = true; return Step.Tap("Install") }
        if (!onStorePage && installTaps == 0 && (has("retry") || contains("no internet") || contains("could not connect"))) {
            retries++
            return if (retries > 2) Step.Fail("the app store could not reach Google Play") else if (has("retry")) Step.Tap("Retry") else Step.Wait
        }
        val home = has("top charts") || has("for you") || has("search")
        if (home && !onStorePage && installTaps == 0) {
            if (!sawReady) { sawReady = true; opens++; return Step.Open } // the first-run pages are done: now open the app's page
            homeSteps++
            if (homeSteps >= 3 && opens < 3) { homeSteps = 0; opens++; return Step.Open }
        }
        return Step.Wait
    }

    companion object {
        /** About four minutes at one look every four seconds. */
        const val MAX_STEPS = 60
        const val LOOK_EVERY_MS = 4_000L
    }
}
