package com.nextquest.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StreaksTest {

    private val today = LocalDate(2026, 7, 23)
    private fun d(day: Int) = LocalDate(2026, 7, day)

    @Test
    fun `no activity means no streak`() {
        assertEquals(Streak(0, 0), Streaks.compute(emptyList(), today))
    }

    @Test
    fun `single step today starts a streak`() {
        assertEquals(Streak(1, 1), Streaks.compute(listOf(today), today))
    }

    @Test
    fun `run ending yesterday still counts as current`() {
        val days = listOf(d(21), d(22))
        assertEquals(Streak(2, 2), Streaks.compute(days, today))
    }

    @Test
    fun `gap before yesterday breaks the current streak`() {
        val days = listOf(d(19), d(20))
        assertEquals(Streak(0, 2), Streaks.compute(days, today))
    }

    @Test
    fun `best survives a broken run`() {
        val days = listOf(d(10), d(11), d(12), d(13), d(20), today)
        assertEquals(Streak(1, 4), Streaks.compute(days, today))
    }

    @Test
    fun `duplicate days collapse`() {
        val days = listOf(today, today, d(22), d(22))
        assertEquals(Streak(2, 2), Streaks.compute(days, today))
    }

    @Test
    fun `streak crosses month boundaries`() {
        val days = listOf(LocalDate(2026, 6, 30), LocalDate(2026, 7, 1))
        assertEquals(Streak(2, 2), Streaks.compute(days, LocalDate(2026, 7, 1)))
    }
}
