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
    /** For a practice: its rhythm, this week's sessions and whether one is due today (v1.1). */
    val practice: Practices.Status? = null,
    /** The step hasn't been touched for the stuck threshold: show a neutral "Stuck?" chip (v1.1). */
    val stuck: Boolean = false,
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
    val focusBlocks: Int = 0,
    val focusMinutes: Int = 0,
)

/** A practice on the calendar: a logged session, or a day its rhythm asks for one (never a missed one). */
data class PracticeDay(val quest: Quest, val date: LocalDate, val done: Boolean)

object Views {
    const val RESUME_AFTER_DAYS = 2

    /** "Stuck?" after this many days without touching a step (setting: 2–7). */
    const val STUCK_DAYS_DEFAULT = 3
    val STUCK_DAYS_RANGE = 2..7

    /**
     * A tracked quest (not a practice) whose visible step hasn't been set, edited, scheduled
     * or focused on for [stuckDays] days. Neutral feedback, never a count or a colour.
     */
    fun isStuck(state: QuestState, rootId: String, nowMs: Long, stuckDays: Int = STUCK_DAYS_DEFAULT): Boolean {
        val q = state.quests[rootId] ?: return false
        if (q.status != QuestStatus.TRACKED || q.isPractice) return false
        val step = state.effectiveStep(rootId) ?: return false
        return nowMs - step.touchedAt >= stuckDays.coerceIn(STUCK_DAYS_RANGE) * 86_400_000L
    }

    fun progress(state: QuestState, questId: String): Progress {
        val kids = state.children(questId).filter { it.status != QuestStatus.DROPPED }
        return Progress(kids.count { it.status == QuestStatus.DONE }, kids.size)
    }

    fun today(state: QuestState, nowMs: Long, tz: TimeZone, stuckDays: Int = STUCK_DAYS_DEFAULT): TodayView {
        val day = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
        val cards = state.tracked.map { q ->
            TodayCard(
                quest = q,
                step = state.effectiveStep(q.id),
                stepOwner = state.effectiveStepOwner(q.id) ?: q,
                progress = progress(state, q.id),
                needsNextStep = state.needsNextStep(q.id),
                practice = Practices.status(state, q, day),
                stuck = isStuck(state, q.id, nowMs, stuckDays),
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

    /**
     * Practices on the calendar from [from] to [to]: days with a logged session, and the
     * days ahead (from [today]) that tracked practices ask for. Days that passed without a
     * session are not shown: no backlog.
     */
    fun practiceCalendar(state: QuestState, from: LocalDate, to: LocalDate, today: LocalDate): List<PracticeDay> {
        val out = mutableListOf<PracticeDay>()
        for (q in state.quests.values.filter { it.isPractice && it.status != QuestStatus.DROPPED }) {
            Practices.sessionDays(state, q.id).filter { it in from..to }.forEach { out += PracticeDay(q, it, done = true) }
            if (q.status == QuestStatus.TRACKED) Practices.plannedDays(state, q, from, to, today).forEach { out += PracticeDay(q, it, done = false) }
        }
        return out.sortedWith(compareBy<PracticeDay>({ it.date }, { it.quest.title.lowercase() }, { it.quest.id }))
    }

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
            focusBlocks = c.focusBlocks, focusMinutes = c.focusMinutes,
        )
    }
}
