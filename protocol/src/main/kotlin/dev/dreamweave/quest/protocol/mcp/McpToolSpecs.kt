// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol.mcp

import dev.dreamweave.quest.protocol.GetHistoryInput
import dev.dreamweave.quest.protocol.GetQuestInput
import dev.dreamweave.quest.protocol.ListProposalsInput
import dev.dreamweave.quest.protocol.ListQuestsInput
import dev.dreamweave.quest.protocol.NoInput
import dev.dreamweave.quest.protocol.ProposeAddQuestInput
import dev.dreamweave.quest.protocol.ProposeAddSubquestInput
import dev.dreamweave.quest.protocol.ProposeCompleteStepInput
import dev.dreamweave.quest.protocol.ProposeParkQuestInput
import dev.dreamweave.quest.protocol.ProposeScheduleStepInput
import dev.dreamweave.quest.protocol.ProposeSetNextStepInput
import dev.dreamweave.quest.protocol.schema.JsonSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/** One MCP tool: what the model sees in tools/list. */
class McpToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val input: KSerializer<*>,
    val readOnly: Boolean,
    fieldDocs: Map<String, String> = emptyMap(),
) {
    val inputSchema: JsonObject = JsonSchema.inline(input.descriptor, fieldDocs)
}

/** Tool catalogue (docs/MCP_TOOLS.md). Write tools never change anything: they create proposals. */
object McpToolSpecs {
    private const val APPROVAL =
        " This only creates a proposal: nothing changes until the user reviews it in the app and accepts, edits or rejects it. " +
            "Tell the user it is waiting for their approval in the app."

    private val reasonDoc = mapOf("reason" to "Why you suggest this, in one or two sentences. Shown to the user next to the proposal.")
    private val dueDoc = mapOf("due_at" to "Optional ISO-8601 date-time, e.g. 2026-10-02T09:00 (server time zone) or with an offset.")

    val GET_TODAY = McpToolSpec(
        "get_today", "Today",
        "The user's tracked quests (at most their slot cap, usually 5), each with its single next step, plus streak, level and pending proposals. Start here.",
        NoInput.serializer(), readOnly = true,
    )
    val LIST_QUESTS = McpToolSpec(
        "list_quests", "List quests",
        "Lists quests by status: inbox (captured, unsorted), tracked, later (parked), done or dropped. Without a status, lists all open quests.",
        ListQuestsInput.serializer(), readOnly = true,
        mapOf("status" to "One of inbox, tracked, later, done, dropped. Omit for all open quests."),
    )
    val GET_QUEST = McpToolSpec(
        "get_quest", "Quest details",
        "One quest with its why, next step, subquests (and which one is focused) and the ids you need for proposals.",
        GetQuestInput.serializer(), readOnly = true,
        mapOf("quest_id" to "The quest id from get_today or list_quests."),
    )
    val GET_HISTORY = McpToolSpec(
        "get_history", "History",
        "What happened in the last N days: captures, completed steps and quests, XP, accepted proposals.",
        GetHistoryInput.serializer(), readOnly = true,
        mapOf("days" to "How many days back (1–90, default 7)."),
    )
    val GET_STATS = McpToolSpec(
        "get_stats", "Stats",
        "Level, XP, streaks, lifetime counts and unlocked achievements.",
        NoInput.serializer(), readOnly = true,
    )
    val LIST_PROPOSALS = McpToolSpec(
        "list_proposals", "List proposals",
        "Proposals made so far and what the user decided. Check this before proposing something again.",
        ListProposalsInput.serializer(), readOnly = true,
        mapOf("status" to "pending (default), accepted, rejected or edited."),
    )

    val PROPOSE_ADD_QUEST = McpToolSpec(
        "propose_add_quest", "Propose a new quest",
        "Proposes a new quest, optionally with a first next step. With track=true it is proposed for the tracked list, which has a hard cap: " +
            "if every slot is full, propose_park_quest for a tracked quest first or leave track=false (the quest then goes to the inbox)." + APPROVAL,
        ProposeAddQuestInput.serializer(), readOnly = false,
        mapOf(
            "title" to "Short quest title.",
            "why" to "Optional: why it matters to the user.",
            "next_step" to "The very next physical action. Required when track is true.",
            "track" to "Suggest tracking it now (needs a free slot or a pending park proposal).",
        ) + dueDoc + reasonDoc,
    )
    val PROPOSE_SET_NEXT_STEP = McpToolSpec(
        "propose_set_next_step", "Propose a next step",
        "Proposes a new next step for a quest or subquest (replacing its current one). If a quest's visible step comes from a focused subquest, propose it for that subquest." + APPROVAL,
        ProposeSetNextStepInput.serializer(), readOnly = false,
        mapOf("quest_id" to "Quest or subquest id.", "text" to "One concrete, small action.") + dueDoc + reasonDoc,
    )
    val PROPOSE_COMPLETE_STEP = McpToolSpec(
        "propose_complete_step", "Propose marking a step done",
        "Proposes marking a quest's current step as done (e.g. the user told you they did it). The user will also be asked for the next step." + APPROVAL,
        ProposeCompleteStepInput.serializer(), readOnly = false,
        mapOf("quest_id" to "Quest or subquest id.", "step_id" to "Optional; defaults to the quest's current step.") + reasonDoc,
    )
    val PROPOSE_ADD_SUBQUEST = McpToolSpec(
        "propose_add_subquest", "Propose a subquest",
        "Proposes breaking a top-level quest down with a new subquest, optionally with its first step." + APPROVAL,
        ProposeAddSubquestInput.serializer(), readOnly = false,
        mapOf("parent_id" to "Top-level quest id.", "title" to "Subquest title.", "next_step" to "Optional first step of the subquest.") + reasonDoc,
    )
    val PROPOSE_SCHEDULE_STEP = McpToolSpec(
        "propose_schedule_step", "Propose a date for a step",
        "Proposes a date and time (and optionally an alarm) for a quest's current step, or removing its date." + APPROVAL,
        ProposeScheduleStepInput.serializer(), readOnly = false,
        mapOf(
            "quest_id" to "Quest or subquest id.",
            "step_id" to "Optional; defaults to the quest's current step.",
            "due_at" to "ISO-8601 date-time (server time zone unless an offset is given), or null to remove the date.",
            "alarm" to "Ring an alarm at that time.",
        ) + reasonDoc,
    )
    val PROPOSE_PARK_QUEST = McpToolSpec(
        "propose_park_quest", "Propose parking a quest",
        "Proposes moving a quest to Later, freeing a tracked slot. Nothing is lost; it can be tracked again." + APPROVAL,
        ProposeParkQuestInput.serializer(), readOnly = false,
        mapOf("quest_id" to "Top-level quest id.") + reasonDoc,
    )

    val all: List<McpToolSpec> = listOf(
        GET_TODAY, LIST_QUESTS, GET_QUEST, GET_HISTORY, GET_STATS, LIST_PROPOSALS,
        PROPOSE_ADD_QUEST, PROPOSE_SET_NEXT_STEP, PROPOSE_COMPLETE_STEP, PROPOSE_ADD_SUBQUEST, PROPOSE_SCHEDULE_STEP, PROPOSE_PARK_QUEST,
    )
}
