package io.github.durkimat.phoneguard.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaPendingTest {
    private val add = WaPending.Op(WaPending.ADD, "111", "Family", 1000, 7, 500)
    private val remove = WaPending.Op(WaPending.REMOVE, "222", "Football", 1100, 7, 600)

    @Test fun anAddPutsTheIdOnTopAndARemoveTakesTheIdAndTheOldNameEntryOut() {
        val allowed = listOf("447700900111", "g:Football", "gid:222", "gid:333")
        assertEquals(listOf("447700900111", "gid:333", "gid:111"), WaPending.overlay(allowed, listOf(add, remove)))
        assertEquals(listOf("gid:111"), WaPending.overlay(listOf("gid:111"), listOf(add))) // no duplicate
    }

    @Test fun aConfigThatArrivesBeforeTheWorkerHasAnsweredNeverRevertsAChange() {
        // the reply's config does not have the change yet, and there is no result for it: it stays on top and stays queued
        val (effective, left) = WaPending.afterSync(listOf(add), emptyMap(), listOf("447700900111"))
        assertEquals(listOf("447700900111", "gid:111"), effective); assertEquals(listOf(add), left)
    }

    @Test fun anAnsweredChangeIsDroppedAndTheConfigIsTheTruth() {
        // applied: the new config already carries it
        val applied = WaPending.afterSync(listOf(add), mapOf(500L to "applied"), listOf("447700900111", "gid:111"))
        assertEquals(listOf("447700900111", "gid:111"), applied.first); assertTrue(applied.second.isEmpty())
        // superseded by a newer companion edit: the config wins, the change is dropped
        val superseded = WaPending.afterSync(listOf(add), mapOf(500L to "superseded"), listOf("447700900111"))
        assertEquals(listOf("447700900111"), superseded.first); assertTrue(superseded.second.isEmpty())
        // one answered, one still waiting
        val mixed = WaPending.afterSync(listOf(add, remove), mapOf(500L to "applied"), listOf("gid:111", "gid:222"))
        assertEquals(listOf("gid:111"), mixed.first); assertEquals(listOf(remove), mixed.second)
    }

    @Test fun reconcileSaysWhatWasAnsweredAndHow() {
        val (left, answered) = WaPending.reconcile(listOf(add, remove), mapOf(600L to "rejected"))
        assertEquals(listOf(add), left); assertEquals(listOf(remove to "rejected"), answered)
    }

    @Test fun resultsAreParsedFromTheReply() {
        val a = org.json.JSONArray("""[{"cmdTs":500,"status":"applied"},{"cmdTs":600,"status":"superseded"}]""")
        assertEquals(mapOf(500L to "applied", 600L to "superseded"), WaPending.parseResults(a)); assertEquals(emptyMap<Long, String>(), WaPending.parseResults(null))
    }
}
