// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameTest {
    private val utc = TimeZone.UTC
    private fun t(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    /** (local day, minute) pairs as a device in [tz] would stamp them. */
    private fun local(times: List<Long>, tz: TimeZone = utc) = times.map {
        val s = LocalStamp.of(it, tz)
        kotlinx.datetime.LocalDate.parse(s.day) to s.minute
    }

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
        val info = Streaks.compute(local(steps), t("2026-03-03T20:00:00Z"), utc)
        assertEquals(StreakInfo(3, 3, kotlinx.datetime.LocalDate(2026, 3, 3)), info)
        // Still alive the next day before any step, broken the day after.
        assertEquals(3, Streaks.compute(local(steps), t("2026-03-04T22:00:00Z"), utc).current)
        assertEquals(0, Streaks.compute(local(steps), t("2026-03-05T13:00:00Z"), utc).current)
        assertEquals(3, Streaks.compute(local(steps), t("2026-03-05T13:00:00Z"), utc).best)
    }

    @Test
    fun streak_graceForOneMissedDayIfStepBeforeNoon() {
        val missedOneThenMorning = listOf("2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z", "2026-03-04T11:59:00Z").map(::t)
        assertEquals(3, Streaks.compute(local(missedOneThenMorning), t("2026-03-04T15:00:00Z"), utc).current)

        val missedOneThenAfternoon = listOf("2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z", "2026-03-04T12:00:00Z").map(::t)
        val broken = Streaks.compute(local(missedOneThenAfternoon), t("2026-03-04T15:00:00Z"), utc)
        assertEquals(1, broken.current)
        assertEquals(2, broken.best)

        val missedTwo = listOf("2026-03-01T10:00:00Z", "2026-03-04T08:00:00Z").map(::t)
        assertEquals(1, Streaks.compute(local(missedTwo), t("2026-03-04T09:00:00Z"), utc).current)

        // During the grace morning the streak is still shown as alive; after noon it is gone.
        val pending = listOf("2026-03-01T10:00:00Z", "2026-03-02T10:00:00Z").map(::t)
        assertEquals(2, Streaks.compute(local(pending), t("2026-03-04T11:00:00Z"), utc).current)
        assertEquals(0, Streaks.compute(local(pending), t("2026-03-04T12:30:00Z"), utc).current)
    }

    @Test
    fun streak_usesTheUsersTimeZone() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        // 23:30 UTC on Mar 1 is 08:30 Mar 2 in Tokyo; 23:30 UTC Mar 2 is 08:30 Mar 3.
        val steps = listOf("2026-03-01T23:30:00Z", "2026-03-02T23:30:00Z").map(::t)
        assertEquals(2, Streaks.compute(local(steps, tokyo), t("2026-03-03T00:00:00Z"), tokyo).current)
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
            // Arrival order is shuffled sometimes, like steps synced late from another device.
            val arrival = if (rnd.nextBoolean()) local(steps) else local(steps).shuffled(rnd)
            val tracker = StreakTracker()
            arrival.forEach { (d, m) -> tracker.onStep(d, m) }
            val now = time + rnd.nextLong(0, 72L * 3_600_000)
            assertEquals(Streaks.compute(local(steps), now, utc), tracker.info(now, utc), "round $round")
            assertEquals(tracker.best, tracker.copy().best)
        }
        val empty = StreakTracker()
        assertEquals(StreakInfo(0, 0, null), empty.info(0, utc))
        assertEquals(0, empty.chainLength)
    }

    @Test
    fun streakTracker_ignoresOlderTimestamps() {
        val tr = StreakTracker()
        val (d2, m2) = local(listOf(t("2026-03-02T10:00:00Z"))).single()
        val (d1, m1) = local(listOf(t("2026-03-01T10:00:00Z"))).single()
        tr.onStep(d2, m2)
        tr.onStep(d1, m1) // an earlier day arriving late joins the chain
        assertEquals(2, tr.chainLength)
        assertEquals(2, tr.best)
        tr.onStep(d2, m2 + 5) // a later step on a known day changes nothing
        assertEquals(2, tr.best)
    }

    @Test
    fun streaksUseTheCompletingDevicesLocalDay_notTheFoldingReplicasZone() {
        // In Los Angeles: a step on Mar 1 at 10:00, none on Mar 2, one on Mar 3 at 11:00 → grace applies, streak 2.
        // The same instants in UTC are Mar 1 18:00 and Mar 3 19:00 → after noon, no grace, streak 1.
        val la = TimeZone.of("America/Los_Angeles")
        val s = Scenario(tz = la, start = "2026-03-01T18:00:00Z")
        val q = s.tracked("Q")
        s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), next = s.next(q, "n1"), local = LocalStamp.of(s.now, la))))
        s.advanceHours(49)
        s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), next = s.next(q, "n2"), local = LocalStamp.of(s.now, la))))
        val inLa = Fold.of(s.log, la)
        val inUtc = Fold.of(s.log, utc)
        val inTokyo = Fold.of(s.log, TimeZone.of("Asia/Tokyo"))
        assertEquals(2, inLa.counters.bestStreak)
        assertEquals(inLa.counters, inUtc.counters)
        assertEquals(inLa.counters, inTokyo.counters)
        assertEquals(inLa.achievements, inTokyo.achievements)
        assertEquals(kotlinx.datetime.LocalDate(2026, 3, 1), inUtc.steps.values.filter { it.isDone }.minOf { it.doneDay!! })
        // Without stamps (older writers), the replica's zone decides — and UTC would say 1.
        val unstamped = Scenario(tz = utc, start = "2026-03-01T18:00:00Z")
        val q2 = unstamped.tracked("Q")
        unstamped.done(unstamped.stepOf(q2), nextText = "n1")
        unstamped.advanceHours(49)
        unstamped.done(unstamped.stepOf(q2), nextText = "n2")
        assertEquals(1, unstamped.state.counters.bestStreak)
    }

    @Test
    fun aDeviceWithAFastClock_cannotBreakAnyonesStreak() {
        // Five real days of steps on this device.
        val s = Scenario(start = "2026-03-01T09:00:00Z")
        val q = s.tracked("Q")
        repeat(5) {
            s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), next = s.next(q, "n$it"), local = LocalStamp.of(s.now, utc))))
            s.advanceHours(24)
        }
        // A peer whose clock is a year ahead completes a step (its stamp is in 2027).
        val peerClock = t("2027-01-10T09:00:00Z")
        val peer = EventFactory(Ids.newId(), clock = { peerClock })
        val peerStep = s.step(q, "peer step")
        val e = peer.create(EventTypes.STEP_COMPLETED, Events.stepCompleted(StepCompleted(peerStep, next = s.next(q, "after"), local = LocalStamp.of(peerClock, utc))).payload)
        val merged = Fold.of(s.log + e, utc)
        // Today (Mar 5, real time) the streak is still the real 5-day one.
        assertEquals(5, merged.streak(t("2026-03-05T20:00:00Z")).current)
        // A forged stamp whose day doesn't match its time falls back to the event time.
        val forged = Events.stepCompleted(StepCompleted(s.stepOf(q), next = s.next(q, "x"), local = LocalStamp(1, "1999-01-01", 0)))
        s.ok(forged)
        assertTrue(s.state.steps.values.none { it.doneDay == kotlinx.datetime.LocalDate(1999, 1, 1) })
    }

    @Test
    fun hybridClock_followsObservedTimestampsForCausality() {
        var clock = t("2026-03-01T09:00:00Z")
        val f = EventFactory(Ids.newId(clock), clock = { clock })
        f.observe(clock + 2L * 86_400_000) // a peer two days ahead
        val e = f.create("quest.created", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(e.ts > clock + 2L * 86_400_000) // our edit sorts after what we saw
        f.observe(Long.MAX_VALUE)
        val e2 = f.create("quest.created", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(Long.MAX_VALUE, e2.ts) // saturates, never wraps
        clock += 1
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
        assertEquals(0, s.state.streak(s.now + 10L * 86_400_000).current)
        assertEquals(1, s.state.streak(s.now + 10L * 86_400_000).best)
    }
}
