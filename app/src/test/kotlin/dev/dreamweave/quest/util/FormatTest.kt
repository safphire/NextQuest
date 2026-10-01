// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FormatTest {
    private val utc = TimeZone.UTC
    private fun t(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun dueChipsAreCalm() {
        val now = t("2026-03-04T10:00:00Z") // a Wednesday
        assertEquals("Today 14:30", Format.due(t("2026-03-04T14:30:00Z"), now, utc))
        assertEquals("Tomorrow 09:00", Format.due(t("2026-03-05T09:00:00Z"), now, utc))
        assertEquals("Sat 08:15", Format.due(t("2026-03-07T08:15:00Z"), now, utc))
        assertEquals("Mon 16 Mar 09:00", Format.due(t("2026-03-16T09:00:00Z"), now, utc))
        val past = Format.due(t("2026-03-01T09:00:00Z"), now, utc)
        assertEquals("Was due Sun 1 Mar", past)
        assertFalse("overdue" in past.lowercase())
    }

    @Test
    fun agesAndTimes() {
        assertEquals("now", Format.age(1_000, 30_000))
        assertEquals("5m", Format.age(0, 5 * 60_000))
        assertEquals("3h", Format.age(0, 3 * 3_600_000))
        assertEquals("2d", Format.age(0, 2 * 86_400_000))
        assertEquals("08:05", Format.minutesOfDay(8 * 60 + 5))
        assertEquals(t("2026-03-05T21:00:00Z"), Format.tomorrowAt(t("2026-03-04T23:00:00Z"), 21 * 60, utc))
        assertEquals(t("2026-03-04T00:00:00Z"), Format.startOfDay(LocalDate(2026, 3, 4), utc))
        assertEquals("4 Mar 2026", Format.longDate(t("2026-03-04T10:00:00Z"), utc))
    }
}
