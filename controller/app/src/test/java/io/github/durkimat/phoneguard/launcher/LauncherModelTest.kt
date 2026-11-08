package io.github.durkimat.phoneguard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherModelTest {
    private val items = listOf(MenuItem("p.phone", "Phone"), MenuItem("p.msg", "Messages"), MenuItem("p.wa", "WhatsApp"))
    private fun model() = LauncherModel(items, contactsApp = "p.contacts")

    @Test fun startsOnHome() { assertEquals(Screen.HOME, model().screen) }

    @Test fun okLeftKeyAndDpadLeftOpenTheMenuAtTheTop() {
        for (k in listOf(Key.OK, Key.LEFT, Key.SOFT_LEFT)) {
            val m = model()
            assertEquals(Action.Redraw, m.onKey(k))
            assertEquals(Screen.MENU, m.screen); assertEquals(0, m.selected)
        }
    }

    @Test fun rightKeyAndDpadRightOnHomeOpenContacts() {
        for (k in listOf(Key.RIGHT, Key.SOFT_RIGHT)) { val m = model(); assertEquals(Action.Redraw, m.onKey(k)); assertEquals(Screen.CONTACTS, m.screen) }
    }

    @Test fun theTwoKeysUnderTheScreenWorkLikeANokia() {
        val m = model()
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)); assertEquals(Screen.CONTACTS, m.screen) // first press on Home: Contacts
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen) // second press: back
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_LEFT)) // left key: the menu
        assertEquals(Screen.MENU, m.screen)
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)) // right key in the menu: Back
        assertEquals(Screen.HOME, m.screen)
    }

    @Test fun digitsOnHomeStartDialling() {
        val m = model()
        assertEquals(Action.Redraw, m.onKey(Key.DIGIT, 7)); assertEquals(Screen.DIAL, m.screen); assertEquals("7", m.dialNumber)
        m.onKey(Key.END); assertEquals(Screen.HOME, m.screen)
        m.onKey(Key.STAR); assertEquals("*", m.dialNumber); m.onKey(Key.END)
        m.onKey(Key.POUND); assertEquals("#", m.dialNumber); m.onKey(Key.END)
        m.onKey(Key.CALL); assertEquals(Screen.CALLLOG, m.screen)
    }

    @Test fun menuMovesAndWraps() {
        val m = model(); m.onKey(Key.OK)
        m.onKey(Key.DOWN); assertEquals(1, m.selected)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); assertEquals(0, m.selected) // wrapped past the end
        m.onKey(Key.UP); assertEquals(2, m.selected) // and back round the top
    }

    @Test fun menuLaunchesTheSelectedEntry() {
        val m = model(); m.onKey(Key.OK); m.onKey(Key.DOWN)
        assertEquals(Action.Launch("p.msg"), m.onKey(Key.OK))
        assertEquals(Action.Launch("p.msg"), m.onKey(Key.LEFT)) // the "Select" label is D-pad Left
        assertEquals(Action.Launch("p.msg"), m.onKey(Key.SOFT_LEFT)) // and the left key under the screen
    }

    @Test fun numberKeysJumpToEntries() {
        val m = model(); m.onKey(Key.OK)
        assertEquals(Action.Launch("p.wa"), m.onKey(Key.DIGIT, 3))
        assertEquals(Action.None, m.onKey(Key.DIGIT, 9)) // no ninth entry
        assertEquals(Action.None, m.onKey(Key.DIGIT, 0))
    }

    @Test fun backAndDpadRightReturnHome() {
        for (k in listOf(Key.SOFT_RIGHT, Key.RIGHT)) {
            val m = model(); m.onKey(Key.OK); m.onKey(Key.DOWN)
            assertEquals(Action.Redraw, m.onKey(k))
            assertEquals(Screen.HOME, m.screen); assertEquals(0, m.selected)
        }
    }

    @Test fun emptyMenuDoesNotCrash() {
        val m = LauncherModel(emptyList(), null); m.onKey(Key.OK)
        assertEquals(Action.None, m.onKey(Key.DOWN)); assertEquals(Action.None, m.onKey(Key.OK))
    }

    @Test fun shrinkingTheListKeepsTheSelectionValid() {
        val m = model(); m.onKey(Key.OK); m.onKey(Key.DOWN); m.onKey(Key.DOWN)
        m.setItems(items.take(1)); assertTrue(m.selected in 0..0)
    }

    private fun withSettings() = LauncherModel(items + MenuItem(SETTINGS_PKG, "Settings"), contactsApp = null)

    @Test fun settingsEntryOpensTheLaunchersOwnSettingsNotAnApp() {
        val m = withSettings(); m.onKey(Key.OK)
        m.onKey(Key.UP) // wraps to the last entry, Settings
        assertEquals(Action.Redraw, m.onKey(Key.OK))
        assertEquals(Screen.SETTINGS, m.screen); assertEquals("Settings", m.title); assertEquals(listOf("Theme", "Font", "Predictive text", "Speakerphone"), m.rows)
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)) // Back returns to the menu, on the Settings entry
        assertEquals(Screen.MENU, m.screen); assertEquals(3, m.selected)
    }

    @Test fun choosingAThemeSavesItAndMarksIt() {
        val m = withSettings(); m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK); m.onKey(Key.OK)
        assertEquals(Screen.THEME, m.screen)
        assertEquals(listOf("Green *", "Grey", "Amber", "Orange"), m.rows)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN)
        assertEquals(Action.SaveTheme("amber"), m.onKey(Key.SOFT_LEFT))
        assertEquals("amber", m.theme); assertEquals(Screen.SETTINGS, m.screen)
    }

    @Test fun themeBackLeavesTheThemeAlone() {
        val m = withSettings(); m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK); m.onKey(Key.OK)
        m.onKey(Key.DOWN)
        assertEquals(Action.Redraw, m.onKey(Key.RIGHT))
        assertEquals("green", m.theme); assertEquals(Screen.SETTINGS, m.screen)
    }

    @Test fun unknownThemeFallsBackToGreen() { assertEquals("green", Themes.byId("nope").id) }

    @Test fun fontIsChosenFromSettingsLikeTheTheme() {
        val m = withSettings(); m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK)
        assertEquals(listOf("Theme", "Font", "Predictive text", "Speakerphone"), m.rows)
        m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals(Screen.FONT, m.screen); assertEquals(listOf("Monospace *", "Sans-serif", "Pixel VT323", "Pixelify"), m.rows)
        m.onKey(Key.DOWN)
        assertEquals(Action.SaveFont("sans"), m.onKey(Key.OK))
        assertEquals("sans", m.font); assertEquals(Screen.SETTINGS, m.screen)
    }

    private fun withContacts() = model().also { it.contacts = listOf(Contact("Dad", listOf("0111")), Contact("Mum", listOf("0222", "0333"))) }

    @Test fun contactsListShowsNamesAndOpensDetail() {
        val m = withContacts(); m.onKey(Key.SOFT_RIGHT)
        assertEquals(listOf("Dad", "Mum"), m.rows)
        m.onKey(Key.DOWN); assertEquals(Action.Redraw, m.onKey(Key.OK))
        assertEquals(Screen.CONTACT, m.screen); assertEquals("Mum", m.title)
        assertEquals(listOf("Call 0222", "Call 0333", "Message"), m.rows)
    }

    @Test fun contactDetailCallsMessagesAndGoesBack() {
        val m = withContacts(); m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals(Action.Call("0222"), m.onKey(Key.OK))
        m.onKey(Key.DOWN); assertEquals(Action.Call("0333"), m.onKey(Key.SOFT_LEFT))
        m.onKey(Key.DOWN); assertEquals(Action.Redraw, m.onKey(Key.LEFT)) // Message opens the write screen with the number filled in
        assertEquals(Screen.WRITE, m.screen); assertEquals("0222", m.writeTo); assertEquals(1, m.writeField)
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)); assertEquals(Screen.CONTACT, m.screen) // Clear on an empty message goes back
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)); assertEquals(Screen.CONTACTS, m.screen); assertEquals(1, m.selected)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen)
    }

    @Test fun contactsFromTheMenuReturnToTheMenu() {
        val m = LauncherModel(listOf(MenuItem("com.android.contacts", "Contacts")), "com.android.contacts"); m.onKey(Key.OK)
        assertEquals(Action.Redraw, m.onKey(Key.OK)); assertEquals(Screen.CONTACTS, m.screen)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MENU, m.screen)
    }

    @Test fun greenKeyCallsTheSelectedNumber() {
        val m = withContacts(); m.onKey(Key.SOFT_RIGHT); m.onKey(Key.OK)
        assertEquals(Action.Call("0111"), m.onKey(Key.CALL))
    }

    // ---- Messages
    private fun msgs(): LauncherModel {
        val m = LauncherModel(listOf(MenuItem("com.android.messaging", "Messages")), "com.android.contacts", "com.android.messaging")
        m.contacts = listOf(Contact("Dad", listOf("0111")), Contact("Mum", listOf("0222")))
        m.convos = listOf(
            Convo("0222", "Mum", listOf(Msg("Dinner at 6?", 1000, false), Msg("ok", 2000, true), Msg("Can you get milk on the way home please", 3000, false)), 1),
            Convo("0111", "Dad", listOf(Msg("Hi", 500, false)), 0),
        )
        m.wrapper = { it.chunked(12) }
        m.pageLines = 2
        return m
    }
    private fun open(m: LauncherModel) { m.onKey(Key.OK); m.onKey(Key.OK) } // menu, then the Messages entry

    @Test fun messagesEntryOpensOurOwnMessagesScreen() {
        val m = msgs(); open(m)
        assertEquals(Screen.MESSAGES, m.screen); assertEquals(listOf("Inbox", "Write message"), m.rows)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MENU, m.screen)
    }

    @Test fun inboxMarksUnreadAndOpensTheNewestMessage() {
        val m = msgs(); open(m); m.onKey(Key.OK)
        assertEquals(Screen.INBOX, m.screen); assertEquals(listOf("* Mum", "Dad"), m.rows)
        m.onKey(Key.OK)
        assertEquals(Screen.CHAT, m.screen); assertEquals("Mum", m.title)
        // the chat is a list: Reply first, then the messages newest first
        assertEquals(listOf("Reply", "< Can you get milk on the way home please", "> ok", "< Dinner at 6?"), m.rows)
        assertEquals(1, m.selected) // opens on the newest message
        assertEquals("Read" to "Back", m.softKeys)
        m.onKey(Key.OK)
        assertEquals(Screen.THREAD, m.screen); assertEquals("Mum", m.title); assertEquals(2, m.selected)
    }

    @Test fun anyMessageInTheListCanBeReadAndBackReturnsToIt() {
        val m = msgs(); open(m); m.onKey(Key.OK); m.onKey(Key.OK) // the chat list
        m.onKey(Key.DOWN); m.onKey(Key.DOWN) // the oldest message, third row
        assertEquals(3, m.selected); m.onKey(Key.OK)
        assertEquals(Screen.THREAD, m.screen); assertEquals(0, m.selected); assertEquals("Dinner at 6?", m.convoText())
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.CHAT, m.screen); assertEquals(3, m.selected) // back on the same row
        m.onKey(Key.UP); m.onKey(Key.UP); m.onKey(Key.UP); assertEquals("Reply" to "Back", m.softKeys) // Reply is the first row
        assertEquals(Action.Redraw, m.onKey(Key.OK)); assertEquals(Screen.WRITE, m.screen); assertEquals("0222", m.writeTo)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.CHAT, m.screen) // Clear on an empty reply returns to the list
    }

    @Test fun backFromTheChatListGoesToWhereItCameFrom() {
        val sms = msgs(); open(sms); sms.onKey(Key.OK); sms.onKey(Key.OK); sms.onKey(Key.SOFT_RIGHT); assertEquals(Screen.INBOX, sms.screen)
        val w = wa(); open(w); w.onKey(Key.DIGIT, 2); w.onKey(Key.OK); w.onKey(Key.SOFT_RIGHT); assertEquals(Screen.WHATSAPP, w.screen)
    }

    @Test fun conversationScrollsLongMessagesThenMovesToTheNextMessage() {
        val m = msgs(); open(m); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.OK) // newest: 40 chars = 4 lines of 12, 2 per page
        assertEquals(0, m.scroll)
        m.onKey(Key.DOWN); assertEquals(2, m.scroll)
        assertEquals(Action.None, m.onKey(Key.DOWN)) // last message, last page
        m.onKey(Key.UP); assertEquals(0, m.scroll)
        m.onKey(Key.UP); assertEquals(1, m.selected); assertEquals(0, m.scroll) // previous message
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)); assertEquals(Screen.CHAT, m.screen); m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.INBOX, m.screen)
    }

    @Test fun replyOpensWriteWithTheNumberAndGreenKeyCalls() {
        val m = msgs(); open(m); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.OK)
        assertEquals(Action.Call("0222"), m.onKey(Key.CALL))
        m.onKey(Key.SOFT_LEFT)
        assertEquals(Screen.WRITE, m.screen); assertEquals("0222", m.writeTo); assertEquals(1, m.writeField)
        assertEquals("Send" to "Clear", m.softKeys)
    }

    @Test fun multiTapCyclesLettersAndCapitalisesTheStart() {
        val m = msgs(); open(m); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.SOFT_LEFT)
        m.onKey(Key.DIGIT, 4, 0); m.onKey(Key.DIGIT, 4, 300); m.onKey(Key.DIGIT, 4, 600) // g h i -> "I"
        assertEquals("I", m.writeText)
        m.onKey(Key.DIGIT, 4, 2000); assertEquals("Ig", m.writeText) // a pause starts a new letter, now lower case
    }

    @Test fun typingFlowSendsAndCursorEditingWorks() {
        val m = msgs(); open(m); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.SOFT_LEFT)
        m.onKey(Key.DIGIT, 4, 0); m.onKey(Key.DIGIT, 4, 100) // "H"
        m.onKey(Key.DIGIT, 4, 2000); m.onKey(Key.DIGIT, 4, 2100); m.onKey(Key.DIGIT, 4, 2200) // "i"
        assertEquals("Hi", m.writeText)
        m.onKey(Key.LEFT); m.onKey(Key.DIGIT, 0, 5000) // a space before the "i"
        assertEquals("H i", m.writeText)
        m.onKey(Key.SOFT_RIGHT); assertEquals("Hi", m.writeText) // Clear deletes before the cursor
        assertEquals(Action.Send("0222", "Hi"), m.onKey(Key.OK))
        assertEquals(Screen.MESSAGES, m.screen); assertEquals("Message sent", m.notice)
        m.onKey(Key.DOWN); assertEquals(null, m.notice)
    }

    @Test fun poundCyclesModesAndNumbersTypeDigits() {
        val m = msgs(); open(m); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.SOFT_LEFT)
        assertEquals("Abc", m.modeName); m.onKey(Key.POUND); assertEquals("abc", m.modeName)
        m.onKey(Key.DIGIT, 2, 0); assertEquals("a", m.writeText)
        m.onKey(Key.POUND); m.onKey(Key.POUND); assertEquals("123", m.modeName)
        m.onKey(Key.DIGIT, 7, 100); assertEquals("a7", m.writeText)
    }

    @Test fun newMessagePicksARecipientFromContacts() {
        val m = msgs(); open(m); m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals(Screen.WRITE, m.screen); assertEquals(0, m.writeField); assertEquals("Contacts" to "Clear", m.softKeys)
        m.onKey(Key.OK); assertEquals(Screen.CONTACTS, m.screen)
        m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals(Screen.WRITE, m.screen); assertEquals("0222", m.writeTo); assertEquals(1, m.writeField)
    }

    @Test fun recipientCanBeTypedAsDigitsAndClearedBackOut() {
        val m = msgs(); open(m); m.onKey(Key.DOWN); m.onKey(Key.OK)
        m.onKey(Key.DIGIT, 0); m.onKey(Key.DIGIT, 7); assertEquals("07", m.writeTo)
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.SOFT_RIGHT); assertEquals("", m.writeTo)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MESSAGES, m.screen) // Clear on empty goes back
    }

    // ---- Predictive text
    private fun t9(): LauncherModel {
        val m = msgs()
        m.predictor = WordList(listOf("the", "good", "home", "gone", "hood", "hello", "I", "don't", "London", "goodbye", "is", "at"))
        m.predictiveOn = true
        open(m); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.SOFT_LEFT) // reply to Mum: write screen
        return m
    }
    private fun press(m: LauncherModel, vararg d: Int) { d.forEach { m.onKey(Key.DIGIT, it, 0) } }

    @Test fun t9ModesAreOfferedOnlyWithADictionary() {
        val m = t9(); assertEquals("T9 Abc", m.modeName)
        m.onKey(Key.POUND); assertEquals("T9 abc", m.modeName)
        m.onKey(Key.POUND); m.onKey(Key.POUND); assertEquals("Abc", m.modeName)
        val plain = msgs(); open(plain); plain.onKey(Key.OK); plain.onKey(Key.OK); plain.onKey(Key.OK); plain.onKey(Key.SOFT_LEFT)
        assertEquals("Abc", plain.modeName)
    }

    @Test fun oneKeyPerLetterShowsTheLikeliestWordAndStarStepsThroughTheRest() {
        val m = t9(); press(m, 4, 6, 6, 3)
        assertEquals("Good", m.writeText); assertEquals(0..3, m.composingRange) // capital at the start of a message
        m.onKey(Key.STAR); assertEquals("Home", m.writeText)
        m.onKey(Key.STAR); assertEquals("Gone", m.writeText)
        m.onKey(Key.STAR); m.onKey(Key.STAR); assertEquals("Good", m.writeText) // wraps round
    }

    @Test fun zeroAcceptsTheWordAndAddsASpaceThenTheNextWordIsLowerCase() {
        val m = t9(); press(m, 4, 6, 6, 3, 0)
        assertEquals("Good ", m.writeText); assertEquals(null, m.composingRange)
        press(m, 4, 3, 5, 5, 6)
        assertEquals("Good hello", m.writeText)
    }

    @Test fun clearRemovesOneKeyAtATimeThenTheWord() {
        val m = t9(); press(m, 4, 6, 6, 3)
        m.onKey(Key.SOFT_RIGHT); assertEquals("Goo", m.writeText) // the 466 matches, shown as the start of a longer word
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.SOFT_RIGHT); assertEquals("I", m.writeText) // one key left: the one-letter word
        m.onKey(Key.SOFT_RIGHT); assertEquals("", m.writeText)
        assertEquals(null, m.composingRange)
    }

    @Test fun anUnknownSequenceStillShowsLettersSoTheUserIsNeverStuck() {
        val m = t9(); press(m, 2, 2, 2, 2); assertEquals("Aaaa", m.writeText)
    }

    @Test fun contractionsAndNamesKeepTheirShape() {
        val m = t9(); press(m, 3, 6, 6, 8); assertEquals("Don't", m.writeText)
        m.onKey(Key.POUND); m.onKey(Key.POUND); m.onKey(Key.POUND) // multi-tap Abc: leave T9
        m.onKey(Key.POUND); assertEquals("abc", m.modeName)
    }

    @Test fun cursorKeysAndSendAcceptTheWordFirst() {
        val m = t9(); press(m, 4, 6, 6, 3)
        assertEquals(Action.Send("0222", "Good"), m.onKey(Key.OK)) // sending takes the word as shown
    }

    @Test fun oneKeyAfterAWordStartsMultiTapPunctuation() {
        val m = t9(); press(m, 4, 6, 6, 3); m.onKey(Key.DIGIT, 1, 5000)
        assertEquals("Good.", m.writeText); assertEquals(null, m.composingRange)
    }

    @Test fun predictiveCanBeSwitchedOffInSettings() {
        val m = t9(); m.onKey(Key.SOFT_RIGHT) // leave write
        assertEquals(Screen.THREAD, m.screen)
    }

    // ---- Calculator and call log
    private fun withTools(): LauncherModel {
        val m = LauncherModel(listOf(MenuItem(CALLLOG_PKG, "Call log"), MenuItem(CALC_PKG, "Calculator")))
        m.calls = listOf(CallEntry("0222", "Mum", 1, 3000), CallEntry("0111", "Dad", 2, 2000), CallEntry("0999", "0999", 3, 1000))
        return m
    }

    @Test fun greenKeyOpensTheCallLogAndMarksDirection() {
        val m = withTools(); m.onKey(Key.CALL)
        assertEquals(Screen.CALLLOG, m.screen); assertEquals(listOf("< Mum", "> Dad", "! 0999"), m.rows)
        assertEquals(Action.Call("0222"), m.onKey(Key.CALL)) // green key again rings the highlighted one
        m.onKey(Key.DOWN); assertEquals(Action.Call("0111"), m.onKey(Key.SOFT_LEFT))
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen)
    }

    @Test fun callLogFromTheMenuReturnsToTheMenu() {
        val m = withTools(); m.onKey(Key.OK); m.onKey(Key.OK)
        assertEquals(Screen.CALLLOG, m.screen); m.onKey(Key.RIGHT); assertEquals(Screen.MENU, m.screen)
    }

    @Test fun calculatorKeysDoTheSumAndClearLeavesWhenEmpty() {
        val m = withTools(); m.onKey(Key.OK); m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals(Screen.CALC, m.screen)
        m.onKey(Key.DIGIT, 1); m.onKey(Key.DIGIT, 2); m.onKey(Key.POUND); m.onKey(Key.DIGIT, 5)
        m.onKey(Key.STAR); assertEquals("12.5 +", m.calc.pending)
        m.onKey(Key.STAR); m.onKey(Key.STAR); m.onKey(Key.STAR); assertEquals("12.5 /", m.calc.pending)
        m.onKey(Key.DIGIT, 5); m.onKey(Key.SOFT_LEFT); assertEquals("2.5", m.calc.display)
        m.onKey(Key.UP); m.onKey(Key.DIGIT, 1); m.onKey(Key.LEFT); assertEquals("3.5", m.calc.display)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.CALC, m.screen) // clears the answer
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MENU, m.screen)
    }

    @Test fun dialScreenTypesCallsAndClears() {
        val m = LauncherModel(emptyList()); m.contacts = listOf(Contact("Mum", listOf("+44 7700 900 222")))
        m.onKey(Key.DIGIT, 0); m.onKey(Key.DIGIT, 7); m.onKey(Key.DIGIT, 7); m.onKey(Key.DIGIT, 0); m.onKey(Key.DIGIT, 0)
        assertEquals("07700", m.dialNumber); assertEquals(null, m.dialName) // too early to match
        "900222".forEach { m.onKey(Key.DIGIT, it - '0') }
        assertEquals("Mum", m.dialName) // the 07... form matches the +44 form
        assertEquals(Action.PlaceCall("07700900222"), m.onKey(Key.CALL))
        assertEquals(Action.PlaceCall("07700900222"), m.onKey(Key.SOFT_LEFT))
        m.onKey(Key.SOFT_RIGHT); assertEquals("0770090022", m.dialNumber)
        repeat(10) { m.onKey(Key.SOFT_RIGHT) }; assertEquals(Screen.HOME, m.screen) // clearing the last digit goes home
    }

    @Test fun starTwiceMakesAPlusAndTheGreenKeyOnAnEmptyNumberShowsRecentCalls() {
        val m = LauncherModel(emptyList()); m.onKey(Key.STAR); m.onKey(Key.STAR); assertEquals("+", m.dialNumber)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.HOME, m.screen)
        m.onKey(Key.CALL); assertEquals(Screen.CALLLOG, m.screen)
    }

    // ---- Call screens
    private fun inCall(phase: CallPhase, muted: Boolean = false, speaker: Boolean = false): LauncherModel {
        val m = LauncherModel(emptyList()); m.setCall(CallInfo("0222", "Mum", phase, startedAt = 1000, muted = muted, speaker = speaker)); return m
    }

    @Test fun incomingCallAnswerAndRejectKeys() {
        for (k in listOf(Key.SOFT_LEFT, Key.LEFT, Key.OK, Key.CALL)) assertEquals(Action.Answer, inCall(CallPhase.RINGING).onKey(k))
        for (k in listOf(Key.SOFT_RIGHT, Key.RIGHT, Key.END)) assertEquals(Action.Reject, inCall(CallPhase.RINGING).onKey(k))
        assertEquals("Answer" to "Reject", inCall(CallPhase.RINGING).softKeys)
        assertEquals(Action.None, inCall(CallPhase.RINGING).onKey(Key.DIGIT, 5)) // a stray key does not answer
    }

    @Test fun callingScreenHasSpeakerAndEnd() {
        val m = inCall(CallPhase.DIALING)
        assertEquals("Speaker" to "End", m.softKeys)
        assertEquals(Action.ToggleSpeaker, m.onKey(Key.SOFT_LEFT)); assertEquals(Action.EndCall, m.onKey(Key.SOFT_RIGHT))
    }

    @Test fun inCallKeysSendTonesAdjustVolumeAndOpenOptions() {
        val m = inCall(CallPhase.ACTIVE)
        assertEquals(Action.Dtmf('5'), m.onKey(Key.DIGIT, 5)); assertEquals(Action.Dtmf('*'), m.onKey(Key.STAR)); assertEquals(Action.Dtmf('#'), m.onKey(Key.POUND))
        assertEquals(Action.CallVolume(1), m.onKey(Key.UP)); assertEquals(Action.CallVolume(-1), m.onKey(Key.DOWN))
        assertEquals(Action.EndCall, m.onKey(Key.SOFT_RIGHT))
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_LEFT)); assertEquals(Screen.CALLMENU, m.screen)
        assertEquals(listOf("Speaker on", "Mute"), m.rows)
    }

    @Test fun optionsToggleSpeakerAndMuteThenReturnToTheCall() {
        val m = inCall(CallPhase.ACTIVE, muted = true, speaker = true); m.onKey(Key.SOFT_LEFT)
        assertEquals(listOf("Speaker off", "Unmute"), m.rows)
        assertEquals(Action.ToggleSpeaker, m.onKey(Key.OK)); assertEquals(Screen.CALL, m.screen)
        m.onKey(Key.SOFT_LEFT); m.onKey(Key.DOWN); assertEquals(Action.ToggleMute, m.onKey(Key.OK))
        m.onKey(Key.SOFT_LEFT); assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)); assertEquals(Screen.CALL, m.screen) // Back
    }

    @Test fun anEndedCallClosesOnAnyKeyAndNoCallGoesHome() {
        val m = inCall(CallPhase.ENDED); assertEquals(Action.CloseCall, m.onKey(Key.DIGIT, 1))
        m.setCall(null); assertEquals(Screen.HOME, m.screen)
    }

    @Test fun theOptionsMenuStaysOpenWhileTheCallStateUpdates() {
        val m = inCall(CallPhase.ACTIVE); m.onKey(Key.SOFT_LEFT)
        m.setCall(CallInfo("0222", "Mum", CallPhase.ACTIVE, startedAt = 1000, muted = true)); assertEquals(Screen.CALLMENU, m.screen)
        m.setCall(CallInfo("0222", "Mum", CallPhase.ENDED, seconds = 9)); assertEquals(Screen.CALL, m.screen)
    }

    @Test fun startDialFillsTheNumber() {
        val m = LauncherModel(emptyList()); m.startDial("tel 07700-900 111".replace("tel ", ""))
        assertEquals(Screen.DIAL, m.screen); assertEquals("07700900111", m.dialNumber)
    }

    @Test fun numberKeysChooseNumberedEntriesInEveryList() {
        // call options: 1 is speaker, 2 is mute
        val c = LauncherModel(emptyList()); c.setCall(CallInfo("0222", "Mum", CallPhase.ACTIVE, startedAt = 1)); c.onKey(Key.SOFT_LEFT)
        assertEquals(Action.ToggleMute, c.onKey(Key.DIGIT, 2)); assertEquals(Screen.CALL, c.screen)
        c.onKey(Key.SOFT_LEFT); assertEquals(Action.ToggleSpeaker, c.onKey(Key.DIGIT, 1))
        // theme: 3 picks the third theme and saves it
        val m = withSettings(); m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK); m.onKey(Key.DIGIT, 1)
        assertEquals(Screen.THEME, m.screen); assertEquals(Action.SaveTheme("amber"), m.onKey(Key.DIGIT, 3))
        // a number with no entry does nothing
        val s = withSettings(); s.onKey(Key.OK); s.onKey(Key.UP); s.onKey(Key.OK); assertEquals(Action.None, s.onKey(Key.DIGIT, 9))
        // the call log: 2 rings (opens the dial screen with) the second entry
        val l = withTools(); l.onKey(Key.CALL); assertEquals(Action.Call("0111"), l.onKey(Key.DIGIT, 2))
    }

    @Test fun hidingTheSpeakerRemovesItFromCallsAndSettingsSavesIt() {
        val s = withSettings(); s.onKey(Key.OK); s.onKey(Key.UP); s.onKey(Key.OK)
        assertEquals(Action.Redraw, s.onKey(Key.DIGIT, 4)); assertEquals(Screen.SPEAKER, s.screen)
        assertEquals(listOf("Show *", "Hide"), s.rows)
        assertEquals(Action.SaveSpeaker(false), s.onKey(Key.DIGIT, 2)); assertEquals(false, s.speakerShown); assertEquals(Screen.SETTINGS, s.screen)

        val c = LauncherModel(emptyList()); c.speakerShown = false
        c.setCall(CallInfo("0222", "Mum", CallPhase.DIALING)); assertEquals("" to "End", c.softKeys); assertEquals(Action.None, c.onKey(Key.SOFT_LEFT))
        c.setCall(CallInfo("0222", "Mum", CallPhase.ACTIVE, startedAt = 1)); c.onKey(Key.SOFT_LEFT)
        assertEquals(listOf("Mute"), c.rows)
        assertEquals(Action.ToggleMute, c.onKey(Key.DIGIT, 1)) // 1 is now Mute
    }

    // ---- WhatsApp
    private fun wa(): LauncherModel {
        val m = msgs()
        m.waEnabled = true; m.waLinked = true; m.waUnread = 2; m.waStatus = "connected"
        m.waConvos = listOf(
            Convo("447700900111", "Mum", listOf(Msg("dinner?", 1000, false), Msg("yes", 2000, true), Msg("[photo] pasta", 3000, false)), 2),
            Convo("g12036311", "Family", listOf(Msg("Dad: who is up?", 500, false)), 0),
        )
        return m
    }

    @Test fun whatsAppAppearsInTheMessagesMenuWithItsUnreadCount() {
        val m = wa(); open(m)
        assertEquals(listOf("Inbox", "WhatsApp (2)", "Write message"), m.rows)
        val plain = msgs(); open(plain); assertEquals(listOf("Inbox", "Write message"), plain.rows) // not linked: nothing changes
    }

    @Test fun openingWhatsAppListsChatsAndReadingMarksThemRead() {
        val m = wa(); open(m)
        assertEquals(Action.WaOpen, m.onKey(Key.DIGIT, 2)); assertEquals(Screen.WHATSAPP, m.screen)
        assertEquals(listOf("* Mum", "Family"), m.rows)
        assertEquals(Action.WaRead("447700900111"), m.onKey(Key.OK))
        assertEquals(Screen.CHAT, m.screen); assertEquals("Mum", m.title); assertEquals("Reply", m.rows[0]); assertEquals("< [photo] pasta", m.rows[1])
        m.onKey(Key.OK); assertEquals(Screen.THREAD, m.screen); assertEquals(2, m.selected)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.CHAT, m.screen)
        assertEquals(Action.Redraw, m.onKey(Key.SOFT_RIGHT)); assertEquals(Screen.WHATSAPP, m.screen) // Back goes to the WhatsApp list, not the SMS inbox
    }

    @Test fun replyingInAWhatsAppChatSendsThroughWhatsApp() {
        val m = wa(); open(m); m.onKey(Key.DIGIT, 2); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.SOFT_LEFT)
        assertEquals(Screen.WRITE, m.screen); assertEquals(true, m.writeWa); assertEquals("Mum", m.writeLabel); assertEquals("447700900111", m.writeTo)
        m.onKey(Key.DIGIT, 6, 0)
        assertEquals(Action.SendWa("447700900111", "M"), m.onKey(Key.OK)) // key 6 is "m" (capital at the start)
    }

    @Test fun smsRepliesStayOnSms() {
        val m = wa(); open(m); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.SOFT_LEFT)
        assertEquals(false, m.writeWa)
        m.onKey(Key.DIGIT, 4, 0); m.onKey(Key.DIGIT, 4, 100)
        assertEquals(true, m.onKey(Key.OK) is Action.Send)
    }

    @Test fun theNoteSaysWhyNothingIsComingThrough() {
        val m = wa(); assertEquals("", m.waNote)
        m.waStatus = "disconnected"; assertEquals("Not connected", m.waNote)
        m.waStatus = "not linked"; assertEquals("Not linked yet", m.waNote)
    }

    @Test fun numberKeysPickChatsAndGroupsOpenLikePeople() {
        val m = wa(); open(m); m.onKey(Key.DIGIT, 2)
        assertEquals(Action.WaRead("g12036311"), m.onKey(Key.DIGIT, 2))
        assertEquals("Family", m.title)
    }

    @Test fun notLinkedOffersToLinkAndTheCodeScreenWalksThroughIt() {
        val m = msgs(); m.waEnabled = true; m.waLinked = false; open(m)
        assertEquals(listOf("Inbox", "WhatsApp", "Write message"), m.rows)
        assertEquals(Action.Redraw, m.onKey(Key.DIGIT, 2)); assertEquals(Screen.WHATSAPP, m.screen); assertEquals(listOf("Link account"), m.rows)
        assertEquals("Link" to "Back", m.softKeys)
        m.onKey(Key.OK); assertEquals(Screen.WAPAIR, m.screen)
        assertEquals(Action.None, m.onKey(Key.OK)) // too short to send
        "447700900123".forEach { m.onKey(Key.DIGIT, it - '0') }
        assertEquals("447700900123", m.waPairNumber)
        m.onKey(Key.SOFT_RIGHT); assertEquals("44770090012", m.waPairNumber) // Clear deletes a digit
        m.onKey(Key.DIGIT, 3)
        assertEquals(Action.PairWa("447700900123"), m.onKey(Key.SOFT_LEFT))
        m.waCode = "ABCD-1234"; assertEquals("New code" to "Back", m.softKeys)
        assertEquals(Action.PairWa("447700900123"), m.onKey(Key.SOFT_LEFT)) // a fresh code can be asked for
        assertEquals(Action.None, m.onKey(Key.DIGIT, 5)) // the number is locked once the code is showing
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.WHATSAPP, m.screen)
    }

    @Test fun settingsOffersWhatsAppUnlinkingWhenLinked() {
        val m = LauncherModel(listOf(MenuItem("com.android.messaging", "Messages"), MenuItem(SETTINGS_PKG, "Settings")), "com.android.contacts", "com.android.messaging")
        m.waEnabled = true; m.waLinked = true
        m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK)
        assertEquals(listOf("Theme", "Font", "Predictive text", "Speakerphone", "WhatsApp"), m.rows)
        m.onKey(Key.DIGIT, 5); assertEquals(Screen.WASET, m.screen); assertEquals(listOf("Unlink account"), m.rows)
        assertEquals(Action.UnlinkWa, m.onKey(Key.OK)); assertEquals(Screen.SETTINGS, m.screen)
    }

    @Test fun whatsAppSwitchedOffByTheModeIsNotOfferedAtAll() {
        val m = msgs(); m.waEnabled = false; open(m)
        assertEquals(listOf("Inbox", "Write message"), m.rows)
    }

    @Test fun aLongListKeepsItsSelectionWhenTheLauncherRedraws() {
        val menu = listOf(MenuItem("p.phone", "Phone"), MenuItem("com.android.messaging", "Messages"))
        val m = LauncherModel(menu, "com.android.contacts", "com.android.messaging")
        val msgs = (1..20).map { Msg("message $it", it * 1000L, false) }
        m.convos = listOf(Convo("0222", "Mum", msgs, 0))
        m.onKey(Key.OK); m.onKey(Key.DOWN); m.onKey(Key.OK); m.onKey(Key.DIGIT, 1); m.onKey(Key.OK) // menu, Messages, Inbox, the chat list
        assertEquals(Screen.CHAT, m.screen)
        m.onKey(Key.DIGIT, 4)
        assertEquals(Screen.THREAD, m.screen); assertEquals(17, m.selected); assertEquals("message 18", m.convoText()) // the fourth row (Reply, 20, 19, 18), not the menu's last
        m.setItems(menu) // a redraw re-reads the menu: the selection must survive it
        assertEquals(17, m.selected); assertEquals("message 18", m.convoText())
        m.onKey(Key.SOFT_RIGHT); for (i in 1..9) m.onKey(Key.DOWN) // back on row 3, then nine down: row 12 of 21
        m.setItems(menu); assertEquals(12, m.selected)
    }
}
