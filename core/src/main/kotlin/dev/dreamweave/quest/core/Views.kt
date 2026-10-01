// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/*
 * Read models derived from [QuestState], shared by the app screens and the MCP read
 * tools so both always say the same thing.
 */

data class Progress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

data class TodayCard(
    val quest: Quest,
    /** The single visible next step (null only when [needsNextStep]). */
    val step: Step?,
    /** The quest that owns [step]: the quest itself or its focused subquest. */
    val stepOwner: Quest,
    val progress: Progress,
    val needsNextStep: Boolean,
)

data class TodayView(
    val cards: List<TodayCard>,
    val slotsUsed: Int,
    val slotCap: Int,
    val level: LevelInfo,
    val xp: Long,
    val streak: StreakInfo,
    val pendingProposals: Int,
    /** Days since the last thing the user did, when it is 2 or more (shows the resume card). */
    val resumeAfterDays: Int?,
)

enum class BoardTab { INBOX, LATER }
enum class BoardSort { TOUCHED, CREATED, ALPHABETICAL }

data class CalendarItem(val step: Step, val quest: Quest, val root: Quest, val date: LocalDate)

data class ChronicleEntry(val quest: Quest, val completedAt: Long, val stepsDone: Int, val subquestsDone: Int)

data class LifetimeStats(
    val xp: Long,
    val level: Int,
    val stepsDone: Int,
    val questsDone: Int,
    val subquestsDone: Int,
    val inboxSorted: Int,
    val proposalsAccepted: Int,
    val currentStreak: Int,
    val bestStreak: Int,
    val activeDays: Int,
    val achievementsUnlocked: Int,
    val achievementsTotal: Int,
)

object Views {
    const val RESUME_AFTER_DAYS = 2

    fun progress(state: QuestState, questId: String): Progress {
        val kids = state.children(questId).filter { it.status != QuestStatus.DROPPED }
        return Progress(kids.count { it.status == QuestStatus.DONE }, kids.size)
    }

    fun today(state: QuestState, nowMs: Long, tz: TimeZone): TodayView {
        val cards = state.tracked.map { q ->
            TodayCard(
                quest = q,
                step = state.effectiveStep(q.id),
                stepOwner = state.effectiveStepOwner(q.id) ?: q,
                progress = progress(state, q.id),
                needsNextStep = state.needsNextStep(q.id),
            )
        }
        return TodayView(
            cards = cards,
            slotsUsed = cards.size,
            slotCap = state.slotCap,
            level = state.level,
            xp = state.xp,
            streak = state.streak(nowMs),
            pendingProposals = state.pendingProposals.size,
            resumeAfterDays = daysAway(state, nowMs, tz)?.takeIf { it >= RESUME_AFTER_DAYS },
        )
    }

    /** Whole calendar days between the user's last action and now, or null for a fresh install. */
    fun daysAway(state: QuestState, nowMs: Long, tz: TimeZone): Int? {
        val last = state.lastUserActivityAt ?: return null
        val lastDay = Instant.fromEpochMilliseconds(last).toLocalDateTime(tz).date
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
        return lastDay.daysUntil(today).coerceAtLeast(0)
    }

    fun board(state: QuestState, tab: BoardTab, query: String = "", sort: BoardSort = BoardSort.TOUCHED): List<Quest> {
        val status = if (tab == BoardTab.INBOX) QuestStatus.INBOX else QuestStatus.LATER
        val q = query.trim()
        val items = state.quests.values.filter {
            it.isRoot && it.status == status &&
                (q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.why?.contains(q, ignoreCase = true) == true)
        }
        return when (sort) {
            BoardSort.TOUCHED -> items.sortedWith(compareByDescending<Quest> { it.touchedAt }.thenBy { it.id })
            BoardSort.CREATED -> items.sortedWith(compareByDescending<Quest> { it.createdAt }.thenBy { it.id })
            BoardSort.ALPHABETICAL -> items.sortedWith(compareBy<Quest>({ it.title.lowercase() }, { it.id }))
        }
    }

    /** Dated steps (open ones and ones already done) whose due date falls in [from]..[to]. */
    fun calendar(state: QuestState, from: LocalDate, to: LocalDate, tz: TimeZone): List<CalendarItem> =
        state.steps.values.asSequence()
            .filter { it.dueAt != null && !it.superseded }
            .mapNotNull { s ->
                val quest = state.quests[s.questId] ?: return@mapNotNull null
                if (quest.status == QuestStatus.DROPPED) return@mapNotNull null
                val root = state.rootOf(quest.id)?.let { state.quests[it] } ?: quest
                val date = Instant.fromEpochMilliseconds(s.dueAt!!).toLocalDateTime(tz).date
                if (date < from || date > to) null else CalendarItem(s, quest, root, date)
            }
            .sortedWith(compareBy<CalendarItem>({ it.step.dueAt }, { it.step.id }))
            .toList()

    fun chronicle(state: QuestState): List<ChronicleEntry> =
        state.quests.values.filter { it.isRoot && it.status == QuestStatus.DONE && it.completedAt != null }
            .map { q ->
                val ids = setOf(q.id) + state.children(q.id).map { it.id }
                ChronicleEntry(
                    quest = q,
                    completedAt = q.completedAt!!,
                    stepsDone = state.steps.values.count { it.questId in ids && it.isDone },
                    subquestsDone = state.children(q.id).count { it.status == QuestStatus.DONE },
                )
            }
            .sortedByDescending { it.completedAt }

    fun stats(state: QuestState, nowMs: Long, tz: TimeZone): LifetimeStats {
        val c = state.counters
        val streak = state.streak(nowMs)
        val activeDays = state.steps.values.mapNotNull { it.doneAt }
            .map { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date }
            .toSet().size
        return LifetimeStats(
            xp = c.xp, level = state.level.level, stepsDone = c.stepsDone, questsDone = c.questsDone,
            subquestsDone = c.subquestsDone, inboxSorted = c.inboxSorted, proposalsAccepted = c.proposalsAccepted,
            currentStreak = streak.current, bestStreak = streak.best, activeDays = activeDays,
            achievementsUnlocked = state.achievements.size, achievementsTotal = Achievement.entries.size,
        )
    }
}
