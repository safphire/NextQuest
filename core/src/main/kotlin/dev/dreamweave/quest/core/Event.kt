// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One immutable fact in the append-only log. State is a fold over these.
 *
 * @property seq per-device monotonic counter (1, 2, 3, …).
 * @property ts milliseconds since the epoch; per device strictly increasing (see [EventFactory]).
 * @property actor `user` or `mcp:<client>`.
 */
@Serializable
data class Event(
    val id: String,
    val deviceId: String,
    val seq: Long,
    val ts: Long,
    val actor: String,
    val type: String,
    val payload: JsonObject,
) {
    companion object {
        /** Deterministic total order used by every replica: `(ts, deviceId, seq)`, then id. */
        val ORDER: Comparator<Event> = compareBy<Event>({ it.ts }, { it.deviceId }, { it.seq }, { it.id })
    }
}

object Actors {
    const val USER = "user"
    private const val MCP_PREFIX = "mcp:"

    fun mcp(client: String): String = MCP_PREFIX + client.trim().ifEmpty { "unknown" }
    fun isUser(actor: String) = actor == USER
    fun isMcp(actor: String) = actor.startsWith(MCP_PREFIX) && actor.length > MCP_PREFIX.length
    fun mcpClient(actor: String): String? = if (isMcp(actor)) actor.removePrefix(MCP_PREFIX) else null
}

object EventTypes {
    const val QUEST_CREATED = "quest.created"
    const val QUEST_EDITED = "quest.edited"
    const val QUEST_STATUS_SET = "quest.status_set"
    const val QUEST_COMPLETED = "quest.completed"
    const val QUEST_FOCUS_SET = "quest.focus_set"
    const val QUEST_REORDERED = "quest.reordered"
    const val STEP_SET = "step.set"
    const val STEP_EDITED = "step.edited"
    const val STEP_COMPLETED = "step.completed"
    const val STEP_SNOOZED = "step.snoozed"
    const val STEP_REMINDER_DISMISSED = "step.reminder_dismissed"
    const val SLOT_CAP_SET = "settings.slot_cap_set"
    const val PROPOSAL_CREATED = "proposal.created"
    const val PROPOSAL_ACCEPTED = "proposal.accepted"
    const val PROPOSAL_REJECTED = "proposal.rejected"
    const val PROPOSAL_EDITED = "proposal.edited"

    // v1.1
    const val QUEST_RHYTHM_SET = "quest.rhythm_set"
    const val FOCUS_BLOCK_COMPLETED = "focus.block_completed"
    const val COMPLETION_UNDONE = "completion.undone"
    const val FRESH_START = "reset.fresh_start"

    val ALL = setOf(
        QUEST_CREATED, QUEST_EDITED, QUEST_STATUS_SET, QUEST_COMPLETED, QUEST_FOCUS_SET, QUEST_REORDERED,
        STEP_SET, STEP_EDITED, STEP_COMPLETED, STEP_SNOOZED, STEP_REMINDER_DISMISSED, SLOT_CAP_SET,
        PROPOSAL_CREATED, PROPOSAL_ACCEPTED, PROPOSAL_REJECTED, PROPOSAL_EDITED,
        QUEST_RHYTHM_SET, FOCUS_BLOCK_COMPLETED, COMPLETION_UNDONE, FRESH_START,
    )
}

/** An event before it gets an id, sequence number and timestamp. */
data class PendingEvent(val type: String, val payload: JsonObject)

/**
 * Stamps events for one device. Timestamps are a hybrid clock: never earlier than the
 * wall clock, strictly increasing per device, and never earlier than any event this
 * device has already seen ([observe]) — so a local edit always orders after the
 * remote events it was made on top of, even with skewed clocks.
 */
class EventFactory(
    val deviceId: String,
    private var lastSeq: Long = 0,
    private var lastTs: Long = 0,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: (Long) -> String = { Ids.newId(it) },
) {
    val currentSeq: Long get() = lastSeq
    val currentTs: Long get() = lastTs

    /**
     * Notes a remote event's timestamp, so this device's next event sorts after it.
     * Causality wins over clock sanity: a peer with a fast clock pulls ordering
     * timestamps forward, but streaks and "done at" use each completion's own local
     * stamp ([LocalStamp]), so nothing the user sees about their days is affected.
     */
    fun observe(ts: Long) {
        if (ts > lastTs) lastTs = ts
    }

    fun create(type: String, payload: JsonObject, actor: String = Actors.USER): Event {
        val ts = maxOf(clock(), if (lastTs == Long.MAX_VALUE) lastTs else lastTs + 1)
        lastTs = ts
        lastSeq += 1
        return Event(newId(ts), deviceId, lastSeq, ts, actor, type, payload)
    }

    fun createAll(pending: List<PendingEvent>, actor: String = Actors.USER): List<Event> =
        pending.map { create(it.type, it.payload, actor) }
}
