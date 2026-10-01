// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.assertTrue
import kotlin.test.fail

/** Test DSL: one device writing through [Commits], like the app does. */
class Scenario(
    val tz: TimeZone = TimeZone.UTC,
    start: String = "2026-01-05T09:00:00Z",
    deviceId: String = "device-a",
) {
    var now: Long = Instant.parse(start).toEpochMilliseconds()
    val factory = EventFactory(deviceId, clock = { now })
    var folder = Folder(tz)
    val log = mutableListOf<Event>()
    val state: QuestState get() = folder.snapshot()

    fun advanceHours(h: Long) { now += h * 3_600_000 }
    fun advanceMinutes(m: Long) { now += m * 60_000 }
    fun at(iso: String) { now = Instant.parse(iso).toEpochMilliseconds() }

    fun commit(vararg pending: PendingEvent, actor: String = Actors.USER): Commits.Result {
        val events = pending.map { factory.create(it.type, it.payload, actor) }
        val result = Commits.validate(folder, events)
        if (result is Commits.Result.Ok) {
            folder = result.folder
            log += events
        }
        return result
    }

    fun ok(vararg pending: PendingEvent, actor: String = Actors.USER) {
        val r = commit(*pending, actor = actor)
        if (r is Commits.Result.Rejected) fail("expected commit to succeed but it was rejected: ${r.reason}")
    }

    fun rejected(vararg pending: PendingEvent, actor: String = Actors.USER): String {
        val r = commit(*pending, actor = actor)
        assertTrue(r is Commits.Result.Rejected, "expected rejection")
        return r.reason
    }

    fun capture(title: String, status: QuestStatus = QuestStatus.INBOX): String {
        val id = Ids.newId(now)
        ok(Events.questCreated(QuestCreated(id, title, status = status)))
        return id
    }

    fun sub(parentId: String, title: String): String {
        val id = Ids.newId(now)
        ok(Events.questCreated(QuestCreated(id, title, parentId = parentId)))
        return id
    }

    fun step(questId: String, text: String, dueAt: Long? = null, alarm: Boolean = false): String {
        val id = Ids.newId(now)
        ok(Events.stepSet(StepSet(id, questId, text, dueAt, alarm)))
        return id
    }

    fun next(questId: String, text: String) = NextStep(Ids.newId(now), questId, text)

    fun track(questId: String) = ok(Events.questStatusSet(QuestStatusSet(questId, QuestStatus.TRACKED)))

    /** Capture + step + track: a ready tracked quest. Returns quest id. */
    fun tracked(title: String, stepText: String = "first step of $title"): String {
        val q = capture(title)
        step(q, stepText)
        track(q)
        return q
    }

    fun done(stepId: String, nextText: String? = null, nextQuest: String? = null, via: StepVia = StepVia.APP, finish: Boolean = false) {
        val owner = nextQuest ?: state.steps.getValue(stepId).questId
        ok(Events.stepCompleted(StepCompleted(stepId, via, finish, nextText?.let { next(owner, it) })))
    }

    fun stepOf(questId: String): String = state.effectiveStep(questId)?.id ?: fail("no step on $questId")

    fun proposal(op: ProposalOp, payload: Any, reason: String = "because", client: String = "Claude"): String {
        val id = Ids.newId(now)
        ok(
            Events.proposalCreated(
                ProposalCreated(id, ProposalSource.fromClientName(client), client, op.wire, ProposalOps.encode(payload), reason),
            ),
            actor = Actors.mcp(client),
        )
        return id
    }

    fun decide(proposalId: String, decision: Decision) {
        val pending = ProposalRules.decide(state, proposalId, decision)
        ok(*pending.toTypedArray())
    }
}
