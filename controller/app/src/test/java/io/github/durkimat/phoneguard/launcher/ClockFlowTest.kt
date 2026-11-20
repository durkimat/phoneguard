package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.alarm.Alarm
import io.github.durkimat.phoneguard.alarm.ClockPrefs
import io.github.durkimat.phoneguard.alarm.Stopwatch
import io.github.durkimat.phoneguard.alarm.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Clock app: reached from the clock on the home screen, walked by key. */
class ClockFlowTest {
    private fun phone() = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")), contactsApp = "c", messagesApp = "m", dialerApp = "d")
    private fun LauncherModel.key(k: Key, d: Int = -1, now: Long = 0L) = onKey(k, d, now)
    private fun LauncherModel.openClockApp() { key(Key.UP); assertEquals("clock", homeFocusTarget()?.kind); key(Key.OK) }

    @Test fun upSelectsTheClockAndOkOpensTheClockApp() {
        val m = phone()
        m.openClockApp()
        assertEquals(Screen.CLOCK, m.screen)
        assertEquals(listOf("Alarms", "Timer", "Stopwatch"), m.rows) // "Set time" is hidden (not greyed) where the clock comes from the network
        m.timeSettable = true
        assertEquals("Set time", m.rows[3])
        m.key(Key.SOFT_RIGHT)
        assertEquals(Screen.HOME, m.screen)
    }

    @Test fun addEditSwitchAndDeleteAnAlarm() {
        val m = phone(); var saved = emptyList<Alarm>()
        m.onAlarms = { saved = it }
        m.openClockApp(); m.key(Key.OK)                       // Alarms
        assertEquals(Screen.ALARMS, m.screen); assertEquals(listOf("Add alarm"), m.rows)
        m.key(Key.OK)                                          // a new alarm
        assertEquals(Screen.FORM, m.screen); assertEquals("New alarm", m.title)
        m.key(Key.DOWN); m.key(Key.RIGHT); m.key(Key.RIGHT)    // hour 09
        m.key(Key.DOWN); m.key(Key.STAR)                       // minute 50 (a big step back from 00)
        m.key(Key.DOWN); m.key(Key.OK)                         // Mon on
        m.key(Key.SOFT_LEFT)                                   // Save
        assertEquals(Screen.ALARMS, m.screen)
        assertEquals(listOf(Alarm(1, 9, 50, 1, true, "chime", 5, false)), saved)
        assertEquals(listOf("09:50  Mon", "Add alarm"), m.rows)
        m.key(Key.RIGHT)                                       // off without opening
        assertEquals(false, saved[0].on); assertEquals("09:50  Off", m.rows[0])
        m.key(Key.OK)                                          // open it, delete it
        repeat(14) { m.key(Key.DOWN) }
        assertEquals("Delete this alarm", m.rows[m.selected])
        m.key(Key.OK)
        assertEquals(Screen.ALARMS, m.screen); assertTrue(saved.isEmpty())
    }

    @Test fun fiveAlarmsFillTheList() {
        val m = phone(); m.alarms = (1..5).map { Alarm(it, it, 0) }
        m.openClockApp(); m.key(Key.OK)
        assertEquals(5, m.rows.size)
        assertTrue(m.rows.none { it == "Add alarm" })
    }

    @Test fun timerSetStartPauseReset() {
        val m = phone(); var t = TimerState(); m.onTimer = { t = it }
        m.openClockApp(); m.key(Key.DOWN); m.key(Key.OK)
        assertEquals(Screen.TIMER, m.screen)
        m.key(Key.DIGIT, 2)                                    // two minutes
        m.key(Key.RIGHT); m.key(Key.RIGHT)                     // 2:20
        assertEquals(140, t.length)
        m.key(Key.OK, now = 1_000)                             // Start
        assertTrue(t.running); assertEquals("Pause", m.softKeys.first)
        m.key(Key.OK, now = 41_000)
        assertTrue(t.paused); assertEquals(100, t.leftSec(0)); assertEquals("Resume", m.softKeys.first)
        m.key(Key.STAR)
        assertEquals(TimerState(140), t)
        m.key(Key.SOFT_RIGHT)
        assertEquals(Screen.CLOCK, m.screen)
    }

    @Test fun stopwatchStartLapStop() {
        val m = phone(); var s = Stopwatch(); m.onStopwatch = { s = it }
        m.openClockApp(); m.key(Key.DOWN); m.key(Key.DOWN); m.key(Key.OK)
        assertEquals(Screen.STOPWATCH, m.screen)
        m.key(Key.OK, now = 1_000); m.key(Key.DOWN, now = 4_000); m.key(Key.OK, now = 6_000)
        assertEquals(listOf(3_000L), s.laps); assertEquals(5_000, s.elapsed(9_999)); assertEquals("Start", m.softKeys.first)
        m.key(Key.STAR)
        assertEquals(Stopwatch(), s)
    }

    @Test fun setTimeNeedsTheOptionAndSaysWhenItCannot() {
        val m = phone(); m.timeSettable = true
        var got = 0L; m.onSetTime = { got = it; false }
        m.openClockApp(); m.key(Key.DOWN); m.key(Key.DOWN); m.key(Key.DOWN); m.key(Key.OK)
        assertEquals("Set time", m.title)
        m.key(Key.DOWN); m.key(Key.RIGHT)
        m.key(Key.SOFT_LEFT)
        assertTrue(got > 0); assertEquals("This phone can't change the time", m.notice)
    }

    @Test fun volumeAndGradualArePerAlarm() {
        val m = phone(); var saved = emptyList<Alarm>(); val previews = ArrayList<Pair<String, Int>>()
        m.onAlarms = { saved = it }; m.onPreviewLoop = { t, v -> previews.add(t to v) }; m.defaultAlarmVol = 4
        m.openClockApp(); m.key(Key.OK); m.key(Key.OK)          // Alarms, a new alarm: starts at the default volume
        repeat(11) { m.key(Key.DOWN) }
        assertTrue(m.rows[m.selected].startsWith("Sound"))
        m.key(Key.DOWN)                                         // the Volume row sits right under Sound
        assertTrue(m.rows[m.selected].contains("####-"))
        m.key(Key.RIGHT); m.key(Key.RIGHT)
        assertEquals(6, previews.last().second)
        m.key(Key.DOWN); m.key(Key.OK)                          // Gradual on
        m.key(Key.SOFT_LEFT)
        assertEquals(6, saved[0].vol); assertTrue(saved[0].gradual)
    }

    @Test fun ringScreenSnoozesAndStops() {
        val m = phone(); m.setRing("alarm", "07:00", false)
        assertEquals(Screen.RING, m.screen); assertEquals("Snooze" to "Stop", m.softKeys)
        assertEquals(Action.AlarmSnooze, m.key(Key.OK)); assertEquals(Action.AlarmStop, m.key(Key.SOFT_RIGHT))
        m.setRing("timer", "Timer", false)
        assertEquals(Action.AlarmStop, m.key(Key.OK))
        assertEquals("" to "Stop", m.softKeys)
    }
}

class TonesFlowTest {
    private fun phone() = LauncherModel(listOf(MenuItem(SETTINGS_PKG, "Settings")), contactsApp = "c", messagesApp = "m", dialerApp = "d")
    private fun LauncherModel.key(k: Key, d: Int = -1) = onKey(k, d, 0L)

    @Test fun pickTonesForCallsTextsAndWhatsAppWithAPreview() {
        val m = phone(); m.waEnabled = true; val picks = mutableListOf<Pair<String, String>>(); val heard = mutableListOf<String>()
        m.onTonePick = { s, id -> picks += s to id }; m.onPreview = { heard += it }
        m.key(Key.OK); m.key(Key.OK)                 // Menu, Settings
        m.key(Key.DIGIT, 1); m.key(Key.DIGIT, 2)     // Sound, Calls
        assertEquals(Screen.FORM, m.screen); assertEquals("Calls", m.title)
        assertEquals(listOf("Ringtone: Phone's own", "Volume: ###----", "Vibrate: On", "Speaker button: Show", "Style: Classic beep"), m.rows)
        m.key(Key.RIGHT)                             // ringtone: the first tune
        m.key(Key.SOFT_RIGHT); m.key(Key.DIGIT, 3)   // back to Sound, then Messages
        assertEquals("Messages", m.title); assertEquals(listOf("Text: Phone's own", "WhatsApp: Phone's own", "Volume: ###----", "Vibrate: On"), m.rows)
        m.key(Key.RIGHT); m.key(Key.RIGHT)           // text tone: two steps on
        m.key(Key.DOWN); m.key(Key.LEFT)             // WhatsApp tone: back round to the last tune
        assertEquals(listOf("ring" to "waltz", "sms" to "waltz", "sms" to "elise", "wa" to "double"), picks)
        assertEquals(listOf("waltz", "waltz", "elise", "double"), heard)
        assertEquals("Text: Fur Elise", m.rows[0])
    }

    @Test fun oneVolumePressIsOneStepAndEachBarIsItsOwn() {
        val m = phone(); m.waEnabled = true; val seen = ArrayList<Pair<Int, Int>>()
        // ringtone and messages: eleven steps drawn as seven bars; media: a stream of five steps shown as five cells
        var pos = listOf(1, 0, 3, 2)
        m.soundInfo = { LauncherModel.SoundInfo(0, null, pos.mapIndexed { i, p -> if (i < 2) LauncherModel.Vol(p, 10, io.github.durkimat.phoneguard.alarm.SoundLevels.bars(p), 7) else LauncherModel.Vol(p, 5, p, 5) }) }
        m.onVolume = { w, v -> seen.add(w to v); pos = pos.toMutableList().also { it[w] = v } }
        m.key(Key.OK); m.key(Key.UP); m.key(Key.OK)            // Settings
        m.key(Key.DIGIT, 1); m.key(Key.DIGIT, 2)                        // Sound > Calls
        assertEquals("Calls", m.title)
        m.key(Key.DOWN)                                                 // Volume (the ringtone is the first row)
        assertEquals("#------", m.rows[1].substringAfter(" ").trim().takeLast(7))
        m.key(Key.RIGHT); assertEquals(listOf(0 to 2), seen)            // hidden sub-step: the bar does not change
        assertTrue(m.rows[1].endsWith("#------"))
        m.key(Key.SOFT_RIGHT); m.key(Key.DIGIT, 3); m.key(Key.DOWN); m.key(Key.DOWN) // Sound > Messages > Volume
        m.key(Key.LEFT); assertEquals(listOf(0 to 2, 1 to 0), seen)     // zero on messages
        assertEquals(2, pos[0])                                         // ringtone untouched
        m.key(Key.SOFT_RIGHT); m.key(Key.DIGIT, 4); m.key(Key.RIGHT)    // Sound > Media volume: a screen of its own
        assertEquals("Media volume", m.title); assertEquals(2 to 4, seen.last()) // media: one press, one step
    }

    @Test fun ringerScreenOffersRingAndVibrateChoices() {
        val m = phone(); var got = -1
        m.soundInfo = { LauncherModel.SoundInfo(0, null, List(4) { LauncherModel.Vol(3, 7, 3, 7) }) }
        m.onRinger = { got = it }
        m.key(Key.OK); m.key(Key.UP); m.key(Key.OK); m.key(Key.OK)                    // Settings > Sound
        m.key(Key.OK)                                                                   // Ringer mode
        assertEquals(listOf("Ring and vibrate", "Ring only", "Vibrate only", "Silent"), m.rows.take(4).map { it.substringBefore(":").trim().removeSuffix("(x)").removeSuffix("( )").trim() })
        assertTrue(m.rows[0].contains("(x)"))
        repeat(2) { m.key(Key.DOWN) }; m.key(Key.OK); assertEquals(2, got)
    }
}
