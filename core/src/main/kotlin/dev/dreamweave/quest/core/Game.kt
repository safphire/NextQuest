// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * XP rewards. No penalties and no lost XP, ever: there is no negative entry here. (Undo
 * reverses a completion the user takes back; that is a correction, not a penalty, D-1112.)
 */
object Xp {
    const val STEP = 15
    const val SUBQUEST = 40
    const val QUEST = 100

    /** Effort XP (v1.1): per focus block of at least [Focus.MIN_MINUTES] that ran to its end. */
    const val FOCUS_BLOCK = 5

    /** Effort XP per local day is capped, so it can't be farmed. */
    const val FOCUS_DAILY_CAP = 60
}

/** Focus timer rules (v1.1). */
object Focus {
    const val MIN_MINUTES = 10
    const val MAX_MINUTES = 240
    val PRESETS = listOf(10, 25, 50)
    const val DEFAULT_MINUTES = 25

    /** Effort XP a block earns when [alreadyToday] effort XP was earned on its day. */
    fun xpFor(alreadyToday: Int): Int = (Xp.FOCUS_DAILY_CAP - alreadyToday).coerceIn(0, Xp.FOCUS_BLOCK)
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
 * Streak rules: a day counts when at least one step is completed (on the completing
 * device's local calendar, see [LocalStamp]). Missing exactly one day is forgiven when the
 * first step of the next active day is completed before noon. A chain is still "current"
 * on its last day, the day after, and — while the grace window is open — until noon the
 * day after that.
 *
 * [Streaks.compute] (from scratch) and [StreakTracker] (incremental) implement these rules
 * separately; tests check they always agree.
 */
object Streaks {
    const val GRACE_DEADLINE_MINUTE = 12 * 60

    /** From scratch: the (local day, minute of day) of every completed step, in any order. */
    fun compute(steps: Collection<Pair<LocalDate, Int>>, today: LocalDate, nowMinute: Int): StreakInfo {
        if (steps.isEmpty()) return StreakInfo(0, 0, null)
        val firstMinuteOfDay = steps.groupBy({ it.first }, { it.second }).mapValues { it.value.min() }.toSortedMap()
        var best = 0
        var chain = 0
        var previous: LocalDate? = null
        var lastPast: LocalDate? = null
        var chainAtLastPast = 0
        for ((day, firstMinute) in firstMinuteOfDay) {
            val gap = previous?.daysUntil(day)
            chain = when {
                gap == 1 -> chain + 1
                gap == 2 && firstMinute < GRACE_DEADLINE_MINUTE -> chain + 1
                else -> 1
            }
            if (chain > best) best = chain
            previous = day
            if (day <= today) {
                lastPast = day
                chainAtLastPast = chain
            }
        }
        val last = lastPast ?: return StreakInfo(0, best, null)
        val sinceLast = last.daysUntil(today)
        val alive = sinceLast <= 1 || (sinceLast == 2 && nowMinute < GRACE_DEADLINE_MINUTE)
        return StreakInfo(if (alive) chainAtLastPast else 0, best, last)
    }

    fun compute(steps: Collection<Pair<LocalDate, Int>>, nowMs: Long, tz: TimeZone): StreakInfo {
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
        return compute(steps, now.date, now.hour * 60 + now.minute)
    }
}

/**
 * Incremental streak. Steps usually arrive in day order (append fast path); a step for an
 * earlier day — e.g. synced late from another device — triggers a rescan of the days.
 */
class StreakTracker {
    /** Each active day's first completion minute. */
    private val days = java.util.TreeMap<LocalDate, Int>()

    /** Every completion minute per day (a completion can be undone, v1.1). */
    private val minutes = HashMap<LocalDate, MutableList<Int>>()
    private var chain = 0
    var best = 0
        private set

    fun copy(): StreakTracker = StreakTracker().also {
        it.days.putAll(days)
        for ((d, m) in minutes) it.minutes[d] = ArrayList(m)
        it.chain = chain
        it.best = best
    }

    /** Takes back one completion (undo): the day stays active only if it has others. */
    fun onUndo(day: LocalDate, minute: Int) {
        val list = minutes[day] ?: return
        if (!list.remove(minute)) return
        if (list.isEmpty()) {
            minutes.remove(day)
            days.remove(day)
        } else {
            days[day] = list.min()
        }
        rescan()
    }

    fun onStep(day: LocalDate, minute: Int) {
        minutes.getOrPut(day) { ArrayList() }.add(minute)
        val last = if (days.isEmpty()) null else days.lastKey()
        val known = days[day]
        if (known != null && known <= minute) return
        days[day] = minOf(known ?: minute, minute)
        if (last == null) {
            chain = 1
        } else if (day > last) {
            val gap = last.daysUntil(day)
            chain = if (gap == 1 || (gap == 2 && minute < Streaks.GRACE_DEADLINE_MINUTE)) chain + 1 else 1
        } else {
            rescan()
            return
        }
        best = maxOf(best, chain)
    }

    private fun rescan() {
        chain = 0
        best = 0
        if (days.isEmpty()) return
        var prev: LocalDate? = null
        for ((d, m) in days) {
            chain = if (prev != null) {
                val gap = prev.daysUntil(d)
                if (gap == 1 || (gap == 2 && m < Streaks.GRACE_DEADLINE_MINUTE)) chain + 1 else 1
            } else {
                1
            }
            best = maxOf(best, chain)
            prev = d
        }
    }

    /** Length of the chain ending at the latest active day, regardless of whether it is still alive. */
    val chainLength: Int get() = chain

    fun info(today: LocalDate, nowMinute: Int): StreakInfo {
        if (days.isEmpty()) return StreakInfo(0, 0, null)
        // Days after today (a device with a fast clock) don't count toward the current streak.
        val past = days.headMap(today, true)
        if (past.isEmpty()) return StreakInfo(0, best, null)
        val last = past.lastKey()
        val chainToLast = if (last == days.lastKey()) chain else chainEndingAt(last)
        val since = last.daysUntil(today)
        val alive = since <= 1 || (since == 2 && nowMinute < Streaks.GRACE_DEADLINE_MINUTE)
        return StreakInfo(if (alive) chainToLast else 0, best, last)
    }

    private fun chainEndingAt(day: LocalDate): Int {
        var length = 0
        var prev: LocalDate? = null
        for ((d, m) in days.headMap(day, true)) {
            length = if (prev != null) {
                val gap = prev.daysUntil(d)
                if (gap == 1 || (gap == 2 && m < Streaks.GRACE_DEADLINE_MINUTE)) length + 1 else 1
            } else {
                1
            }
            prev = d
        }
        return length
    }

    fun info(nowMs: Long, tz: TimeZone): StreakInfo {
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
        return info(now.date, now.hour * 60 + now.minute)
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
    /** Focus blocks that ran to their end, and their minutes (v1.1). */
    val focusBlocks: Int = 0,
    val focusMinutes: Int = 0,
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
    FIRST_FOCUS("Deep breath, deep work", "Finish your first focus block", { it.focusBlocks >= 1 }),
    FOCUS_10H("Ten focused hours", "Spend 10 hours in focus blocks", { it.focusMinutes >= 600 }),
    ;

    companion object {
        /** From scratch: every achievement the counters qualify for. */
        fun unlockedBy(counters: Counters): Set<Achievement> = entries.filter { it.isUnlocked(counters) }.toSet()
    }
}

/**
 * Recounts the lifetime counters from the entities in a state — independently of the
 * incremental bookkeeping in the fold. Tests check `recount(state) == state.counters`.
 */
object Stats {
    fun recount(state: QuestState): Counters {
        val quests = state.quests.values
        val doneSteps = state.steps.values.filter { it.isDone }
        val streak = Streaks.compute(
            doneSteps.mapNotNull { s -> s.doneDay?.let { d -> d to (s.doneMinute ?: 0) } },
            LocalDate(1970, 1, 1), 0,
        )
        // Effort XP, re-derived from the blocks themselves: +5 each, at most 60 per local day.
        val effortByDay = HashMap<LocalDate, Int>()
        var effortXp = 0L
        for (b in state.focusBlocks) {
            val used = effortByDay[b.day] ?: 0
            val xp = Focus.xpFor(used)
            effortByDay[b.day] = used + xp
            effortXp += xp
        }
        return Counters(
            stepsDone = doneSteps.size,
            questsDone = quests.count { it.isRoot && it.status == QuestStatus.DONE },
            subquestsDone = quests.count { !it.isRoot && it.status == QuestStatus.DONE },
            inboxSorted = quests.count { it.isRoot && it.leftInboxAt != null },
            subquestsCreated = quests.count { !it.isRoot },
            proposalsAccepted = state.proposals.values.count { it.status == ProposalStatus.ACCEPTED || it.status == ProposalStatus.EDITED },
            bestStreak = streak.best,
            xp = doneSteps.size.toLong() * Xp.STEP +
                quests.count { it.isRoot && it.status == QuestStatus.DONE } * Xp.QUEST +
                quests.count { !it.isRoot && it.status == QuestStatus.DONE } * Xp.SUBQUEST +
                effortXp,
            focusBlocks = state.focusBlocks.size,
            focusMinutes = state.focusBlocks.sumOf { it.minutes },
        )
    }
}
