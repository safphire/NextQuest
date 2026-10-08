// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import dev.dreamweave.quest.core.CaptureText
import dev.dreamweave.quest.core.Completion
import dev.dreamweave.quest.core.CompletionUndone
import dev.dreamweave.quest.core.Decision
import dev.dreamweave.quest.core.EventTypes
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.FocusBlockCompleted
import dev.dreamweave.quest.core.FreshStart
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.LocalStamp
import dev.dreamweave.quest.core.NextStep
import dev.dreamweave.quest.core.PendingEvent
import dev.dreamweave.quest.core.ProposalException
import dev.dreamweave.quest.core.ProposalRules
import dev.dreamweave.quest.core.ProposalStatus
import dev.dreamweave.quest.core.QuestCompleted
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestEdited
import dev.dreamweave.quest.core.QuestFocusSet
import dev.dreamweave.quest.core.QuestRhythmSet
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.QuestStatusSet
import dev.dreamweave.quest.core.Rhythm
import dev.dreamweave.quest.core.SlotCapSet
import dev.dreamweave.quest.core.StepCompleted
import dev.dreamweave.quest.core.StepEdited
import dev.dreamweave.quest.core.StepReminderDismissed
import dev.dreamweave.quest.core.StepSet
import dev.dreamweave.quest.core.StepSnoozed
import dev.dreamweave.quest.core.StepVia

/**
 * User intents → events. Used by the UI, notification actions and the widget, so every
 * entry point writes exactly the same events. Every method returns the repository's
 * [CommitResult]; a [CommitResult.Rejected] carries a message fit to show the user.
 */
class QuestActions(private val repo: QuestRepository, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private fun id() = Ids.newId(clock())

    /** The completing device's wall time and local day: what streaks count (DECISIONS D-108). */
    private fun stamp() = LocalStamp.of(clock(), repo.zone)
    private suspend fun commit(vararg p: PendingEvent) = repo.commit(p.toList())

    /**
     * Capture never forces sorting: the quest lands in the inbox with no step required.
     * [rhythm] makes it a practice (v1.1).
     */
    suspend fun capture(title: String, rhythm: Rhythm? = null): CommitResult =
        commit(Events.questCreated(QuestCreated(id(), title.trim(), rhythm = rhythm)))

    /** A pasted list (e.g. an AI's breakdown): one inbox item per line, list markers removed. */
    suspend fun captureList(text: String): CommitResult = captureMany(CaptureText.items(text))

    /** Makes a top-level quest a practice with [rhythm], changes its rhythm, or (null) makes it a plain quest. */
    suspend fun setRhythm(questId: String, rhythm: Rhythm?): CommitResult =
        commit(Events.questRhythmSet(QuestRhythmSet(questId, rhythm)))

    /** Mental contrasting: what's in the way, and the if-then plan for it. Blank clears. */
    suspend fun editObstacle(questId: String, obstacle: String? = null, plan: String? = null): CommitResult =
        commit(
            Events.questEdited(
                QuestEdited(
                    questId,
                    obstacle = obstacle?.takeIf { it.isNotBlank() }, clearObstacle = obstacle != null && obstacle.isBlank(),
                    plan = plan?.takeIf { it.isNotBlank() }, clearPlan = plan != null && plan.isBlank(),
                ),
            ),
        )

    /** A focus block on [stepId] ran to its end (stopping early writes nothing). */
    suspend fun focusBlockDone(questId: String, stepId: String, minutes: Int): CommitResult =
        commit(Events.focusBlockCompleted(FocusBlockCompleted(questId, stepId, minutes, stamp())))

    /** Reverses the completion [eventId] (B2). Refused when it can't be undone exactly. */
    suspend fun undo(eventId: String): CommitResult = commit(Events.completionUndone(CompletionUndone(eventId)))

    /** "Clean slate" (C1): see [FreshStart]. */
    suspend fun freshStart(keepQuestId: String? = null, parkOthers: Boolean = false): CommitResult =
        commit(Events.freshStart(FreshStart(keepQuestId, parkOthers)))

    /** Sets or clears a step's when/where cue ("" or blank clears). */
    suspend fun setCue(stepId: String, cue: String): CommitResult =
        commit(Events.stepEdited(StepEdited(stepId, cue = cue.takeIf { it.isNotBlank() }, clearCue = cue.isBlank())))

    /** Brain dump from first launch / share sheet: many quests in one commit. */
    suspend fun captureMany(titles: List<String>, status: QuestStatus = QuestStatus.INBOX): CommitResult {
        val clean = titles.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return CommitResult.Rejected("Nothing to capture.")
        return repo.commit(clean.map { Events.questCreated(QuestCreated(id(), it, status = status)) })
    }

    suspend fun addSubquest(parentId: String, title: String): CommitResult =
        commit(Events.questCreated(QuestCreated(id(), title, parentId = parentId)))

    suspend fun edit(questId: String, title: String? = null, why: String? = null, clearWhy: Boolean = false): CommitResult =
        commit(Events.questEdited(QuestEdited(questId, title, why, clearWhy)))

    suspend fun setStep(questId: String, text: String, dueAt: Long? = null, alarm: Boolean = false, cue: String? = null): CommitResult =
        commit(Events.stepSet(StepSet(id(), questId, text, dueAt, alarm, cue?.takeIf { it.isNotBlank() })))

    suspend fun schedule(stepId: String, dueAt: Long?, alarm: Boolean): CommitResult =
        commit(Events.stepEdited(StepEdited(stepId, dueAt = dueAt, clearDue = dueAt == null, alarm = alarm)))

    suspend fun editStepText(stepId: String, text: String): CommitResult =
        commit(Events.stepEdited(StepEdited(stepId, text = text)))

    /**
     * Track a quest. [firstStep] is required when the quest has no step yet; [parkQuestId]
     * is the tracked quest to park first when every slot is full (the swap dialog).
     */
    suspend fun track(questId: String, firstStep: String? = null, parkQuestId: String? = null): CommitResult {
        val events = mutableListOf<PendingEvent>()
        parkQuestId?.let { events += Events.questStatusSet(QuestStatusSet(it, QuestStatus.LATER)) }
        // The step belongs to whoever shows it: the focused subquest, if there is one.
        val owner = repo.current().effectiveStepOwner(questId)?.id ?: questId
        val next = firstStep?.trim()?.takeIf { it.isNotEmpty() }?.let { NextStep(id(), owner, it) }
        events += Events.questStatusSet(QuestStatusSet(questId, QuestStatus.TRACKED, next))
        return repo.commit(events)
    }

    /** Track several quests at once (first launch), each with its first step. */
    suspend fun trackMany(firstSteps: Map<String, String>): CommitResult {
        val state = repo.current()
        return repo.commit(
            firstSteps.map { (questId, step) ->
                val owner = state.effectiveStepOwner(questId)?.id ?: questId
                Events.questStatusSet(QuestStatusSet(questId, QuestStatus.TRACKED, NextStep(id(), owner, step)))
            },
        )
    }

    /**
     * First launch: every brain-dumped item becomes a quest in one commit. Items with a
     * first step are tracked (up to the cap); the rest go to Later.
     */
    suspend fun onboard(items: List<Pair<String, String?>>): CommitResult {
        val events = mutableListOf<PendingEvent>()
        for ((title, firstStep) in items) {
            val questId = id()
            val step = firstStep?.trim()?.takeIf { it.isNotEmpty() }
            events += Events.questCreated(QuestCreated(questId, title, status = if (step != null) QuestStatus.INBOX else QuestStatus.LATER))
            if (step != null) events += Events.questStatusSet(QuestStatusSet(questId, QuestStatus.TRACKED, NextStep(id(), questId, step)))
        }
        if (events.isEmpty()) return CommitResult.Rejected("Nothing to save.")
        return repo.commit(events)
    }

    suspend fun park(questId: String) = commit(Events.questStatusSet(QuestStatusSet(questId, QuestStatus.LATER)))
    suspend fun toInbox(questId: String) = commit(Events.questStatusSet(QuestStatusSet(questId, QuestStatus.INBOX)))
    suspend fun drop(questId: String, nextStepText: String? = null, nextOwnerId: String? = null) =
        commit(
            Events.questStatusSet(
                QuestStatusSet(questId, QuestStatus.DROPPED, nextOf(nextOwnerId, nextStepText)),
            ),
        )

    /** Undo a drop: back to the list it was dropped from (Inbox or Later). */
    suspend fun restore(questId: String, to: QuestStatus = QuestStatus.INBOX) =
        commit(Events.questStatusSet(QuestStatusSet(questId, if (to == QuestStatus.LATER) QuestStatus.LATER else QuestStatus.INBOX)))

    suspend fun completeQuest(questId: String, nextStepText: String? = null, nextOwnerId: String? = null) =
        commit(Events.questCompleted(QuestCompleted(questId, nextOf(nextOwnerId, nextStepText))))

    suspend fun focus(rootId: String, childId: String?, nextStepText: String? = null) =
        commit(Events.questFocusSet(QuestFocusSet(rootId, childId, nextOf(childId ?: rootId, nextStepText))))

    /**
     * Done from the app: always paired with the next step (the "What's next?" sheet) or
     * with finishing the quest. [Completion.plan] says which quest gets the next step.
     */
    suspend fun completeStep(
        stepId: String,
        nextStepText: String?,
        finishOwner: Boolean,
        nextDueAt: Long? = null,
        nextCue: String? = null,
    ): CommitResult {
        val state = repo.current()
        if (state.steps[stepId] == null) return CommitResult.Rejected("That step no longer exists.")
        val plan = Completion.plan(state, stepId, finishOwner)
        return commit(
            Events.stepCompleted(StepCompleted(stepId, StepVia.APP, finishOwner, nextOf(plan.nextOwnerId, nextStepText, nextDueAt, nextCue), stamp())),
        )
    }

    /** Done from a notification action: no sheet, so the quest may be flagged "Needs next step". */
    suspend fun completeStepFromNotification(stepId: String): CommitResult =
        commit(Events.stepCompleted(StepCompleted(stepId, StepVia.NOTIFICATION, local = stamp())))

    /** Done from the widget's "What's next?" dialog: like the app, with a next step or finishing. */
    suspend fun completeStepFromWidget(
        stepId: String,
        nextStepText: String?,
        finishOwner: Boolean,
        nextDueAt: Long? = null,
        nextCue: String? = null,
    ): CommitResult {
        val state = repo.current()
        if (state.steps[stepId] == null) return CommitResult.Rejected("That step no longer exists.")
        val plan = Completion.plan(state, stepId, finishOwner)
        return commit(
            Events.stepCompleted(StepCompleted(stepId, StepVia.WIDGET, finishOwner, nextOf(plan.nextOwnerId, nextStepText, nextDueAt, nextCue), stamp())),
        )
    }

    suspend fun snooze(stepId: String, until: Long) = commit(Events.stepSnoozed(StepSnoozed(stepId, until)))
    suspend fun dismissReminder(stepId: String) = commit(Events.stepReminderDismissed(StepReminderDismissed(stepId)))
    suspend fun setSlotCap(cap: Int) = commit(Events.slotCapSet(SlotCapSet(cap)))

    suspend fun decide(proposalId: String, decision: Decision): CommitResult {
        val state = repo.current()
        val pending = try {
            ProposalRules.decide(state, proposalId, decision, stamp())
        } catch (e: ProposalException) {
            return CommitResult.Rejected(e.message ?: "This proposal can't be applied.")
        }
        return repo.commit(pending)
    }

    /**
     * "Accept all": accepts every pending proposal that can be accepted as-is, one commit
     * each, and returns how many were accepted. Proposals that need input (a next step, a
     * swap) are left pending for the user.
     */
    suspend fun acceptAll(): Int {
        var accepted = 0
        val ids = repo.current().pendingProposals.map { it.id }
        for (id in ids) {
            val p = repo.current().proposals[id] ?: continue
            if (p.status != ProposalStatus.PENDING) continue
            if (decide(id, Decision.Accept()) is CommitResult.Ok) accepted++
        }
        return accepted
    }

    private fun nextOf(ownerId: String?, text: String?, dueAt: Long? = null, cue: String? = null): NextStep? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return NextStep(id(), ownerId ?: return null, t, dueAt = dueAt, cue = cue?.trim()?.takeIf { it.isNotEmpty() })
    }

    companion object {
        /** The completion event a successful Done wrote: what Undo reverses. */
        fun completionOf(result: CommitResult): String? =
            (result as? CommitResult.Ok)?.change?.events?.lastOrNull { it.type == EventTypes.STEP_COMPLETED || it.type == EventTypes.QUEST_COMPLETED }?.id
    }
}
