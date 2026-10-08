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
) {
    val isRoot: Boolean get() = parentId == null
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
}
