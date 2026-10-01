// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.datetime.toLocalDateTime

/**
 * A new current step, attached to events that would otherwise leave a tracked quest
 * without one (completing a step, finishing or dropping a focused subquest, changing
 * focus). Carrying it inside the same event keeps every event atomic, so the fold can
 * check "every tracked quest has a next step" event by event, on every path.
 */
@Serializable
data class NextStep(
    val stepId: String,
    val questId: String,
    val text: String,
    val dueAt: Long? = null,
    val alarm: Boolean = false,
)

@Serializable
data class QuestCreated(
    val questId: String,
    val title: String,
    val why: String? = null,
    val parentId: String? = null,
    /** INBOX or LATER only — tracking always goes through [QuestStatusSet]. */
    val status: QuestStatus = QuestStatus.INBOX,
    val order: Double? = null,
)

@Serializable
data class QuestEdited(
    val questId: String,
    val title: String? = null,
    val why: String? = null,
    val clearWhy: Boolean = false,
)

@Serializable
data class QuestStatusSet(val questId: String, val status: QuestStatus, val next: NextStep? = null)

@Serializable
data class QuestCompleted(val questId: String, val next: NextStep? = null)

@Serializable
data class QuestFocusSet(val questId: String, val childId: String? = null, val next: NextStep? = null)

@Serializable
data class QuestReordered(val questId: String, val order: Double)

@Serializable
data class StepSet(
    val stepId: String,
    val questId: String,
    val text: String,
    val dueAt: Long? = null,
    val alarm: Boolean = false,
)

@Serializable
data class StepEdited(
    val stepId: String,
    val text: String? = null,
    val dueAt: Long? = null,
    val clearDue: Boolean = false,
    val alarm: Boolean? = null,
)

/**
 * When and on which local day a step was completed, as seen by the completing device.
 * Streaks count these local days, so they don't depend on event timestamps (which follow
 * the hybrid clock) or on the time zone of whichever replica folds the log.
 */
@Serializable
data class LocalStamp(val at: Long, val day: String, val minute: Int) {
    companion object {
        fun of(nowMs: Long, tz: kotlinx.datetime.TimeZone): LocalStamp {
            val dt = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
            return LocalStamp(nowMs, dt.date.toString(), dt.hour * 60 + dt.minute)
        }
    }
}

@Serializable
data class StepCompleted(
    val stepId: String,
    val via: StepVia = StepVia.APP,
    /** Also complete the quest (or subquest) the step belongs to. */
    val finishQuest: Boolean = false,
    val next: NextStep? = null,
    val local: LocalStamp? = null,
)

@Serializable
data class StepSnoozed(val stepId: String, val until: Long)

@Serializable
data class StepReminderDismissed(val stepId: String)

@Serializable
data class SlotCapSet(val cap: Int)

@Serializable
data class ProposalCreated(
    val proposalId: String,
    val source: ProposalSource,
    val client: String,
    val op: String,
    val payload: JsonObject,
    val reason: String? = null,
)

@Serializable
data class ProposalDecided(val proposalId: String)

@Serializable
data class ProposalEdited(val proposalId: String, val payload: JsonObject)

/** Typed construction of pending events, so callers never hand-write JSON. */
object Events {
    private fun <T> pending(type: String, serializer: KSerializer<T>, value: T) =
        PendingEvent(type, CoreJson.encodeToJsonElement(serializer, value).jsonObject)

    fun questCreated(p: QuestCreated) = pending(EventTypes.QUEST_CREATED, QuestCreated.serializer(), p)
    fun questEdited(p: QuestEdited) = pending(EventTypes.QUEST_EDITED, QuestEdited.serializer(), p)
    fun questStatusSet(p: QuestStatusSet) = pending(EventTypes.QUEST_STATUS_SET, QuestStatusSet.serializer(), p)
    fun questCompleted(p: QuestCompleted) = pending(EventTypes.QUEST_COMPLETED, QuestCompleted.serializer(), p)
    fun questFocusSet(p: QuestFocusSet) = pending(EventTypes.QUEST_FOCUS_SET, QuestFocusSet.serializer(), p)
    fun questReordered(p: QuestReordered) = pending(EventTypes.QUEST_REORDERED, QuestReordered.serializer(), p)
    fun stepSet(p: StepSet) = pending(EventTypes.STEP_SET, StepSet.serializer(), p)
    fun stepEdited(p: StepEdited) = pending(EventTypes.STEP_EDITED, StepEdited.serializer(), p)
    fun stepCompleted(p: StepCompleted) = pending(EventTypes.STEP_COMPLETED, StepCompleted.serializer(), p)
    fun stepSnoozed(p: StepSnoozed) = pending(EventTypes.STEP_SNOOZED, StepSnoozed.serializer(), p)
    fun stepReminderDismissed(p: StepReminderDismissed) =
        pending(EventTypes.STEP_REMINDER_DISMISSED, StepReminderDismissed.serializer(), p)
    fun slotCapSet(p: SlotCapSet) = pending(EventTypes.SLOT_CAP_SET, SlotCapSet.serializer(), p)
    fun proposalCreated(p: ProposalCreated) = pending(EventTypes.PROPOSAL_CREATED, ProposalCreated.serializer(), p)
    fun proposalAccepted(id: String) = pending(EventTypes.PROPOSAL_ACCEPTED, ProposalDecided.serializer(), ProposalDecided(id))
    fun proposalRejected(id: String) = pending(EventTypes.PROPOSAL_REJECTED, ProposalDecided.serializer(), ProposalDecided(id))
    fun proposalEdited(id: String, payload: JsonObject) =
        pending(EventTypes.PROPOSAL_EDITED, ProposalEdited.serializer(), ProposalEdited(id, payload))

    /** Decodes a payload; returns null when it is malformed. */
    fun <T> decode(serializer: KSerializer<T>, payload: JsonObject): T? =
        try {
            CoreJson.decodeFromJsonElement(serializer, payload)
        } catch (e: IllegalArgumentException) {
            null
        }
}
