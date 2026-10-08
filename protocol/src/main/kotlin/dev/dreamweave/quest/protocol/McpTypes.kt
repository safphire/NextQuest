// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * MCP tool inputs and outputs. Field names are snake_case on the wire because that is
 * what models handle best; times are ISO-8601 strings in the server's time zone.
 */

// ---- read tool inputs ----------------------------------------------------------------

@Serializable
class NoInput

@Serializable
data class ListQuestsInput(
    /** inbox, tracked, later, done or dropped; omit for all open quests. */
    val status: String? = null,
)

@Serializable
data class GetQuestInput(@SerialName("quest_id") val questId: String)

@Serializable
data class GetHistoryInput(val days: Int = 7)

@Serializable
data class ListProposalsInput(
    /** pending, accepted, rejected or edited; omit for pending. */
    val status: String? = null,
)

// ---- write tool inputs (every one creates a proposal; the user decides in the app) ----

@Serializable
data class ProposeAddQuestInput(
    val title: String,
    val why: String? = null,
    @SerialName("next_step") val nextStep: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    val track: Boolean = false,
    val reason: String,
)

@Serializable
data class ProposeSetNextStepInput(
    @SerialName("quest_id") val questId: String,
    val text: String,
    @SerialName("due_at") val dueAt: String? = null,
    val reason: String,
)

@Serializable
data class ProposeCompleteStepInput(
    @SerialName("quest_id") val questId: String,
    /** Defaults to the quest's current step. */
    @SerialName("step_id") val stepId: String? = null,
    val reason: String,
)

@Serializable
data class ProposeAddSubquestInput(
    @SerialName("parent_id") val parentId: String,
    val title: String,
    @SerialName("next_step") val nextStep: String? = null,
    val reason: String,
)

@Serializable
data class ProposeScheduleStepInput(
    @SerialName("quest_id") val questId: String,
    @SerialName("step_id") val stepId: String? = null,
    /** ISO-8601 date-time, or null to remove the date. */
    @SerialName("due_at") val dueAt: String? = null,
    val alarm: Boolean? = null,
    val reason: String,
)

@Serializable
data class ProposeParkQuestInput(@SerialName("quest_id") val questId: String, val reason: String)

// ---- outputs -------------------------------------------------------------------------

@Serializable
data class StepDto(
    val id: String,
    val text: String,
    @SerialName("due_at") val dueAt: String? = null,
    val alarm: Boolean = false,
    val done: Boolean = false,
    @SerialName("quest_id") val questId: String,
)

@Serializable
data class SubquestDto(
    val id: String,
    val title: String,
    val status: String,
    val focused: Boolean,
    @SerialName("next_step") val nextStep: StepDto? = null,
)

@Serializable
data class QuestDto(
    val id: String,
    val title: String,
    val why: String? = null,
    val status: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("next_step") val nextStep: StepDto? = null,
    @SerialName("needs_next_step") val needsNextStep: Boolean = false,
    @SerialName("subquests_done") val subquestsDone: Int = 0,
    @SerialName("subquests_total") val subquestsTotal: Int = 0,
    val subquests: List<SubquestDto> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("touched_at") val touchedAt: String,
)

@Serializable
data class TodayDto(
    val date: String,
    @SerialName("slots_used") val slotsUsed: Int,
    @SerialName("slot_cap") val slotCap: Int,
    val tracked: List<QuestDto>,
    @SerialName("streak_days") val streakDays: Int,
    val level: Int,
    val xp: Long,
    @SerialName("pending_proposals") val pendingProposals: Int,
    val note: String,
)

@Serializable
data class QuestListDto(val quests: List<QuestDto>)

@Serializable
data class HistoryEntryDto(
    val at: String,
    val text: String,
    val xp: Int = 0,
    @SerialName("quest_id") val questId: String? = null,
    val by: String,
)

@Serializable
data class HistoryDto(val days: Int, val entries: List<HistoryEntryDto>)

@Serializable
data class StatsDto(
    val level: Int,
    val xp: Long,
    @SerialName("xp_to_next_level") val xpToNextLevel: Long,
    @SerialName("steps_done") val stepsDone: Int,
    @SerialName("quests_done") val questsDone: Int,
    @SerialName("subquests_done") val subquestsDone: Int,
    @SerialName("current_streak") val currentStreak: Int,
    @SerialName("best_streak") val bestStreak: Int,
    @SerialName("active_days") val activeDays: Int,
    val achievements: List<String>,
)

@Serializable
data class ProposalDto(
    val id: String,
    val op: String,
    val status: String,
    val source: String,
    val client: String,
    @SerialName("created_at") val createdAt: String,
    val quest: String? = null,
    val before: String? = null,
    val after: String,
    val reason: String? = null,
)

@Serializable
data class ProposalListDto(val proposals: List<ProposalDto>)

@Serializable
data class ProposalCreatedDto(
    @SerialName("proposal_id") val proposalId: String,
    val status: String = "pending",
    val message: String,
)
