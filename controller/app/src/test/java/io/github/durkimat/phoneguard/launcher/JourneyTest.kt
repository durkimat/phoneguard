package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walks the launcher the way a person does: every key from every reachable screen, breadth first, on a phone with plenty of
 * data and with each main-menu style. Hand-picked paths ([NavigationBackTest]) only catch the routes someone thought of; this
 * catches the rest, and fails the day a new screen is added with no way in, no way out, or a Back that lands somewhere odd.
 */
class JourneyTest {
    private val items = listOf(
        MenuItem("com.android.dialer", "Phone"), MenuItem("com.android.messaging", "Messages"), MenuItem("com.android.contacts", "Contacts"),
        MenuItem(CALLLOG_PKG, "Calls"), MenuItem(NOTES_PKG, "Notes"), MenuItem(WEATHER_PKG, "Weather"), MenuItem(GAMES_PKG, "Games"),
        MenuItem(APPS_PKG, "Apps"), MenuItem(CALC_PKG, "Calculator"), MenuItem(MUSIC_PKG, "Music"), MenuItem(SETTINGS_PKG, "Settings"),
        MenuItem(LOGSET_PKG, "Logs"), MenuItem(RUNNING_PKG, "Running"),
    )

    private class Setup(val menuStyle: String, val whatsApp: Boolean, val theme: String = "green", val linked: Boolean = whatsApp) {
        override fun toString() = "menu=$menuStyle whatsapp=$whatsApp${if (whatsApp && !linked) "(unlinked)" else ""} theme=$theme"
    }

    private fun phone(s: Setup): LauncherModel = LauncherModel(items, contactsApp = "com.android.contacts", messagesApp = "com.android.messaging", dialerApp = "com.android.dialer").also {
        it.menuStyle = s.menuStyle; it.theme = s.theme
        it.androidSettings = true
        it.waEnabled = s.whatsApp; it.waLinked = s.whatsApp && s.linked
        it.contacts = listOf(Contact("Dad", listOf("0111")), Contact("Mum", listOf("0222", "0333")), Contact("Nan", listOf("0444")))
        val chat = listOf(Convo("0222", "Mum", listOf(Msg("Dinner at 6?", 1000, false), Msg("ok", 2000, true)), 1), Convo("0111", "Dad", listOf(Msg("Hi", 500, false)), 0))
        it.convos = chat
        if (s.whatsApp && s.linked) it.waConvos = chat
        it.calls = listOf(CallEntry("0222", "Mum", 1, 3000), CallEntry("0111", "Dad", 2, 2000), CallEntry("0999", "0999", 3, 1000))
        it.logItems = listOf(MenuItem(CALLLOG_PKG, "Calls"), MenuItem(NOTES_PKG, "Notes"), MenuItem(SETTINGS_PKG, "Settings"))
        it.apps = listOf(MenuItem("a.b", "Maps"), MenuItem("c.d", "Bank"))
        it.notes = listOf(io.github.durkimat.phoneguard.notes.NoteItem("k1", "a.b", "Maps", "Route", "Turn left", 1L, null))
        it.wifiOn = true; it.wifiNets = listOf(WifiNet("Home", 1, true, true, 3, true, true, "WPA2"), WifiNet("Cafe", -1, false, false, 2, false, true, ""))
        it.btOn = true; it.btDevs = listOf(BtDev("AA:BB", "Speaker", true, false), BtDev("CC:DD", "Watch", false, false))
        it.running = listOf(RunningApp("a.b", "Maps", "busy"))
        it.spSignedIn = true
        it.spPlaylists = listOf(io.github.durkimat.phoneguard.music.SpItem("spotify:pl:1", "Mix", "20 songs"))
        it.spResults = listOf(io.github.durkimat.phoneguard.music.SpItem("spotify:tr:1", "Song", "Band"))
        it.spDevices = listOf(io.github.durkimat.phoneguard.music.SpDevice("d1", "Kitchen", "Speaker", false, 50))
    }

    private val setups = listOf(Setup("list", false), Setup("grid", false), Setup("list", true), Setup("grid", true), Setup("list", false, "mac"), Setup("grid", true, "macdark"), Setup("list", true, linked = false))

    /** One key press: a [Key], and the digit when it is [Key.DIGIT]. */
    private data class Press(val key: Key, val digit: Int = -1) { override fun toString() = if (key == Key.DIGIT) "D$digit" else key.name }
    private val presses = (Key.values().filter { it != Key.DIGIT }.map { Press(it) } + (0..9).map { Press(Key.DIGIT, it) })
    /** The keys a person leans on to find their way: moving, choosing, and the two soft keys. */
    private val walking = listOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.OK, Key.SOFT_LEFT, Key.SOFT_RIGHT).map { Press(it) } + (1..9).map { Press(Key.DIGIT, it) }

    private data class Snap(val screen: Screen, val selected: Int, val depth: Int)
    private fun LauncherModel.snap() = Snap(screen, selected, depth)
    private fun LauncherModel.press(p: Press) = onKey(p.key, p.digit, 0L)

    private fun replay(s: Setup, path: List<Press>): LauncherModel = phone(s).also { m -> path.forEach { m.press(it) } }

    private class Map(val seen: LinkedHashMap<Snap, List<Press>>, val crashes: List<String>)

    /** Breadth first over (screen, row, depth), keeping the shortest key path to each. */
    private fun explore(s: Setup, levels: Int = 7, cap: Int = 6000): Map {
        val seen = LinkedHashMap<Snap, List<Press>>()
        val crashes = ArrayList<String>()
        val start = phone(s)
        seen[start.snap()] = emptyList()
        var frontier = listOf(emptyList<Press>())
        repeat(levels) {
            val next = ArrayList<List<Press>>()
            for (path in frontier) {
                for (p in walking) {
                    val m = try { replay(s, path) } catch (e: Throwable) { crashes += "$s: replaying ${path.joinToString(" ")}: $e"; continue }
                    try { m.press(p) } catch (e: Throwable) { crashes += "$s: ${(path + p).joinToString(" ")} on ${m.screen}: $e"; continue }
                    val sn = m.snap()
                    if (sn !in seen && seen.size < cap) { seen[sn] = path + p; next += (path + p) }
                }
            }
            frontier = next
        }
        return Map(seen, crashes)
    }

    private val explored by lazy { setups.associateWith { explore(it) } }
    private fun fmt(p: List<Press>) = p.joinToString(" ")

    @Test fun noKeyEverCrashesAnyReachableScreen() {
        // every one of the 22 keys, not only the walking ones, from every state found
        val bad = ArrayList<String>()
        for ((s, e) in explored) {
            bad += e.crashes
            for ((snap, path) in e.seen) for (p in presses) {
                try { replay(s, path).press(p) } catch (t: Throwable) { bad += "$s: ${fmt(path)} then $p on ${snap.screen}: $t" }
            }
        }
        assertTrue("keys that crash:\n" + bad.take(25).joinToString("\n"), bad.isEmpty())
    }

    @Test fun theWalkCoversPlentyOfGround() {
        for ((s, e) in explored) {
            println("journey $s: ${e.seen.size} states, ${e.seen.keys.map { it.screen }.toSet().size} screens")
            assertTrue("$s: only ${e.seen.size} states found", e.seen.size > 100)
        }
    }

    @Test fun theSelectedRowIsAlwaysOnTheScreen() {
        val bad = ArrayList<String>()
        for ((s, e) in explored) for ((snap, path) in e.seen) {
            val m = replay(s, path)
            val n = if (m.layout == Layout.LIST) m.rows.size else if (m.layout == Layout.GRID) m.items.size else continue
            if (n > 0 && snap.selected !in 0 until n) bad += "$s: ${fmt(path)} -> ${snap.screen} row ${snap.selected} of $n"
        }
        assertTrue("selection off the list:\n" + bad.take(25).joinToString("\n"), bad.isEmpty())
    }

    @Test fun backAlwaysGetsHomeInAFewPresses() {
        val bad = ArrayList<String>()
        for ((s, e) in explored) for ((snap, path) in e.seen) {
            val m = replay(s, path)
            var n = 0
            while (m.screen != Screen.HOME && n < snap.depth + 6) { m.press(Press(Key.SOFT_RIGHT)); n++ }
            if (m.screen != Screen.HOME) bad += "$s: ${fmt(path)} (${snap.screen}) is stuck on ${m.screen} after $n Backs"
        }
        assertTrue("screens Back cannot leave:\n" + bad.take(25).joinToString("\n"), bad.isEmpty())
    }

    @Test fun theRedKeyGoesHomeFromEverywhere() {
        val bad = ArrayList<String>()
        for ((s, e) in explored) for ((snap, path) in e.seen) {
            val m = replay(s, path); m.press(Press(Key.END))
            if (m.screen != Screen.HOME) bad += "$s: ${fmt(path)} (${snap.screen}) -END-> ${m.screen}"
        }
        assertTrue("End does not go home:\n" + bad.take(25).joinToString("\n"), bad.isEmpty())
    }

    @Test fun backUndoesAStepForwardAndLandsOnTheSameRow() {
        // Choosing something and pressing Back must put you where you were. With OK that is the very same row; a number key
        // moves the highlight to its own row first (like a Nokia menu), so it comes back to the same screen on that row.
        val bad = ArrayList<String>()
        for ((s, e) in explored) for ((snap, path) in e.seen) for (p in listOf(Press(Key.OK)) + (1..9).map { Press(Key.DIGIT, it) }) {
            if (snap.screen == Screen.HOME && p.key == Key.DIGIT) continue // a digit on Home starts a number, it does not open a screen
            val m = replay(s, path); m.press(p)
            if (m.depth != snap.depth + 1) continue // not a step into a new screen (a setting was chosen, a game started, a key typed...)
            val into = m.screen
            m.press(Press(Key.SOFT_RIGHT))
            val ok = if (p.key == Key.OK) m.snap() == snap else m.screen == snap.screen && m.depth == snap.depth
            if (!ok) bad += "$s: ${fmt(path)} (${snap.screen}/${snap.selected}) -$p-> $into, Back -> ${m.screen}/${m.selected}"
        }
        assertTrue("Back does not undo the step in:\n" + bad.take(30).joinToString("\n"), bad.isEmpty())
    }

    @Test fun aNumberKeyDoesWhatMovingThereAndPressingOkDoes() {
        val bad = ArrayList<String>()
        for ((s, e) in explored) for ((snap, path) in e.seen) {
            val probe = replay(s, path)
            if (probe.layout != Layout.LIST || probe.rows.isEmpty() || snap.screen == Screen.MENU) continue
            for (d in 1..minOf(9, probe.rows.size)) {
                val a = replay(s, path); a.press(Press(Key.DIGIT, d))
                if (a.snap() == snap) continue // the screen does not use number shortcuts
                val b = replay(s, path); repeat(snap.selected) { b.press(Press(Key.UP)) }; repeat(d - 1) { b.press(Press(Key.DOWN)) }; b.press(Press(Key.OK))
                if (a.screen != b.screen) bad += "$s: ${fmt(path)} (${snap.screen}) digit $d -> ${a.screen} but row $d + OK -> ${b.screen}"
            }
        }
        assertTrue("number keys differ from Down+OK:\n" + bad.take(25).joinToString("\n"), bad.isEmpty())
    }

    @Test fun everyScreenCanBeReached() {
        val reached = explored.values.flatMap { it.seen.keys.map { s -> s.screen } }.toSet()
        // a live call, and typed text sent to Spotify, which the keys alone cannot produce
        val needsSomethingElse = setOf(Screen.CALL, Screen.CALLMENU, Screen.SEARCHRES)
        val missing = Screen.values().filter { it !in reached && it !in needsSomethingElse }
        assertEquals("screens no key path reaches (add data to the test phone or fix the route): $missing", emptyList<Screen>(), missing)
    }

    @Test fun everyChoiceInSettingsEndsOnASettingsScreenAndBackComesHome() {
        // the list a person walks: Menu > Settings > each row > Back > Back > Back is Home, in every menu style and theme
        for (s in setups) {
            val m = phone(s)
            m.press(Press(Key.OK))
            val settingsRow = m.items.indexOfFirst { it.pkg == SETTINGS_PKG }
            repeat(settingsRow) { m.press(Press(Key.DOWN)) }; m.press(Press(Key.OK))
            if (m.screen != Screen.SETTINGS) { // grid menus step with Left/Right and Down moves a row of tiles
                val g = phone(s); g.press(Press(Key.OK)); repeat(settingsRow) { g.press(Press(Key.RIGHT)) }; g.press(Press(Key.OK))
                assertEquals("$s: Menu > Settings", Screen.SETTINGS, g.screen)
            }
        }
    }
}
