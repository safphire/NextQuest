// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.math.pow
import kotlin.math.roundToLong

/** XP rewards. No penalties and no lost XP, ever: there is no negative entry here. */
object Xp {
    const val STEP = 15
    const val SUBQUEST = 40
    const val QUEST = 100
}

data class LevelInfo(val level: Int, val xpIntoLevel: Long, val xpForNextLevel: Long) {
    val progress: Float get() = if (xpForNextLevel == 0L) 0f else xpIntoLevel.toFloat() / xpForNextLevel
}

/**
 * Gentle level curve: reaching level 2 takes 100 XP and every further level costs
 * 1.25× the previous one, rounded: 100, 125, 156, 195, 244, 305, … (level 5 at 576 XP,
 * level 10 at 2,579 XP). See docs/ARCHITECTURE.md.
 */
object Levels {
    const val BASE = 100.0
    const val GROWTH = 1.25

    /** XP needed to go from [level] to level + 1. */
    fun costOf(level: Int): Long {
        require(level >= 1)
        return (BASE * GROWTH.pow(level - 1)).roundToLong()
    }

    /** Total XP needed to reach [level] from zero. */
    fun totalFor(level: Int): Long {
        require(level >= 1)
        var total = 0L
        for (l in 1 until level) total += costOf(l)
        return total
    }

    fun infoFor(xp: Long): LevelInfo {
        require(xp >= 0)
        var level = 1
        var remaining = xp
        while (remaining >= costOf(level)) {
            remaining -= costOf(level)
            level++
        }
        return LevelInfo(level, remaining, costOf(level))
    }
}

data class StreakInfo(val current: Int, val best: Int, val lastActiveDay: LocalDate?)

/**
 * Streak rules: a day counts when at least one step is completed. Missing exactly one
 * day is forgiven when a step is completed before noon on the following day. Both the
 * incremental [StreakTracker] and the from-scratch [Streaks.compute] implement these
 * rules independently; tests check they always agree.
 */
object Streaks {
    const val GRACE_DEADLINE_HOUR = 12

    /** Links two consecutive active days of a chain. */
    internal fun links(previous: LocalDate, next: LocalDate, nextFirstStep: LocalDateTime): Boolean {
        val gap = previous.daysUntil(next)
        return gap == 1 || (gap == 2 && nextFirstStep.hour < GRACE_DEADLINE_HOUR)
    }

    /** Whether a chain whose last active day is [last] is still alive at [now]. */
    internal fun alive(last: LocalDate, now: LocalDateTime): Boolean {
        val gap = last.daysUntil(now.date)
        return gap <= 1 || (gap == 2 && now.hour < GRACE_DEADLINE_HOUR)
    }

    /** From scratch: timestamps of every completed step, in any order. */
    fun compute(stepTimestamps: Collection<Long>, nowMs: Long, tz: TimeZone): StreakInfo {
        if (stepTimestamps.isEmpty()) return StreakInfo(0, 0, null)
        val firstPerDay = stepTimestamps
            .map { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz) }
            .groupBy { it.date }
            .mapValues { (_, times) -> times.min() }
            .toSortedMap()
        var best = 0
        var chain = 0
        var previous: LocalDate? = null
        for ((day, firstStep) in firstPerDay) {
            chain = if (previous != null && links(previous, day, firstStep)) chain + 1 else 1
            best = maxOf(best, chain)
            previous = day
        }
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
        val last = previous!!
        val current = if (alive(last, now)) chain else 0
        return StreakInfo(current, best, last)
    }
}

/** Incremental streak, fed with step completions in time order. */
class StreakTracker(private val tz: TimeZone) {
    private var lastDay: LocalDate? = null
    private var chain = 0
    var best = 0
        private set

    fun copy(): StreakTracker = StreakTracker(tz).also {
        it.lastDay = lastDay
        it.chain = chain
        it.best = best
    }

    fun onStep(ts: Long) {
        val at = Instant.fromEpochMilliseconds(ts).toLocalDateTime(tz)
        val previous = lastDay
        when {
            previous == null -> chain = 1
            at.date <= previous -> return // same day (or clock went back): nothing new
            Streaks.links(previous, at.date, at) -> chain += 1
            else -> chain = 1
        }
        lastDay = at.date
        best = maxOf(best, chain)
    }

    /** Length of the chain ending at the latest active day, regardless of whether it is still alive. */
    val chainLength: Int get() = chain

    fun info(nowMs: Long): StreakInfo {
        val last = lastDay ?: return StreakInfo(0, 0, null)
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
        return StreakInfo(if (Streaks.alive(last, now)) chain else 0, best, last)
    }
}

/** Lifetime counters, all derived from accepted events. */
data class Counters(
    val stepsDone: Int = 0,
    val questsDone: Int = 0,
    val subquestsDone: Int = 0,
    val inboxSorted: Int = 0,
    val subquestsCreated: Int = 0,
    val proposalsAccepted: Int = 0,
    val bestStreak: Int = 0,
    val xp: Long = 0,
)

enum class Achievement(val title: String, val description: String, val isUnlocked: (Counters) -> Boolean) {
    FIRST_STEP("First step", "Complete your first step", { it.stepsDone >= 1 }),
    STEPS_10("Momentum", "Complete 10 steps", { it.stepsDone >= 10 }),
    STEPS_50("Relentless", "Complete 50 steps", { it.stepsDone >= 50 }),
    FIRST_QUEST("Quest complete", "Finish your first quest", { it.questsDone >= 1 }),
    QUESTS_5("Seasoned", "Finish 5 quests", { it.questsDone >= 5 }),
    STREAK_3("Warming up", "Reach a 3-day streak", { it.bestStreak >= 3 }),
    STREAK_7("On a roll", "Reach a 7-day streak", { it.bestStreak >= 7 }),
    LEVEL_5("Level 5", "Reach level 5", { Levels.infoFor(it.xp).level >= 5 }),
    INBOX_10("Sorter", "Sort 10 inbox items", { it.inboxSorted >= 10 }),
    FIRST_TREE("Branching out", "Create your first subquest", { it.subquestsCreated >= 1 }),
    SUBQUESTS_10("Piece by piece", "Finish 10 subquests", { it.subquestsDone >= 10 }),
    FIRST_PROPOSAL("Good advice", "Accept your first AI proposal", { it.proposalsAccepted >= 1 }),
    ;

    companion object {
        /** From scratch: every achievement the counters qualify for. */
        fun unlockedBy(counters: Counters): Set<Achievement> = entries.filter { it.isUnlocked(counters) }.toSet()
    }
}
