// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.Locale

/** Human-friendly dates. Calm wording: nothing is ever "OVERDUE" (principle 6). */
object Format {
    private fun two(n: Int) = n.toString().padStart(2, '0')

    fun time(dt: LocalDateTime) = "${two(dt.hour)}:${two(dt.minute)}"

    fun minutesOfDay(m: Int) = "${two(m / 60)}:${two(m % 60)}"

    fun weekday(d: LocalDate) = d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }

    fun month(d: LocalDate) = d.month.name.take(3).lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }

    fun shortDate(d: LocalDate) = "${weekday(d)} ${d.dayOfMonth} ${month(d)}"

    /** "Today 14:00", "Tomorrow 09:30", "Fri 14:00", "Mon 3 Mar 09:00", or "Was due Mon 3 Mar". */
    fun due(dueAt: Long, now: Long, tz: TimeZone): String {
        val due = Instant.fromEpochMilliseconds(dueAt).toLocalDateTime(tz)
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).date
        val days = today.daysUntil(due.date)
        return when {
            dueAt < now && days < 0 -> "Was due ${shortDate(due.date)}"
            days == 0 -> "Today ${time(due)}"
            days == 1 -> "Tomorrow ${time(due)}"
            days in 2..6 -> "${weekday(due.date)} ${time(due)}"
            else -> "${shortDate(due.date)} ${time(due)}"
        }
    }

    /** "now", "5m", "3h", "2d" — the age on proposal cards. */
    fun age(ts: Long, now: Long): String {
        val s = ((now - ts) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3_600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3_600}h"
            else -> "${s / 86_400}d"
        }
    }

    fun dateOf(ts: Long, tz: TimeZone): LocalDate = Instant.fromEpochMilliseconds(ts).toLocalDateTime(tz).date

    fun longDate(ts: Long, tz: TimeZone): String {
        val d = dateOf(ts, tz)
        return "${d.dayOfMonth} ${month(d)} ${d.year}"
    }

    fun historyStamp(ts: Long, tz: TimeZone): String {
        val dt = Instant.fromEpochMilliseconds(ts).toLocalDateTime(tz)
        return "${month(dt.date)} ${two(dt.dayOfMonth)} ${time(dt)}"
    }

    fun epochOf(date: LocalDate, minutesOfDay: Int, tz: TimeZone): Long =
        LocalDateTime(date.year, date.month, date.dayOfMonth, minutesOfDay / 60, minutesOfDay % 60).toInstant(tz).toEpochMilliseconds()

    fun startOfDay(date: LocalDate, tz: TimeZone): Long = date.atStartOfDayIn(tz).toEpochMilliseconds()

    fun tomorrowAt(now: Long, minutesOfDay: Int, tz: TimeZone): Long =
        epochOf(dateOf(now, tz).plus(1, DateTimeUnit.DAY), minutesOfDay, tz)
}
