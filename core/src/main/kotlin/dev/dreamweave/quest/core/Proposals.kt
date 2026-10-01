// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * AI proposes, the user ratifies. A proposal is inert data inside a `proposal.created`
 * event; nothing in this file mutates state. [ProposalRules.decide] turns a user's
 * decision into ordinary user events, which the fold validates like any other edit.
 */

@Serializable
data class AddQuestPayload(
    val title: String,
    val why: String? = null,
    val nextStep: String? = null,
    val dueAt: Long? = null,
    /** The AI suggests tracking it now; the user still chooses Track or To Inbox. */
    val track: Boolean = false,
)

@Serializable
data class SetNextStepPayload(val questId: String, val text: String, val dueAt: Long? = null)

@Serializable
data class CompleteStepPayload(val questId: String, val stepId: String)

@Serializable
data class AddSubquestPayload(val parentId: String, val title: String, val nextStep: String? = null)

@Serializable
data class ScheduleStepPayload(val questId: String, val stepId: String, val dueAt: Long?, val alarm: Boolean? = null)

@Serializable
data class ParkQuestPayload(val questId: String)

enum class ProposalOp(val wire: String, val label: String) {
    ADD_QUEST("add_quest", "New quest"),
    SET_NEXT_STEP("set_next_step", "Set next step"),
    COMPLETE_STEP("complete_step", "Complete step"),
    ADD_SUBQUEST("add_subquest", "Add subquest"),
    SCHEDULE_STEP("schedule_step", "Schedule step"),
    PARK_QUEST("park_quest", "Park quest"),
    ;

    companion object {
        fun fromWire(op: String): ProposalOp? = entries.firstOrNull { it.wire == op }
    }
}

object ProposalOps {
    fun label(op: String): String = ProposalOp.fromWire(op)?.label ?: op

    /** The quest a payload is about, when it names one. */
    fun questIdOf(payload: JsonObject): String? =
        ((payload["questId"] ?: payload["parentId"]) as? JsonPrimitive)?.contentOrNull

    fun encode(payload: Any): JsonObject = when (payload) {
        is AddQuestPayload -> enc(AddQuestPayload.serializer(), payload)
        is SetNextStepPayload -> enc(SetNextStepPayload.serializer(), payload)
        is CompleteStepPayload -> enc(CompleteStepPayload.serializer(), payload)
        is AddSubquestPayload -> enc(AddSubquestPayload.serializer(), payload)
        is ScheduleStepPayload -> enc(ScheduleStepPayload.serializer(), payload)
        is ParkQuestPayload -> enc(ParkQuestPayload.serializer(), payload)
        else -> throw IllegalArgumentException("not a proposal payload: ${payload::class}")
    }

    private fun <T> enc(s: KSerializer<T>, v: T) = CoreJson.encodeToJsonElement(s, v).jsonObject

    /** Decodes a payload into its typed class, or null when malformed / unknown op. */
    fun decode(op: String, payload: JsonObject): Any? = when (ProposalOp.fromWire(op)) {
        ProposalOp.ADD_QUEST -> Events.decode(AddQuestPayload.serializer(), payload)
        ProposalOp.SET_NEXT_STEP -> Events.decode(SetNextStepPayload.serializer(), payload)
        ProposalOp.COMPLETE_STEP -> Events.decode(CompleteStepPayload.serializer(), payload)
        ProposalOp.ADD_SUBQUEST -> Events.decode(AddSubquestPayload.serializer(), payload)
        ProposalOp.SCHEDULE_STEP -> Events.decode(ScheduleStepPayload.serializer(), payload)
        ProposalOp.PARK_QUEST -> Events.decode(ParkQuestPayload.serializer(), payload)
        null -> null
    }
}

/** What the proposal card shows: op label, the quest, and a before → after diff. */
data class ProposalPreview(
    val opLabel: String,
    val questTitle: String,
    val before: String?,
    val after: String,
    /** A new quest that the AI suggests tracking — the card offers To Inbox / Track. */
    val isNewQuestToTrack: Boolean = false,
    /** Accepting as-is is impossible right now (e.g. the quest was finished meanwhile). */
    val stale: String? = null,
)

/** A user's decision on a proposal. */
sealed interface Decision {
    data object Reject : Decision

    /**
     * Accept, optionally with edits.
     * @param edited replacement payload (the card's Edit action); recorded as `proposal.edited`.
     * @param track for add_quest: true = Track, false = To Inbox, null = follow the proposal.
     * @param parkQuestId for Track when every slot is full: the tracked quest to park first (swap).
     * @param nextStepText a next step the user typed when one is required (e.g. tracking a
     *   quest without one, or completing the only step of a tracked quest).
     * @param finishQuest for complete_step: also complete the quest.
     */
    data class Accept(
        val edited: JsonObject? = null,
        val track: Boolean? = null,
        val parkQuestId: String? = null,
        val nextStepText: String? = null,
        val finishQuest: Boolean = false,
    ) : Decision
}

object ProposalRules {
    /**
     * Validates a proposal before it is stored (server side, at MCP call time).
     * Returns human-readable problems; empty means valid. Messages are written for
     * the AI model, so they say how to fix the proposal.
     */
    fun validate(state: QuestState, op: String, payload: JsonObject): List<String> {
        val kind = ProposalOp.fromWire(op) ?: return listOf("unknown operation '$op'")
        val p = ProposalOps.decode(op, payload) ?: return listOf("malformed payload for $op")
        val errors = mutableListOf<String>()
        fun openQuest(id: String, what: String = "quest"): Quest? {
            val q = state.quests[id]
            when {
                q == null -> errors += "$what $id does not exist"
                !q.status.isOpen -> errors += "$what “${q.title}” is already ${q.status.name.lowercase()}"
                else -> return q
            }
            return null
        }
        fun checkText(text: String?, what: String, max: Int) {
            if (text != null && text.isBlank()) errors += "$what must not be empty"
            if (text != null && text.trim().length > max) errors += "$what is longer than $max characters"
        }
        when (kind) {
            ProposalOp.ADD_QUEST -> {
                p as AddQuestPayload
                checkText(p.title, "title", Limits.MAX_TITLE)
                checkText(p.nextStep, "nextStep", Limits.MAX_TEXT)
                if (p.track) {
                    if (p.nextStep.isNullOrBlank()) errors += "a tracked quest needs a nextStep"
                    if (availableSlotsForNewTracks(state) <= 0) {
                        errors += "all ${state.slotCap} slots are full; propose_park_quest for a tracked quest " +
                            "first, or set track=false so the quest goes to the inbox"
                    }
                }
            }
            ProposalOp.SET_NEXT_STEP -> {
                p as SetNextStepPayload
                openQuest(p.questId)
                checkText(p.text, "text", Limits.MAX_TEXT)
            }
            ProposalOp.COMPLETE_STEP -> {
                p as CompleteStepPayload
                openQuest(p.questId)
                val s = state.steps[p.stepId]
                when {
                    s == null -> errors += "step ${p.stepId} does not exist"
                    s.questId != p.questId -> errors += "step ${p.stepId} does not belong to quest ${p.questId}"
                    s.isDone -> errors += "step “${s.text}” is already done"
                }
            }
            ProposalOp.ADD_SUBQUEST -> {
                p as AddSubquestPayload
                val parent = openQuest(p.parentId, "parent quest")
                if (parent != null && !parent.isRoot) errors += "subquests can only be added to top-level quests"
                checkText(p.title, "title", Limits.MAX_TITLE)
                checkText(p.nextStep, "nextStep", Limits.MAX_TEXT)
            }
            ProposalOp.SCHEDULE_STEP -> {
                p as ScheduleStepPayload
                openQuest(p.questId)
                val s = state.steps[p.stepId]
                if (s == null || s.questId != p.questId || state.quests[p.questId]?.openStepId != p.stepId) {
                    errors += "step ${p.stepId} is not the current step of quest ${p.questId}"
                }
            }
            ProposalOp.PARK_QUEST -> {
                p as ParkQuestPayload
                val q = openQuest(p.questId)
                if (q != null && !q.isRoot) errors += "only top-level quests can be parked"
                if (q != null && q.status == QuestStatus.LATER) errors += "quest is already parked"
            }
        }
        return errors
    }

    /**
     * Free slots for proposals that want to track a new quest: free slots now, plus
     * tracked quests with a pending park proposal, minus pending track proposals that
     * already claimed one. This is how "never exceed the cap without a park" is checked
     * for AI proposals; the fold enforces the hard cap when the user accepts.
     */
    fun availableSlotsForNewTracks(state: QuestState): Int {
        val pending = state.pendingProposals
        val parks = pending.count { p ->
            p.op == ProposalOp.PARK_QUEST.wire &&
                (ProposalOps.decode(p.op, p.payload) as? ParkQuestPayload)
                    ?.let { state.quests[it.questId]?.status == QuestStatus.TRACKED } == true
        }
        val claims = pending.count { p ->
            p.op == ProposalOp.ADD_QUEST.wire && (ProposalOps.decode(p.op, p.payload) as? AddQuestPayload)?.track == true
        }
        return state.freeSlots + parks - claims
    }

    fun preview(state: QuestState, proposal: Proposal): ProposalPreview {
        val label = ProposalOps.label(proposal.op)
        val p = ProposalOps.decode(proposal.op, proposal.payload)
            ?: return ProposalPreview(label, "?", null, "This proposal can't be read by this version of the app.", stale = "unreadable")
        val problems = if (proposal.status == ProposalStatus.PENDING) validate(state, proposal.op, proposal.payload) else emptyList()
        val stale = problems.firstOrNull()
        fun title(id: String) = state.quests[id]?.title ?: "(missing quest)"
        return when (p) {
            is AddQuestPayload -> ProposalPreview(
                label, p.title.trim(), null,
                buildString {
                    append(p.title.trim())
                    p.nextStep?.let { append(" — next: ").append(it.trim()) }
                },
                isNewQuestToTrack = p.track,
                // Slots being full is not "stale" for a new quest: the card offers To Inbox or a swap.
                stale = problems.firstOrNull { "slots are full" !in it },
            )
            is SetNextStepPayload -> ProposalPreview(
                label, title(p.questId), state.effectiveStepFor(p.questId)?.text ?: "(no next step)", p.text.trim(), stale = stale,
            )
            is CompleteStepPayload -> ProposalPreview(
                label, title(p.questId), state.steps[p.stepId]?.text ?: "(missing step)", "Done ✓", stale = stale,
            )
            is AddSubquestPayload -> ProposalPreview(
                label, title(p.parentId), null,
                p.title.trim() + (p.nextStep?.let { " — next: ${it.trim()}" } ?: ""), stale = stale,
            )
            is ScheduleStepPayload -> ProposalPreview(
                label, title(p.questId),
                state.steps[p.stepId]?.let { s -> s.text + (s.dueAt?.let { " · due ${formatTs(it)}" } ?: " · no date") },
                (state.steps[p.stepId]?.text ?: "step") + (p.dueAt?.let { " · due ${formatTs(it)}" } ?: " · no date") +
                    if (p.alarm == true) " · alarm" else "",
                stale = stale,
            )
            is ParkQuestPayload -> ProposalPreview(
                label, title(p.questId), state.quests[p.questId]?.status?.name?.lowercase(), "later", stale = stale,
            )
            else -> ProposalPreview(label, "?", null, "?", stale = "unreadable")
        }
    }

    private fun QuestState.effectiveStepFor(questId: String): Step? =
        if (quests[questId]?.isRoot == true) effectiveStep(questId) else openStep(questId)

    private fun formatTs(ms: Long): String =
        kotlinx.datetime.Instant.fromEpochMilliseconds(ms).toString().replace("T", " ").take(16) + " UTC"

    /**
     * Turns a decision into the events to commit, in order: the resulting domain events
     * (actor `user`) followed by `proposal.accepted` / `proposal.edited` /
     * `proposal.rejected`. The caller stamps them with its [EventFactory] and commits
     * them through [Commits.validate], so every invariant is checked again at accept time.
     *
     * Every id the acceptance creates is derived from the proposal id ([Ids.derive]), so
     * accepting the same proposal on two offline devices cannot create duplicates.
     *
     * @throws ProposalException when the decision cannot be applied (the message says why).
     */
    fun decide(state: QuestState, proposalId: String, decision: Decision): List<PendingEvent> {
        val newIdFor = { role: String -> Ids.derive(proposalId, role) }
        val proposal = state.proposals[proposalId] ?: throw ProposalException("This proposal no longer exists.")
        if (proposal.status != ProposalStatus.PENDING) throw ProposalException("This proposal was already decided.")
        if (decision is Decision.Reject) return listOf(Events.proposalRejected(proposalId))
        decision as Decision.Accept
        val payloadJson = decision.edited ?: proposal.payload
        val p = ProposalOps.decode(proposal.op, payloadJson) ?: throw ProposalException("This proposal can't be read.")
        val out = mutableListOf<PendingEvent>()
        fun nextFor(questId: String, text: String?): NextStep? =
            text?.trim()?.takeIf { it.isNotEmpty() }?.let { NextStep(newIdFor("next"), questId, it) }

        when (p) {
            is AddQuestPayload -> {
                val questId = newIdFor("quest")
                out += Events.questCreated(QuestCreated(questId, p.title, p.why))
                val stepText = decision.nextStepText ?: p.nextStep
                stepText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    out += Events.stepSet(StepSet(newIdFor("step"), questId, it, p.dueAt))
                }
                if (decision.track ?: p.track) {
                    if (stepText.isNullOrBlank()) throw ProposalException(NEEDS_NEXT_STEP)
                    decision.parkQuestId?.let { out += Events.questStatusSet(QuestStatusSet(it, QuestStatus.LATER)) }
                    out += Events.questStatusSet(QuestStatusSet(questId, QuestStatus.TRACKED))
                }
            }
            is SetNextStepPayload -> out += Events.stepSet(StepSet(newIdFor("step"), p.questId, p.text, p.dueAt))
            is CompleteStepPayload -> {
                if (state.steps[p.stepId] == null) throw ProposalException("That step no longer exists.")
                val plan = Completion.plan(state, p.stepId, decision.finishQuest)
                val next = plan.nextOwnerId?.let { nextFor(it, decision.nextStepText) }
                if (plan.nextRequired && next == null) throw ProposalException(NEEDS_NEXT_STEP)
                out += Events.stepCompleted(StepCompleted(p.stepId, StepVia.PROPOSAL, decision.finishQuest, next))
            }
            is AddSubquestPayload -> {
                val childId = newIdFor("quest")
                out += Events.questCreated(QuestCreated(childId, p.title, parentId = p.parentId))
                p.nextStep?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Events.stepSet(StepSet(newIdFor("step"), childId, it)) }
            }
            is ScheduleStepPayload -> out += Events.stepEdited(
                StepEdited(p.stepId, dueAt = p.dueAt, clearDue = p.dueAt == null, alarm = p.alarm),
            )
            is ParkQuestPayload -> out += Events.questStatusSet(QuestStatusSet(p.questId, QuestStatus.LATER))
        }
        out += if (decision.edited != null) Events.proposalEdited(proposalId, payloadJson) else Events.proposalAccepted(proposalId)
        return out
    }

    /** Thrown by [decide] when the user must type a next step first (the app shows the sheet). */
    const val NEEDS_NEXT_STEP = "This quest needs a next step."
}

class ProposalException(message: String) : Exception(message)
