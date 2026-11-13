package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.mail.MailAccount
import io.github.durkimat.phoneguard.mail.MailHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailUiTest {
    private val acct = MailAccount("kid@gmail.com", imapHost = "imap.gmail.com", smtpHost = "smtp.gmail.com")
    private val inbox = listOf(
        MailHeader(5, "Mum", "mum@x.org", "kid@gmail.com", "Tea tonight?", 1_000_000, true),
        MailHeader(4, "Dad", "dad@x.org", "kid@gmail.com", "Lift home", 900_000, false),
    )

    private fun model(configured: Boolean = true): LauncherModel = LauncherModel(listOf(MenuItem(MAIL_PKG, "Email"), MenuItem(SETTINGS_PKG, "Settings"))).also {
        if (configured) { it.mailConfig = acct; it.mailInbox = inbox; it.mailSent = listOf(MailHeader(1, "Mum", "mum@x.org", "mum@x.org", "Re: Tea", 800_000, false)) }
        it.onKey(Key.OK) // menu
    }
    private fun open(m: LauncherModel): Action = m.onKey(Key.OK) // Email

    @Test fun emailOpensTheMailboxAndAsksForASync() {
        val m = model()
        assertEquals(Action.MailSync(false), open(m))
        assertEquals(Screen.MAILBOX, m.screen)
        assertEquals(listOf("Inbox (1)", "Sent", "Write message", "Check mail now", "Account"), m.rows)
    }

    @Test fun readingAMessageFetchesItAndMarksItRead() {
        val m = model(); open(m)
        m.onKey(Key.OK) // Inbox
        assertEquals(Screen.MAILLIST, m.screen)
        assertEquals(listOf("* Mum: Tea tonight?", "Dad: Lift home"), m.rows)
        val a = m.onKey(Key.OK)
        assertEquals(Action.MailFetch(false, 5), a)
        assertEquals(Screen.MAILMSG, m.screen)
        assertTrue(m.pageBody.endsWith("Loading..."))
        assertEquals(listOf("Tea tonight?"), m.pageBody.lines().take(1))
        m.setMailBody(5, "Coming for tea?")
        assertEquals("Tea tonight?\n\nComing for tea?", m.pageBody)
        assertEquals("Dad: Lift home", m.rows.let { m.onKey(Key.SOFT_RIGHT); m.rows[1] })
        assertEquals(listOf("Mum: Tea tonight?", "Dad: Lift home"), m.rows) // no longer unread
    }

    @Test fun replyOpensAFormAndNeedsAMessage() {
        val m = model(); open(m); m.onKey(Key.OK); m.onKey(Key.OK) // message
        m.onKey(Key.SOFT_LEFT) // options
        assertEquals(listOf("Reply", "Delete", "Mark unread"), m.rows)
        m.onKey(Key.OK)
        assertEquals(Screen.FORM, m.screen)
        assertEquals("Write message", m.title)
        assertEquals(listOf("To: mum@x.org", "Subject: Re: Tea tonight?", "Message: -"), m.rows)
        m.onKey(Key.SOFT_LEFT)
        assertEquals("Write a message first", m.notice)
        // type "h" (4 pressed twice, capital at the start) in the message
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals(Screen.WRITE, m.screen)
        m.onKey(Key.DIGIT, 4, 1000L); m.onKey(Key.DIGIT, 4, 1200L)
        m.onKey(Key.SOFT_LEFT)
        assertEquals(Screen.FORM, m.screen)
        val a = m.onKey(Key.SOFT_LEFT) as Action.MailSend
        assertEquals(listOf("mum@x.org"), a.to); assertEquals("Re: Tea tonight?", a.subject); assertEquals("H", a.body)
        assertEquals(Screen.MAILLIST, m.screen)
        assertEquals("Sending...", m.notice)
    }

    @Test fun writeNeedsARealAddress() {
        val m = model(); open(m)
        repeat(2) { m.onKey(Key.DOWN) }; m.onKey(Key.OK) // Write message
        assertEquals(Screen.FORM, m.screen)
        m.onKey(Key.SOFT_LEFT)
        assertEquals("Check the To address", m.notice)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MAILBOX, m.screen)
    }

    @Test fun deleteAsksFirst() {
        val m = model(); open(m); m.onKey(Key.OK); m.onKey(Key.OK); m.onKey(Key.SOFT_LEFT)
        m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals(listOf("Yes, delete it", "No"), m.rows)
        m.onKey(Key.OK) // the highlight starts on No
        assertEquals(listOf("Reply", "Delete", "Mark unread"), m.rows)
        m.onKey(Key.OK); m.onKey(Key.UP) // Delete again, then Yes
        val a = m.onKey(Key.OK)
        assertEquals(Action.MailDelete(false, 5), a)
        assertEquals(Screen.MAILLIST, m.screen)
        assertEquals(listOf("Dad: Lift home"), m.rows)
    }

    @Test fun markUnread() {
        val m = model(); open(m); m.onKey(Key.OK); m.onKey(Key.DOWN); m.onKey(Key.OK); m.onKey(Key.SOFT_LEFT)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); val a = m.onKey(Key.OK)
        assertEquals(Action.MailSeen(4, false), a)
        assertEquals("* Dad: Lift home", m.rows[1])
    }

    @Test fun sentListShowsWhoItWentTo() {
        val m = model(); open(m); m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals("Sent", m.title); assertEquals(listOf("mum@x.org: Re: Tea"), m.rows)
    }

    @Test fun noAccountOffersSetupAndNeedsAnAddress() {
        val m = model(false); open(m)
        assertEquals(listOf("Set up email"), m.rows)
        m.onKey(Key.OK)
        assertEquals(Screen.FORM, m.screen); assertEquals("Set up email", m.title)
        assertEquals(listOf("Address: -", "Password: -", "Your name: -"), m.rows)
        m.onKey(Key.SOFT_LEFT)
        assertEquals("Enter your email address", m.notice)
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.MAILBOX, m.screen)
    }

    @Test fun accountRowsOnceSignedIn() {
        val m = model(); open(m); repeat(4) { m.onKey(Key.DOWN) }; m.onKey(Key.OK)
        assertEquals(Screen.FORM, m.screen)
        assertEquals(listOf("Account: kid@gmail.com", "Password: -", "Remove account"), m.rows)
        m.onKey(Key.DOWN); m.onKey(Key.DOWN); m.onKey(Key.OK)
        assertEquals("Press again to remove", m.rows[2])
        assertEquals(Action.MailRemove, m.onKey(Key.OK))
        assertEquals(Screen.MAILBOX, m.screen)
    }

    @Test fun checkNowForcesASync() {
        val m = model(); open(m); repeat(3) { m.onKey(Key.DOWN) }
        assertEquals(Action.MailSync(true), m.onKey(Key.OK))
        assertTrue(m.rows[3].contains("Checking"))
    }
}
