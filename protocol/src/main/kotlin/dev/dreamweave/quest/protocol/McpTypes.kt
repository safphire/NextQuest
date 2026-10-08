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
    /** If-then cue: when or where (v1.1). */
    val cue: String? = null,
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
    /** v1.1: a when/where cue; "" removes it. With a cue and no due_at, the date is left alone. */
    val cue: String? = null,
    val reason: String,
)

@Serializable
data class ProposeParkQuestInput(@SerialName("quest_id") val questId: String, val reason: String)

/** How often a practice wants a session (v1.1). */
@Serializable
enum class RhythmKindDto {
    @SerialName("daily") DAILY,
    @SerialName("weekdays") WEEKDAYS,
    @SerialName("per_week") PER_WEEK,
}

@Serializable
data class RhythmDto(
    val kind: RhythmKindDto,
    /** For weekdays: mon, tue, wed, thu, fri, sat, sun. */
    val days: List<String>? = null,
    /** For per_week: sessions per week, 1–7. */
    val times: Int? = null,
    /** Optional reminder time on session days, HH:MM. */
    val reminder: String? = null,
    /** Output only: e.g. "Mon, Wed, Fri · 07:30". */
    val label: String? = null,
)

@Serializable
data class ProposeAddPracticeInput(
    val title: String,
    val rhythm: RhythmDto,
    @SerialName("first_step") val firstStep: String,
    val why: String? = null,
    val reason: String,
)

// ---- outputs -------------------------------------------------------------------------

@Serializable
data class StepDto(
    val id: String,
    val text: String,
    @SerialName("due_at") val dueAt: String? = null,
    val alarm: Boolean = false,
    val done: Boolean = false,
    @SerialName("quest_id") val questId: String,
    /** v1.1: when or where the step happens ("after lunch, at my desk"). */
    val cue: String? = null,
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
    /** v1.1: "quest" or "practice" (an ongoing quest with a rhythm that never completes). */
    val kind: String = "quest",
    val rhythm: RhythmDto? = null,
    /** Practices: sessions logged this week against the rhythm. Informational; missed sessions never pile up. */
    @SerialName("this_week") val thisWeek: WeekDto? = null,
    /** Practices: the date of the next session the rhythm asks for (today when one is due). */
    @SerialName("next_session_on") val nextSessionOn: String? = null,
    /** v1.1 mental contrasting: what's in the way, and the user's if-then plan for it. */
    val obstacle: String? = null,
    val plan: String? = null,
)

@Serializable
data class WeekDto(val done: Int, val target: Int)

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
