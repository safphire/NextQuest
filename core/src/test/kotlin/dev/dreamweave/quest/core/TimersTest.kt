// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimersTest {
    @Test
    fun focusSession_runsPausesResumesAndEnds() {
        val t0 = 1_000_000L
        val s = FocusSession.start("q", "s", "Draft", 25, t0)
        assertEquals(25 * 60_000L, s.leftMs(t0))
        assertFalse(s.paused)
        val p = s.pause(t0 + 60_000)
        assertTrue(p.paused)
        assertEquals(24 * 60_000L, p.leftMs(t0 + 10 * 60_000)) // paused time doesn't count
        assertEquals(p, p.pause(t0 + 70_000))
        val r = p.resume(t0 + 10 * 60_000)
        assertEquals(t0 + 34 * 60_000L, r.endsAt)
        assertEquals(r, r.resume(t0))
        assertFalse(r.isOver(t0 + 33 * 60_000))
        assertTrue(r.isOver(t0 + 34 * 60_000))
        val e = r.end()
        assertTrue(e.ended)
        assertFalse(e.paused)
        assertFalse(e.isOver(t0 + 99 * 60_000))
        assertEquals(0, e.leftMs(t0))
        assertEquals(e, e.pause(t0))
        assertFailsWith<IllegalArgumentException> { FocusSession.start("q", "s", "x", 5, t0) }
        assertEquals("25:00", FocusSession.format(25 * 60_000L))
        assertEquals("0:01", FocusSession.format(1))
        assertEquals("1:05:00", FocusSession.format(65 * 60_000L))
    }

    @Test
    fun breathing_followsTwoInhalesAndALongExhale() {
        val a = Breathing.at(0, 5)
        assertEquals(1, a.breath)
        assertEquals(Breathing.Phase.INHALE, a.phase)
        assertEquals(0f, a.fullness)
        assertEquals(Breathing.Phase.TOP_UP, Breathing.at(Breathing.INHALE_MS, 5).phase)
        val top = Breathing.at(Breathing.INHALE_MS + Breathing.TOP_UP_MS - 1, 5)
        assertTrue(top.fullness > 0.99f)
        assertEquals(Breathing.Phase.EXHALE, Breathing.at(Breathing.INHALE_MS + Breathing.TOP_UP_MS, 5).phase)
        assertEquals(2, Breathing.at(Breathing.CYCLE_MS, 5).breath)
        val end = Breathing.at(5 * 60_000L + 10, 5)
        assertTrue(end.finished)
        assertEquals(0, end.leftMs)
        assertEquals(60_000L - 1_000, Breathing.at(1_000, 1).leftMs)
        // Exhale is the longest part, as in cyclic sighing.
        assertTrue(Breathing.EXHALE_MS > Breathing.INHALE_MS + Breathing.TOP_UP_MS)
        val starts = Breathing.phaseStarts(1)
        assertEquals(Breathing.cycles(1), starts.count { it.second == Breathing.Phase.INHALE })
        assertTrue(starts.all { it.first < 60_000L })
        assertEquals(listOf(1, 3, 5), Breathing.MINUTES)
    }
}
