package io.github.durkimat.phoneguard.core

import io.github.durkimat.phoneguard.core.F2Gesture.Out
import io.github.durkimat.phoneguard.core.F2Gesture.Policy
import org.junit.Assert.assertEquals
import org.junit.Test

class F2GestureTest {
    private val all = Policy()

    @Test fun onePressOpensTheCameraAfterTheDoublePressWait() {
        val g = F2Gesture(F2Gesture.Mapping.NEW)
        assertEquals(emptyList<Out>(), g.down(0, all)); assertEquals(emptyList<Out>(), g.up(100, all))
        assertEquals(500L, g.deadline)
        assertEquals(emptyList<Out>(), g.timer(300, all)) // too early
        assertEquals(listOf(Out.Camera), g.timer(500, all))
        assertEquals(-1L, g.deadline)
    }

    @Test fun twoPressesAreTheTorchAndNeverTheCamera() {
        val g = F2Gesture(F2Gesture.Mapping.NEW)
        g.down(0, all); g.up(80, all); g.down(250, all)
        assertEquals(listOf(Out.Torch), g.up(330, all))
        assertEquals(emptyList<Out>(), g.timer(2000, all))
    }

    @Test fun aHoldSpeaksAndLettingGoStops() {
        val g = F2Gesture(F2Gesture.Mapping.NEW)
        g.down(0, all)
        assertEquals(listOf(Out.SpeakStart), g.timer(600, all))
        assertEquals(listOf(Out.SpeakStop), g.up(2500, all))
        assertEquals(emptyList<Out>(), g.timer(3000, all)) // no camera afterwards
    }

    @Test fun aHoldOnTheSecondPressSpeaksAndTheFirstTapIsIgnored() {
        val g = F2Gesture(F2Gesture.Mapping.NEW)
        g.down(0, all); g.up(80, all); g.down(200, all)
        assertEquals(listOf(Out.SpeakStart), g.timer(800, all))
        assertEquals(listOf(Out.SpeakStop), g.up(1500, all))
    }

    @Test fun disallowedActionsAreNotEmittedButTheHoldIsSwallowed() {
        val g = F2Gesture(F2Gesture.Mapping.NEW)
        g.down(0, all); g.up(50, all)
        assertEquals(emptyList<Out>(), g.timer(450, Policy(camera = false)))
        g.down(1000, all)
        assertEquals(emptyList<Out>(), g.timer(1600, Policy(speak = false))) // hold does nothing (not torch) when voice is off
        assertEquals(emptyList<Out>(), g.up(1900, Policy(speak = false)))
        g.down(3000, all); g.up(3050, all); g.down(3200, all)
        assertEquals(emptyList<Out>(), g.up(3250, Policy(torch = false)))
    }

    @Test fun aLostKeyUpDoesNotWedgeTheMachine() {
        val g = F2Gesture(F2Gesture.Mapping.NEW)
        g.down(0, all)
        g.down(5000, all) // the up never came
        g.up(5050, all)
        assertEquals(listOf(Out.Camera), g.timer(5450, all))
    }

    @Test fun legacyMappingIsTheOldButtons() {
        val g = F2Gesture(F2Gesture.Mapping.LEGACY)
        g.down(0, all); g.up(50, all)
        assertEquals(emptyList<Out>(), g.timer(800, all)) // one press does nothing
        g.down(1000, all); g.up(1050, all); g.down(1500, all)
        assertEquals(listOf(Out.Camera), g.up(1550, all)) // two presses, camera
        g.down(3000, all)
        assertEquals(listOf(Out.Torch), g.timer(3600, all)) // hold, torch
        assertEquals(emptyList<Out>(), g.up(3900, all))
    }
}
