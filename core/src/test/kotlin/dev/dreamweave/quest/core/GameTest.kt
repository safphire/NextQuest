// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GameTest {
    private val utc = TimeZone.UTC
    private fun t(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun levelCurve_isGentleAndDocumented() {
        assertEquals(listOf(100L, 125L, 156L, 195L, 244L), (1..5).map { Levels.costOf(it) })
        assertEquals(0, Levels.totalFor(1))
        assertEquals(100, Levels.totalFor(2))
        assertEquals(576, Levels.totalFor(5))
        assertEquals(LevelInfo(1, 0, 100), Levels.infoFor(0))
        assertEquals(LevelInfo(1, 99, 100), Levels.infoFor(99))
        assertEquals(LevelInfo(2, 0, 125), Levels.infoFor(100))
        assertEquals(5, Levels.infoFor(576).level)
        assertEquals(4, Levels.infoFor(575).level)
        assertEquals(0.5f, LevelInfo(1, 50, 100).progress)
        assertEquals(0f, LevelInfo(1, 0, 0).progress)
        assertFailsWith<IllegalArgumentException> { Levels.infoFor(-1) }
        assertFailsWith<IllegalArgumentException> { Levels.costOf(0) }
        assertFailsWith<IllegalArgumentException> { Levels.totalFor(0) }
        // Gentle: each level costs ~1.25x the previous one.
        for (l in 2..30) {
            val ratio = Levels.costOf(l).toDouble() / Levels.costOf(l - 1)
            assert(ratio in 1.2..1.3) { "level $l ratio $ratio" }
        }
    }

    @Test
    fun streak_consecutiveDays() {
        val steps = listOf("2026-03-01T10:00:00Z", "2026-03-02T23:00:00Z", "2026-03-03T08:00:00Z", "2026-03-03T09:00:00Z").map(::t)
        val info = Streaks.compute(steps, t("2026-03-03T20:00:00Z"), utc)
        assertEquals(StreakInfo(3, 3, kotlinx.datetime.LocalDate(2026, 3, 3)), info)
        // Still alive the next day before any step, broken the day after.
        assertEquals(3, Streaks.compute(steps, t("2026-03-04T22:00:00Z"), utc).current)
        assertEquals(0, Streaks.compute(steps, t("2026-03-05T13:00:00Z"), utc).current)
        assertEquals(3, Streaks.compute(steps, t("2026-03-05T13:00:00Z"), utc).best)
    }

    @Test
    fun streak_graceForOneMissedDayIfStepBeforeNoon() {
        val missedOneThenMorning = listOf("2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z", "2026-03-04T11:59:00Z").map(::t)
        assertEquals(3, Streaks.compute(missedOneThenMorning, t("2026-03-04T15:00:00Z"), utc).current)

        val missedOneThenAfternoon = listOf("2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z", "2026-03-04T12:00:00Z").map(::t)
        val broken = Streaks.compute(missedOneThenAfternoon, t("2026-03-04T15:00:00Z"), utc)
        assertEquals(1, broken.current)
        assertEquals(2, broken.best)

        val missedTwo = listOf("2026-03-01T10:00:00Z", "2026-03-04T08:00:00Z").map(::t)
        assertEquals(1, Streaks.compute(missedTwo, t("2026-03-04T09:00:00Z"), utc).current)

        // During the grace morning the streak is still shown as alive; after noon it is gone.
        val pending = listOf("2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z").map(::t)
        assertEquals(2, Streaks.compute(pending, t("2026-03-04T11:00:00Z"), utc).current)
        assertEquals(0, Streaks.compute(pending, t("2026-03-04T12:30:00Z"), utc).current)
    }

    @Test
    fun streak_usesTheUsersTimeZone() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        // 23:30 UTC on Mar 1 is 08:30 Mar 2 in Tokyo; 23:30 UTC Mar 2 is 08:30 Mar 3.
        val steps = listOf("2026-03-01T23:30:00Z", "2026-03-02T23:30:00Z").map(::t)
        assertEquals(2, Streaks.compute(steps, t("2026-03-03T00:00:00Z"), tokyo).current)
        assertEquals(StreakInfo(0, 0, null), Streaks.compute(emptyList(), 0, utc))
    }

    @Test
    fun streakTracker_matchesFromScratch_onRandomHistories() {
        val rnd = Random(42)
        repeat(300) { round ->
            val start = t("2026-01-01T00:00:00Z")
            var time = start
            val steps = mutableListOf<Long>()
            repeat(rnd.nextInt(0, 40)) {
                time += rnd.nextLong(0, 60L * 60 * 60 * 1000) // up to 60h later
                steps += time
            }
            val tracker = StreakTracker(utc)
            steps.forEach(tracker::onStep)
            val now = time + rnd.nextLong(0, 72L * 3_600_000)
            assertEquals(Streaks.compute(steps, now, utc), tracker.info(now), "round $round")
            assertEquals(tracker.best, tracker.copy().best)
        }
        val empty = StreakTracker(utc)
        assertEquals(StreakInfo(0, 0, null), empty.info(0))
        assertEquals(0, empty.chainLength)
    }

    @Test
    fun streakTracker_ignoresOlderTimestamps() {
        val tr = StreakTracker(utc)
        tr.onStep(t("2026-03-02T10:00:00Z"))
        tr.onStep(t("2026-03-01T10:00:00Z"))
        assertEquals(1, tr.chainLength)
    }

    @Test
    fun achievements_unlockFromCountersAlone() {
        assertEquals(emptySet(), Achievement.unlockedBy(Counters()))
        val all = Achievement.unlockedBy(
            Counters(
                stepsDone = 50, questsDone = 5, subquestsDone = 10, inboxSorted = 10,
                subquestsCreated = 1, proposalsAccepted = 1, bestStreak = 7, xp = Levels.totalFor(5),
            ),
        )
        assertEquals(Achievement.entries.toSet(), all)
        assertEquals(setOf(Achievement.STEPS_10, Achievement.FIRST_STEP), Achievement.unlockedBy(Counters(stepsDone = 10)))
        Achievement.entries.forEach { assert(it.title.isNotBlank() && it.description.isNotBlank()) }
    }

    @Test
    fun noPenalties_xpNeverDecreases() {
        val s = Scenario()
        var last = 0L
        val q = s.tracked("Q")
        repeat(20) {
            s.done(s.stepOf(q), nextText = "step $it")
            s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.LATER)))
            s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED)))
            assert(s.state.xp >= last)
            last = s.state.xp
        }
        s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.DROPPED)))
        assertEquals(last, s.state.xp)
        assertNull(s.state.streak(s.now + 10L * 86_400_000).current.takeIf { it > 0 })
        assertEquals(1, s.state.streak(s.now + 10L * 86_400_000).best)
    }
}
