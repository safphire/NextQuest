// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Coming back without guilt (v1.1): when to offer a fresh start, and when to say one calm
 * line after a day without a step. Pure rules; the app keeps the device-local bookkeeping
 * (last open day, dismissals) in its settings (D-1115, D-1117).
 */
object Returning {
    enum class FreshStartReason { AWAY, NEW_WEEK, NEW_MONTH }

    /** Two dismissals in a row pause the offer for this many days. */
    const val PAUSE_DAYS = 30
    const val DISMISSALS_TO_PAUSE = 2

    /**
     * Whether the first open of [today] offers a fresh start: after [daysAway] ≥ 2 days away,
     * or on the first open of a new week (weeks start on Monday) or month. Never on the very
     * first open of the app, never twice a day, and not while paused.
     */
    fun freshStartOffer(lastOpenDay: LocalDate?, today: LocalDate, daysAway: Int?, pausedUntil: LocalDate?): FreshStartReason? {
        if (lastOpenDay == null || lastOpenDay >= today) return null
        if (pausedUntil != null && today < pausedUntil) return null
        return when {
            (daysAway ?: 0) >= Views.RESUME_AFTER_DAYS -> FreshStartReason.AWAY
            Practices.weekStart(today) != Practices.weekStart(lastOpenDay) -> FreshStartReason.NEW_WEEK
            today.month != lastOpenDay.month || today.year != lastOpenDay.year -> FreshStartReason.NEW_MONTH
            else -> null
        }
    }

    /** After a dismissal: the new count of dismissals in a row, and the day the pause ends (if one starts). */
    fun afterDismissal(dismissedInARow: Int, today: LocalDate): Pair<Int, LocalDate?> {
        val n = dismissedInARow + 1
        return if (n >= DISMISSALS_TO_PAUSE) 0 to today.plus(PAUSE_DAYS, DateTimeUnit.DAY) else n to null
    }

    /**
     * The forgiveness line (C5): yesterday passed without a completed step, for someone who
     * has done steps before and was around recently (after 2+ days away the resume card
     * speaks instead). Not once a step is done today, and not after it was dismissed today.
     */
    fun forgivenessDue(state: QuestState, nowMs: Long, tz: TimeZone, dismissedOn: LocalDate?): Boolean {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
        if (dismissedOn == today) return false
        if (state.counters.stepsDone == 0) return false
        val away = Views.daysAway(state, nowMs, tz) ?: return false
        if (away >= Views.RESUME_AFTER_DAYS) return false
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val doneDays = state.steps.values.mapNotNullTo(HashSet()) { it.doneDay }
        if (today in doneDays || yesterday in doneDays) return false
        // Only once there was a "yesterday" to speak of: the first step came before it.
        return doneDays.any { it < yesterday }
    }

    /** Which of [variants] lines to show on [today]: it rotates by day. */
    fun variant(today: LocalDate, variants: Int): Int =
        if (variants <= 0) 0 else Math.floorMod(today.toEpochDays().toLong(), variants.toLong()).toInt()
}
