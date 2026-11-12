package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Like [NavigationBackTest], for the screens that need data or a setting first: messages, WhatsApp, its settings and the Logs list. */
class NavigationDataTest {
    private val items = listOf(
        MenuItem("com.android.dialer", "Phone"), MenuItem("com.android.messaging", "Messages"), MenuItem("com.android.contacts", "Contacts"),
        MenuItem(CALLLOG_PKG, "Calls"), MenuItem(NOTES_PKG, "Notes"), MenuItem(WEATHER_PKG, "Weather"), MenuItem(GAMES_PKG, "Games"),
        MenuItem(APPS_PKG, "Apps"), MenuItem(CALC_PKG, "Calculator"), MenuItem(MUSIC_PKG, "Music"), MenuItem(SETTINGS_PKG, "Settings"), MenuItem(LOGSET_PKG, "Logs"),
    )

    private fun model(linked: Boolean = false): LauncherModel = LauncherModel(items, contactsApp = "com.android.contacts", messagesApp = "com.android.messaging", dialerApp = "com.android.dialer").also {
        it.waEnabled = true; it.waLinked = linked
        it.logItems = listOf(MenuItem(CALLLOG_PKG, "Calls"), MenuItem(NOTES_PKG, "Notes"), MenuItem(SETTINGS_PKG, "Settings"), MenuItem(RUNNING_PKG, "Running"))
    }

    private fun run(m: LauncherModel, path: String) {
        for (tok in path.split(" ").filter { it.isNotEmpty() }) {
            val (name, n) = tok.split("*").let { it[0] to (it.getOrNull(1)?.toInt() ?: 1) }
            repeat(n) { if (name.length == 2 && name[0] == 'D' && name[1].isDigit()) m.onKey(Key.DIGIT, name[1] - '0') else m.onKey(Key.valueOf(name)) }
        }
    }

    private fun badge(kind: BadgeKind): (LauncherModel) -> Unit = { m -> m.badges = listOf(Badge(kind, 1)); m.tap(240f, 500f) }

    private class Case(val name: String, val linked: Boolean = false, val setup: ((LauncherModel) -> Unit)? = null, val path: String = "")

    private val cases = listOf(
        Case("inbox", path = "OK DOWN OK OK"),
        Case("inboxFromBadge", setup = badge(BadgeKind.SMS)),
        Case("whatsapp", path = "OK DOWN OK DOWN OK"),
        Case("whatsappFromBadge", setup = badge(BadgeKind.WHATSAPP)),
        Case("pairFromWhatsapp", path = "OK DOWN OK DOWN OK OK"),
        Case("waSettings", path = "OK DOWN*10 OK DOWN*8 OK"),
        Case("pairFromWaSettings", path = "OK DOWN*10 OK DOWN*8 OK OK"),
        Case("waNames", linked = true, path = "OK DOWN*10 OK DOWN*8 OK DOWN OK"),
        Case("running", path = "OK DOWN*11 OK DOWN*3 OK"),
        Case("sentMessage", path = "OK DOWN OK DOWN*2 OK D5 DOWN D2 OK"),
        Case("writeBack", path = "OK DOWN OK DOWN*2 OK"),
        Case("sentFromChat", setup = { it.convos = listOf(Convo("0700", "Mum", listOf(Msg("hi", 1, false)), 0)) }, path = "OK DOWN OK OK OK OK OK D5 OK"),
    )

    private fun line(c: Case, back: Key): String {
        val m = model(c.linked)
        c.setup?.invoke(m); run(m, c.path)
        val before = "${m.screen}"
        m.onKey(back)
        return "${c.name}: $before -${back.name}-> ${m.screen}/${m.selected}"
    }

    @Test fun softRightGoesBackToWhereYouCameFrom() {
        assertEquals(GOLDEN_BACK.trim(), cases.joinToString("\n") { line(it, Key.SOFT_RIGHT) }.trim())
    }

    @Test fun endGoesHome() {
        assertEquals(GOLDEN_END.trim(), cases.joinToString("\n") { line(it, Key.END) }.trim())
    }

    private companion object {
        const val GOLDEN_BACK = """
inbox: INBOX -SOFT_RIGHT-> MESSAGES/0
inboxFromBadge: INBOX -SOFT_RIGHT-> HOME/0
whatsapp: WHATSAPP -SOFT_RIGHT-> MESSAGES/1
whatsappFromBadge: WHATSAPP -SOFT_RIGHT-> HOME/0
pairFromWhatsapp: WAPAIR -SOFT_RIGHT-> WHATSAPP/0
waSettings: WASET -SOFT_RIGHT-> SETTINGS/8
pairFromWaSettings: WAPAIR -SOFT_RIGHT-> WASET/0
waNames: WANAMES -SOFT_RIGHT-> WASET/1
running: RUNNING -SOFT_RIGHT-> LOGSET/3
sentMessage: MESSAGES -SOFT_RIGHT-> MENU/1
writeBack: WRITE -SOFT_RIGHT-> MESSAGES/2
sentFromChat: MESSAGES -SOFT_RIGHT-> MENU/1
"""
        const val GOLDEN_END = """
inbox: INBOX -END-> HOME/0
inboxFromBadge: INBOX -END-> HOME/0
whatsapp: WHATSAPP -END-> HOME/0
whatsappFromBadge: WHATSAPP -END-> HOME/0
pairFromWhatsapp: WAPAIR -END-> HOME/0
waSettings: WASET -END-> HOME/0
pairFromWaSettings: WAPAIR -END-> HOME/0
waNames: WANAMES -END-> HOME/0
running: RUNNING -END-> HOME/0
sentMessage: MESSAGES -END-> HOME/0
writeBack: WRITE -END-> HOME/0
sentFromChat: MESSAGES -END-> HOME/0
"""
    }
}
