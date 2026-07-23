package com.nextquest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XpTest {

    @Test
    fun `event rewards match the spec table`() {
        assertEquals(15, Xp.forEvent(EventType.STEP_DONE))
        assertEquals(40, Xp.forEvent(EventType.SUBQUEST_DONE))
        assertEquals(100, Xp.forEvent(EventType.QUEST_DONE))
        assertEquals(0, Xp.forEvent(EventType.CREATED))
        assertEquals(0, Xp.forEvent(EventType.TRACKED))
        assertEquals(0, Xp.forEvent(EventType.UNTRACKED))
    }

    @Test
    fun `total sums only rewarding events`() {
        val events = listOf(
            EventType.CREATED,
            EventType.TRACKED,
            EventType.STEP_DONE,
            EventType.STEP_DONE,
            EventType.SUBQUEST_DONE,
            EventType.QUEST_DONE,
        )
        assertEquals(15 + 15 + 40 + 100, Xp.total(events))
    }

    @Test
    fun `level curve is 100 plus 50 per level`() {
        assertEquals(100, Xp.requiredFor(1))
        assertEquals(150, Xp.requiredFor(2))
        assertEquals(200, Xp.requiredFor(3))
        assertEquals(550, Xp.requiredFor(10))
    }

    @Test
    fun `progress at zero xp is level 1`() {
        assertEquals(LevelProgress(level = 1, intoLevel = 0, forNext = 100), Xp.progress(0))
    }

    @Test
    fun `progress just below and at level boundaries`() {
        assertEquals(LevelProgress(1, 99, 100), Xp.progress(99))
        assertEquals(LevelProgress(2, 0, 150), Xp.progress(100))
        assertEquals(LevelProgress(2, 149, 150), Xp.progress(249))
        assertEquals(LevelProgress(3, 0, 200), Xp.progress(250))
        // 100 + 150 + 200 + 250 = 700 → level 5
        assertEquals(LevelProgress(5, 0, 300), Xp.progress(700))
    }

    @Test
    fun `fraction reflects xp into level`() {
        assertEquals(0.5f, Xp.progress(50).fraction)
    }

    @Test
    fun `negative xp and level zero are rejected`() {
        assertFailsWith<IllegalArgumentException> { Xp.progress(-1) }
        assertFailsWith<IllegalArgumentException> { Xp.requiredFor(0) }
    }
}
