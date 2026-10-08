// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class QuestStatus {
    INBOX, TRACKED, LATER, DONE, DROPPED;

    /** Open quests can still receive steps and be completed. */
    val isOpen: Boolean get() = this != DONE && this != DROPPED
}

/** How a step was completed. Only [NOTIFICATION] may leave a tracked quest without a next step (D-101). */
@Serializable
enum class StepVia {
    @SerialName("app") APP,
    @SerialName("notification") NOTIFICATION,
    @SerialName("widget") WIDGET,
    @SerialName("proposal") PROPOSAL;

    /**
     * Completed from a notification action, where no "What's next?" sheet can be shown —
     * the only exception to principle 4. The widget opens the sheet in a dialog instead.
     */
    val mayLeaveQuestWithoutStep: Boolean get() = this == NOTIFICATION
}

data class Quest(
    val id: String,
    val title: String,
    val why: String?,
    val status: QuestStatus,
    val parentId: String?,
    val order: Double,
    val focusedChildId: String?,
    val createdAt: Long,
    val touchedAt: Long,
    val trackedAt: Long? = null,
    val completedAt: Long? = null,
    /** First time this quest left the inbox (counts once toward "inbox items sorted"). */
    val leftInboxAt: Long? = null,
    /** The single current (open) step of this quest, if any. */
    val openStepId: String? = null,
    /** Set for a practice: an ongoing quest that never completes (v1.1, D-1110). */
    val rhythm: Rhythm? = null,
    /** Mental contrasting (v1.1): what's in the way, and the if-then plan for it. */
    val obstacle: String? = null,
    val plan: String? = null,
) {
    val isRoot: Boolean get() = parentId == null
    val isPractice: Boolean get() = rhythm != null
}

data class Step(
    val id: String,
    val questId: String,
    val text: String,
    val dueAt: Long?,
    val alarm: Boolean,
    val createdAt: Long,
    val doneAt: Long? = null,
    val doneVia: StepVia? = null,
    /** The completing device's local calendar day and minute of day — what streaks count. */
    val doneDay: kotlinx.datetime.LocalDate? = null,
    val doneMinute: Int? = null,
    /** Replaced by a newer step before being completed. Kept for history; never shown as "next". */
    val superseded: Boolean = false,
    val snoozedUntil: Long? = null,
    val reminderDismissed: Boolean = false,
    /** An if-then cue: when or where the step happens ("after lunch, at my desk"). */
    val cue: String? = null,
    /**
     * Last time the user worked on this step: set, edited, rescheduled or focused on.
     * "Stuck?" (v1.1) is measured from here.
     */
    val touchedAt: Long = createdAt,
) {
    val isDone: Boolean get() = doneAt != null
}

@Serializable
enum class ProposalSource {
    @SerialName("claude") CLAUDE,
    @SerialName("chatgpt") CHATGPT,
    @SerialName("other") OTHER;

    companion object {
        /** Maps an MCP client name ("Claude", "chatgpt-connector", …) to a source chip. */
        fun fromClientName(name: String): ProposalSource {
            val n = name.lowercase()
            return when {
                "claude" in n || "anthropic" in n -> CLAUDE
                "chatgpt" in n || "openai" in n -> CHATGPT
                else -> OTHER
            }
        }
    }
}

@Serializable
enum class ProposalStatus { PENDING, ACCEPTED, REJECTED, EDITED }

data class Proposal(
    val id: String,
    val source: ProposalSource,
    val client: String,
    val createdAt: Long,
    val op: String,
    val payload: JsonObject,
    val reason: String?,
    val status: ProposalStatus = ProposalStatus.PENDING,
    val decidedAt: Long? = null,
)

object Limits {
    const val MIN_CAP = 3
    const val MAX_CAP = 7
    const val DEFAULT_CAP = 5
    const val MAX_TITLE = 500
    const val MAX_TEXT = 2_000
    const val MAX_CUE = 200
    const val MAX_NOTE = 1_000
}

/**
 * How often a practice wants a session. Days are ISO day numbers (1 = Monday … 7 = Sunday).
 * [reminderMinute] is an optional minute of the day for a reminder on session days.
 */
@Serializable
data class Rhythm(
    val kind: RhythmKind,
    /** For [RhythmKind.WEEKDAYS]: the chosen days. */
    val days: List<Int> = emptyList(),
    /** For [RhythmKind.PER_WEEK]: sessions per week (1–7). */
    val times: Int = 0,
    val reminderMinute: Int? = null,
) {
    /** Null when valid, otherwise what is wrong (shown to an AI model or in a log). */
    fun problem(): String? = when {
        kind == RhythmKind.WEEKDAYS && days.isEmpty() -> "pick at least one weekday"
        kind == RhythmKind.WEEKDAYS && days.any { it !in 1..7 } -> "weekdays are 1 (Monday) to 7 (Sunday)"
        kind == RhythmKind.WEEKDAYS && days.distinct().size != days.size -> "a weekday is listed twice"
        kind == RhythmKind.PER_WEEK && times !in 1..7 -> "times per week must be 1 to 7"
        reminderMinute != null && reminderMinute !in 0 until 24 * 60 -> "reminder time must be within the day"
        else -> null
    }

    /** Sessions a full week asks for: 7, the number of chosen weekdays, or N. */
    val perWeek: Int get() = when (kind) {
        RhythmKind.DAILY -> 7
        RhythmKind.WEEKDAYS -> days.size
        RhythmKind.PER_WEEK -> times
    }

    /** The canonical form stored in state: sorted, deduplicated days; unused fields cleared. */
    fun normalized(): Rhythm = when (kind) {
        RhythmKind.DAILY -> Rhythm(kind, reminderMinute = reminderMinute)
        RhythmKind.WEEKDAYS -> Rhythm(kind, days = days.distinct().sorted(), reminderMinute = reminderMinute)
        RhythmKind.PER_WEEK -> Rhythm(kind, times = times, reminderMinute = reminderMinute)
    }
}

@Serializable
enum class RhythmKind {
    @SerialName("daily") DAILY,
    @SerialName("weekdays") WEEKDAYS,
    @SerialName("per_week") PER_WEEK,
}
