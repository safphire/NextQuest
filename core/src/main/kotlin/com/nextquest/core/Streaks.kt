package com.nextquest.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

data class Streak(val current: Int, val best: Int)

object Streaks {
    /**
     * Any step or completion counts for its day. The current streak is the
     * consecutive run ending today — or yesterday, so it isn't shown as broken
     * before the user has had a chance to act today.
     */
    fun compute(activityDays: Collection<LocalDate>, today: LocalDate): Streak {
        if (activityDays.isEmpty()) return Streak(0, 0)
        val days = activityDays.toSortedSet()

        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (day in days) {
            run = if (prev?.plus(1, DateTimeUnit.DAY) == day) run + 1 else 1
            if (run > best) best = run
            prev = day
        }

        var current = 0
        var cursor = if (today in days) today else today.minus(1, DateTimeUnit.DAY)
        while (cursor in days) {
            current++
            cursor = cursor.minus(1, DateTimeUnit.DAY)
        }
        return Streak(current, best)
    }
}
