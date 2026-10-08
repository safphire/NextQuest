// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.core.Actors
import dev.dreamweave.quest.core.AddQuestPayload
import dev.dreamweave.quest.core.AddSubquestPayload
import dev.dreamweave.quest.core.CompleteStepPayload
import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.ParkQuestPayload
import dev.dreamweave.quest.core.Proposal
import dev.dreamweave.quest.core.ProposalCreated
import dev.dreamweave.quest.core.ProposalOp
import dev.dreamweave.quest.core.ProposalOps
import dev.dreamweave.quest.core.ProposalRules
import dev.dreamweave.quest.core.ProposalSource
import dev.dreamweave.quest.core.ProposalStatus
import dev.dreamweave.quest.core.Quest
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.ScheduleStepPayload
import dev.dreamweave.quest.core.SetNextStepPayload
import dev.dreamweave.quest.core.Step
import dev.dreamweave.quest.core.Views
import dev.dreamweave.quest.protocol.GetHistoryInput
import dev.dreamweave.quest.protocol.GetQuestInput
import dev.dreamweave.quest.protocol.HistoryDto
import dev.dreamweave.quest.protocol.HistoryEntryDto
import dev.dreamweave.quest.protocol.ListProposalsInput
import dev.dreamweave.quest.protocol.ListQuestsInput
import dev.dreamweave.quest.protocol.ProposalCreatedDto
import dev.dreamweave.quest.protocol.ProposalDto
import dev.dreamweave.quest.protocol.ProposalListDto
import dev.dreamweave.quest.protocol.ProposeAddQuestInput
import dev.dreamweave.quest.protocol.ProposeAddSubquestInput
import dev.dreamweave.quest.protocol.ProposeCompleteStepInput
import dev.dreamweave.quest.protocol.ProposeParkQuestInput
import dev.dreamweave.quest.protocol.ProposeScheduleStepInput
import dev.dreamweave.quest.protocol.ProposeSetNextStepInput
import dev.dreamweave.quest.protocol.QuestDto
import dev.dreamweave.quest.protocol.QuestListDto
import dev.dreamweave.quest.protocol.StatsDto
import dev.dreamweave.quest.protocol.StepDto
import dev.dreamweave.quest.protocol.SubquestDto
import dev.dreamweave.quest.protocol.TodayDto
import dev.dreamweave.quest.protocol.mcp.McpToolSpecs
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A tool's answer: structured content, or an error message the model can act on. */
sealed interface ToolResult {
    data class Ok(val json: JsonElement) : ToolResult
    data class Error(val message: String) : ToolResult
}

/**
 * The MCP tools (docs/MCP_TOOLS.md). Read tools answer from the folded state; write
 * tools never change quest state — they validate against the core rules and store a
 * `proposal.created` event that the user decides on in the app.
 */
class McpTools(private val store: EventStore, private val config: Config, private val clock: () -> Long) {
    private val tz get() = config.timeZone

    fun call(grant: Grant, name: String, args: JsonObject): ToolResult = try {
        when (name) {
            McpToolSpecs.GET_TODAY.name -> ok(TodayDto.serializer(), today(grant))
            McpToolSpecs.LIST_QUESTS.name -> ok(QuestListDto.serializer(), listQuests(grant, decode(ListQuestsInput.serializer(), args)))
            McpToolSpecs.GET_QUEST.name -> getQuest(grant, decode(GetQuestInput.serializer(), args))
            McpToolSpecs.GET_HISTORY.name -> ok(HistoryDto.serializer(), history(grant, decode(GetHistoryInput.serializer(), args)))
            McpToolSpecs.GET_STATS.name -> ok(StatsDto.serializer(), stats(grant))
            McpToolSpecs.LIST_PROPOSALS.name -> ok(ProposalListDto.serializer(), proposals(grant, decode(ListProposalsInput.serializer(), args)))
            McpToolSpecs.PROPOSE_ADD_QUEST.name -> decode(ProposeAddQuestInput.serializer(), args).let { i ->
                propose(grant, ProposalOp.ADD_QUEST, AddQuestPayload(i.title, i.why, i.nextStep, i.dueAt?.let(::parseTime), i.track), i.reason)
            }
            McpToolSpecs.PROPOSE_SET_NEXT_STEP.name -> decode(ProposeSetNextStepInput.serializer(), args).let { i ->
                propose(grant, ProposalOp.SET_NEXT_STEP, SetNextStepPayload(i.questId, i.text, i.dueAt?.let(::parseTime)), i.reason)
            }
            McpToolSpecs.PROPOSE_COMPLETE_STEP.name -> decode(ProposeCompleteStepInput.serializer(), args).let { i ->
                val step = resolveStep(grant, i.questId, i.stepId, "complete")
                propose(grant, ProposalOp.COMPLETE_STEP, CompleteStepPayload(step.questId, step.id), i.reason)
            }
            McpToolSpecs.PROPOSE_ADD_SUBQUEST.name -> decode(ProposeAddSubquestInput.serializer(), args).let { i ->
                propose(grant, ProposalOp.ADD_SUBQUEST, AddSubquestPayload(i.parentId, i.title, i.nextStep), i.reason)
            }
            McpToolSpecs.PROPOSE_SCHEDULE_STEP.name -> decode(ProposeScheduleStepInput.serializer(), args).let { i ->
                val step = resolveStep(grant, i.questId, i.stepId, "schedule")
                propose(grant, ProposalOp.SCHEDULE_STEP, ScheduleStepPayload(step.questId, step.id, i.dueAt?.let(::parseTime), i.alarm), i.reason)
            }
            McpToolSpecs.PROPOSE_PARK_QUEST.name -> decode(ProposeParkQuestInput.serializer(), args).let { i ->
                propose(grant, ProposalOp.PARK_QUEST, ParkQuestPayload(i.questId), i.reason)
            }
            else -> ToolResult.Error("unknown tool '$name'")
        }
    } catch (e: BadInput) {
        ToolResult.Error(e.message ?: "invalid arguments")
    }

    private class BadInput(message: String) : Exception(message)

    private fun <T> decode(s: KSerializer<T>, args: JsonObject): T = try {
        ToolJson.decodeFromJsonElement(s, args)
    } catch (e: IllegalArgumentException) {
        throw BadInput("invalid arguments: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
    }

    private fun <T> ok(s: KSerializer<T>, value: T) = ToolResult.Ok(ToolJson.encodeToJsonElement(s, value))

    // ---- time ------------------------------------------------------------------------------

    private fun iso(ms: Long): String {
        val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)
        return dt.toString().take(16)
    }

    /** ISO date-time with offset, local date-time (server zone), or a date (09:00). */
    private fun parseTime(s: String): Long {
        val t = s.trim()
        runCatching { return Instant.parse(t).toEpochMilliseconds() }
        runCatching { return LocalDateTime.parse(t).toInstant(tz).toEpochMilliseconds() }
        runCatching { return LocalDateTime(LocalDate.parse(t), kotlinx.datetime.LocalTime(9, 0)).toInstant(tz).toEpochMilliseconds() }
        throw BadInput("due_at must be ISO-8601, e.g. 2026-10-02T09:00 (got '$s')")
    }

    // ---- mapping ---------------------------------------------------------------------------

    private fun stepDto(s: Step?) = s?.let { StepDto(it.id, it.text, it.dueAt?.let(::iso), it.alarm, it.isDone, it.questId) }

    private fun questDto(state: QuestState, q: Quest): QuestDto {
        val kids = state.children(q.id).filter { it.status != QuestStatus.DROPPED }
        val progress = Views.progress(state, q.id)
        return QuestDto(
            id = q.id, title = q.title, why = q.why, status = q.status.name.lowercase(), parentId = q.parentId,
            nextStep = stepDto(if (q.isRoot) state.effectiveStep(q.id) else state.openStep(q.id)),
            needsNextStep = state.needsNextStep(q.id),
            subquestsDone = progress.done, subquestsTotal = progress.total,
            subquests = kids.map { c -> SubquestDto(c.id, c.title, c.status.name.lowercase(), q.focusedChildId == c.id, stepDto(state.openStep(c.id))) },
            createdAt = iso(q.createdAt), touchedAt = iso(q.touchedAt),
        )
    }

    private fun proposalDto(state: QuestState, p: Proposal): ProposalDto {
        val preview = ProposalRules.preview(state, p)
        return ProposalDto(
            id = p.id, op = p.op, status = p.status.name.lowercase(), source = p.source.name.lowercase(), client = p.client,
            createdAt = iso(p.createdAt), quest = preview.questTitle, before = preview.before, after = preview.after, reason = p.reason,
        )
    }

    // ---- read tools -----------------------------------------------------------------------

    private fun today(g: Grant): TodayDto {
        val state = store.state(g.userId)
        val now = clock()
        val v = Views.today(state, now, tz)
        return TodayDto(
            date = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).date.toString(),
            slotsUsed = v.slotsUsed, slotCap = v.slotCap,
            tracked = v.cards.map { questDto(state, it.quest) },
            streakDays = v.streak.current, level = v.level.level, xp = v.xp, pendingProposals = v.pendingProposals,
            note = "Each tracked quest shows one next step. Every change you suggest becomes a proposal the user approves in the app.",
        )
    }

    private fun listQuests(g: Grant, i: ListQuestsInput): QuestListDto {
        val state = store.state(g.userId)
        val wanted = i.status?.lowercase()?.let { s ->
            QuestStatus.entries.firstOrNull { it.name.lowercase() == s } ?: throw BadInput("status must be one of inbox, tracked, later, done, dropped")
        }
        val quests = state.quests.values
            .filter { it.isRoot && if (wanted == null) it.status.isOpen else it.status == wanted }
            .sortedByDescending { it.touchedAt }
            .take(200)
        return QuestListDto(quests.map { questDto(state, it) })
    }

    private fun getQuest(g: Grant, i: GetQuestInput): ToolResult {
        val state = store.state(g.userId)
        val q = state.quests[i.questId] ?: return ToolResult.Error("quest ${i.questId} does not exist")
        return ok(QuestDto.serializer(), questDto(state, q))
    }

    private fun history(g: Grant, i: GetHistoryInput): HistoryDto {
        val days = i.days.coerceIn(1, 90)
        val state = store.state(g.userId)
        val since = clock() - days * 86_400_000L
        val entries = state.history.filter { it.ts >= since }.takeLast(500).map { h ->
            HistoryEntryDto(iso(h.ts), h.text, h.xp, h.questId, if (Actors.isUser(h.actor)) "user" else (Actors.mcpClient(h.actor) ?: h.actor))
        }
        return HistoryDto(days, entries)
    }

    private fun stats(g: Grant): StatsDto {
        val state = store.state(g.userId)
        val s = Views.stats(state, clock(), tz)
        return StatsDto(
            level = s.level, xp = s.xp, xpToNextLevel = state.level.xpForNextLevel - state.level.xpIntoLevel,
            stepsDone = s.stepsDone, questsDone = s.questsDone, subquestsDone = s.subquestsDone,
            currentStreak = s.currentStreak, bestStreak = s.bestStreak, activeDays = s.activeDays,
            achievements = state.achievements.keys.map { it.title },
        )
    }

    private fun proposals(g: Grant, i: ListProposalsInput): ProposalListDto {
        val state = store.state(g.userId)
        val wanted = (i.status ?: "pending").lowercase().let { s ->
            ProposalStatus.entries.firstOrNull { it.name.lowercase() == s } ?: throw BadInput("status must be pending, accepted, rejected or edited")
        }
        return ProposalListDto(
            state.proposals.values.filter { it.status == wanted }.sortedByDescending { it.createdAt }.take(100).map { proposalDto(state, it) },
        )
    }

    // ---- write tools: proposals only ----------------------------------------------------

    /** The given step (which must belong to [questId]), or the quest's current one. */
    private fun resolveStep(g: Grant, questId: String, stepId: String?, verb: String): Step {
        val state = store.state(g.userId)
        if (stepId != null) {
            val step = state.steps[stepId] ?: throw BadInput("step $stepId does not exist")
            if (step.questId != questId) throw BadInput("step $stepId belongs to quest ${step.questId}, not $questId; pass quest_id=${step.questId}")
            return step
        }
        return state.openStep(questId) ?: state.effectiveStep(questId) ?: throw BadInput("quest $questId has no current step to $verb")
    }

    private fun propose(g: Grant, op: ProposalOp, payload: Any, reason: String): ToolResult {
        if (reason.isBlank()) return ToolResult.Error("reason is required: tell the user why you suggest this")
        val json = ProposalOps.encode(payload)
        val id = Ids.newId(clock())
        val created = Events.proposalCreated(
            ProposalCreated(id, ProposalSource.fromClientName(g.clientName), g.clientName, op.wire, json, reason.trim().take(1_000)),
        )
        return try {
            // Validated under the store's lock against the state it is appended to, so two
            // concurrent proposals can't both pass against the same stale snapshot.
            store.appendServerEvents(g.userId, listOf(created), Actors.mcp(g.clientName)) { state -> ProposalRules.validate(state, op.wire, json) }
            ok(
                ProposalCreatedDto.serializer(),
                ProposalCreatedDto(id, "pending", "Proposal saved. Nothing has changed yet: the user will accept, edit or reject it in the app."),
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.Error("Not proposed: ${e.message}")
        }
    }
}

/** Lenient on input (unknown keys ignored), complete on output. */
val ToolJson = kotlinx.serialization.json.Json(from = CoreJson) {
    encodeDefaults = true
    explicitNulls = false
}
