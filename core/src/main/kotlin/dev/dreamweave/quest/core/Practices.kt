// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Practices (v1.1): ongoing quests with a rhythm that never complete. Each session is a
 * step; completing it logs the session and the next one appears by itself (see the fold).
 * Missed sessions never pile up: there is no backlog and no overdue count, only this
 * week's sessions as feedback (D-1110).
 */
object Practices {
    /** This week's sessions against the rhythm, e.g. 2 of 4. Informational only. */
    data class Week(val done: Int, val target: Int)

    data class Status(
        val rhythm: Rhythm,
        val week: Week,
        /** A session is due today (and none was logged today yet). */
        val dueToday: Boolean,
        /** Today when [dueToday], otherwise the next day a session is wanted. */
        val nextSessionDay: LocalDate,
    )

    /** Monday of [day]'s ISO week. */
    fun weekStart(day: LocalDate): LocalDate = day.minus(day.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    /** Local days on which a session of [questId] was logged. */
    fun sessionDays(state: QuestState, questId: String): Set<LocalDate> =
        state.steps.values.asSequence().filter { it.questId == questId && it.isDone }.mapNotNull { it.doneDay }.toSet()

    fun week(rhythm: Rhythm, days: Set<LocalDate>, today: LocalDate): Week {
        val start = weekStart(today)
        val end = start.plus(6, DateTimeUnit.DAY)
        return Week(days.count { it in start..end }, rhythm.perWeek)
    }

    /** Whether [day] wants a session, given the sessions in [days] (only that week's count matters). */
    fun wantsSession(rhythm: Rhythm, days: Set<LocalDate>, day: LocalDate): Boolean {
        if (day in days) return false
        return when (rhythm.kind) {
            RhythmKind.DAILY -> true
            RhythmKind.WEEKDAYS -> day.dayOfWeek.isoDayNumber in rhythm.days
            RhythmKind.PER_WEEK -> week(rhythm, days, day).done < rhythm.times
        }
    }

    fun status(state: QuestState, quest: Quest, today: LocalDate): Status? {
        val rhythm = quest.rhythm ?: return null
        val days = sessionDays(state, quest.id)
        val next = generateSequence(today) { it.plus(1, DateTimeUnit.DAY) }.take(15).firstOrNull { wantsSession(rhythm, days, it) }
            ?: today.plus(1, DateTimeUnit.DAY)
        return Status(rhythm, week(rhythm, days, today), dueToday = next == today, nextSessionDay = next)
    }

    /** Session days from [from] to [to] the rhythm asks for, after [today]'s sessions so far. */
    fun plannedDays(state: QuestState, quest: Quest, from: LocalDate, to: LocalDate, today: LocalDate): List<LocalDate> {
        val rhythm = quest.rhythm ?: return emptyList()
        // "N times a week" has no fixed days: nothing to put on the calendar.
        if (rhythm.kind == RhythmKind.PER_WEEK) return emptyList()
        val days = sessionDays(state, quest.id)
        val start = maxOf(from, today)
        if (start > to) return emptyList()
        return generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= to }
            .filter { wantsSession(rhythm, days, it) }.toList()
    }

    /** "Daily", "Mon, Wed, Fri", "3× a week", with " · 07:30" when it has a reminder. */
    fun label(rhythm: Rhythm): String {
        val base = when (rhythm.kind) {
            RhythmKind.DAILY -> "Daily"
            RhythmKind.WEEKDAYS -> when (rhythm.days.sorted()) {
                listOf(1, 2, 3, 4, 5) -> "Weekdays"
                listOf(6, 7) -> "Weekends"
                else -> rhythm.days.sorted().joinToString(", ") { dayName(it) }
            }
            RhythmKind.PER_WEEK -> if (rhythm.times == 1) "Once a week" else "${rhythm.times}× a week"
        }
        val time = rhythm.reminderMinute?.let { " · ${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}" } ?: ""
        return base + time
    }

    fun dayName(iso: Int): String = DayOfWeek(iso).name.take(3).lowercase().replaceFirstChar { it.uppercase() }
}
