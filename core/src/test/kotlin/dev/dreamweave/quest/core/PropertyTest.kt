// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Randomized end-to-end checks of the acceptance criteria that matter most:
 * - the cap can never be exceeded, whatever arrives (UI commits, merges, rogue events);
 * - a tracked quest is never without a next step except after a notification/widget Done;
 * - replicas converge: any arrival order of the same events folds to the same state;
 * - XP, streak and achievements recomputed from scratch equal the incremental values;
 * - AI actors never change quest state.
 */
class PropertyTest {
    private val tz = TimeZone.of("Europe/Berlin")

    private class Device(val id: String, val tz: TimeZone, var clock: () -> Long) {
        val factory = EventFactory(id, clock = { clock() })
        var folder = Folder(tz)
        val known = LinkedHashMap<String, Event>()

        fun receive(events: Collection<Event>) {
            var outOfOrder = false
            for (e in events) {
                if (e.id in known) continue
                known[e.id] = e
                factory.observe(e.ts)
                if (folder.isOutOfOrder(e)) outOfOrder = true
            }
            folder = if (outOfOrder) Fold.folder(known.values, tz) else folder.also { f ->
                events.filter { !f.hasSeen(it.id) }.sortedWith(Event.ORDER).forEach { f.apply(it) }
            }
        }

        fun tryCommit(pending: List<PendingEvent>, actor: String = Actors.USER): Boolean {
            val events = pending.map { factory.create(it.type, it.payload, actor) }
            return when (val r = Commits.validate(folder, events)) {
                is Commits.Result.Ok -> {
                    folder = r.folder
                    events.forEach { known[it.id] = it }
                    true
                }
                is Commits.Result.Rejected -> false
            }
        }

        /** A buggy importer or old client: appends without validation. */
        fun forceAppend(p: PendingEvent, actor: String = Actors.USER): Event {
            val e = factory.create(p.type, p.payload, actor)
            known[e.id] = e
            folder.apply(e)
            return e
        }
    }

    private fun randomAction(d: Device, rnd: Random, newId: () -> String) {
        val st = d.folder.snapshot()
        val open = st.quests.values.filter { it.status.isOpen }.sortedBy { it.id }
        val roots = open.filter { it.isRoot }
        val q = open.randomOrNull(rnd)
        val root = roots.randomOrNull(rnd)
        val pending: List<PendingEvent> = when (rnd.nextInt(16)) {
            0, 1 -> listOf(Events.questCreated(QuestCreated(newId(), "Quest ${rnd.nextInt(1000)}", status = if (rnd.nextBoolean()) QuestStatus.INBOX else QuestStatus.LATER)))
            2, 3 -> q?.let { listOf(Events.stepSet(StepSet(newId(), it.id, "step ${rnd.nextInt(1000)}", dueAt = if (rnd.nextBoolean()) d.clock() + rnd.nextLong(1, 86_400_000L * 10) else null))) } ?: emptyList()
            4, 5 -> root?.let { listOf(Events.questStatusSet(QuestStatusSet(it.id, QuestStatus.TRACKED, next = if (rnd.nextBoolean()) NextStep(newId(), it.id, "go") else null))) } ?: emptyList()
            6 -> root?.let { listOf(Events.questStatusSet(QuestStatusSet(it.id, listOf(QuestStatus.LATER, QuestStatus.INBOX, QuestStatus.DROPPED).random(rnd)))) } ?: emptyList()
            7, 8, 9 -> {
                val step = st.steps.values.filter { !it.isDone }.sortedBy { it.id }.randomOrNull(rnd)
                step?.let {
                    val via = StepVia.entries.random(rnd)
                    val stamp = LocalStamp.of(d.clock(), if (rnd.nextBoolean()) tz else TimeZone.of("America/New_York"))
                    val plan = Completion.plan(st, it.id, rnd.nextInt(4) == 0)
                    val nextQuest = plan.nextOwnerId
                    listOf(
                        Events.stepCompleted(
                            StepCompleted(
                                it.id, via, finishQuest = plan.ownerIsRoot.not() && rnd.nextInt(4) == 0 || (plan.ownerIsRoot && rnd.nextInt(6) == 0),
                                next = if (nextQuest != null && rnd.nextInt(3) != 0) NextStep(newId(), nextQuest, "next ${rnd.nextInt(99)}") else null,
                                local = stamp.takeIf { rnd.nextInt(4) != 0 },
                            ),
                        ),
                    )
                } ?: emptyList()
            }
            10 -> root?.let { listOf(Events.questCreated(QuestCreated(newId(), "Sub ${rnd.nextInt(99)}", parentId = it.id))) } ?: emptyList()
            11 -> root?.let { r ->
                val child = st.children(r.id).filter { it.status.isOpen }.randomOrNull(rnd)
                listOf(Events.questFocusSet(QuestFocusSet(r.id, child?.id, next = child?.let { NextStep(newId(), it.id, "focus step") })))
            } ?: emptyList()
            12 -> q?.let { listOf(Events.questCompleted(QuestCompleted(it.id))) } ?: emptyList()
            13 -> listOf(Events.slotCapSet(SlotCapSet(rnd.nextInt(Limits.MIN_CAP, Limits.MAX_CAP + 1))))
            14 -> {
                // An AI proposal, sometimes accepted.
                val target = roots.randomOrNull(rnd)
                val pid = newId()
                val payload = if (target != null && rnd.nextBoolean()) ProposalOps.encode(ParkQuestPayload(target.id))
                else ProposalOps.encode(AddQuestPayload("AI idea", nextStep = "ai step", track = rnd.nextBoolean()))
                val op = if (payload.containsKey("questId")) ProposalOp.PARK_QUEST else ProposalOp.ADD_QUEST
                d.tryCommit(listOf(Events.proposalCreated(ProposalCreated(pid, ProposalSource.CLAUDE, "Claude", op.wire, payload, "r"))), Actors.mcp("Claude"))
                if (rnd.nextBoolean()) {
                    try {
                        ProposalRules.decide(d.folder.snapshot(), pid, if (rnd.nextBoolean()) Decision.Accept(track = rnd.nextBoolean()) else Decision.Reject)
                    } catch (e: ProposalException) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
            else -> {
                // Rogue writes that bypass commit validation: they must be neutralized by the fold.
                val target = root?.id ?: return
                when (rnd.nextInt(3)) {
                    0 -> d.forceAppend(Events.questStatusSet(QuestStatusSet(target, QuestStatus.TRACKED)))
                    1 -> d.forceAppend(Events.questCompleted(QuestCompleted(target)), Actors.mcp("evil"))
                    else -> st.openStep(target)?.let { s -> d.forceAppend(Events.stepCompleted(StepCompleted(s.id)), Actors.USER) }
                }
                emptyList()
            }
        }
        if (pending.isNotEmpty()) d.tryCommit(pending)
    }

    /** Folds [events] one at a time, checking the invariants after every single event. */
    private fun foldChecking(events: List<Event>): QuestState {
        val f = Folder(tz)
        val mayBeFlagged = HashSet<String>()
        for (e in events.sortedWith(Event.ORDER)) {
            val before = f.snapshot()
            val outcome = f.apply(e)
            val after = f.snapshot()
            assertTrue(after.tracked.size <= after.slotCap, "cap exceeded after ${e.type}")
            if (Actors.isMcp(e.actor) && outcome == Outcome.Applied) {
                assertEquals(EventTypes.PROPOSAL_CREATED, e.type)
                assertEquals(before.quests, after.quests, "an AI event changed quests")
                assertEquals(before.steps, after.steps, "an AI event changed steps")
            }
            if (outcome == Outcome.Applied && e.type == EventTypes.STEP_COMPLETED) {
                val p = Events.decode(StepCompleted.serializer(), e.payload)!!
                if (p.via.mayLeaveQuestWithoutStep) after.rootOf(before.steps.getValue(p.stepId).questId)?.let { mayBeFlagged += it }
            }
            for (q in after.tracked) {
                if (after.needsNextStep(q.id)) {
                    if (q.id !in mayBeFlagged) fail("tracked quest ${q.id} lost its next step via ${e.type} (${e.actor})")
                } else {
                    mayBeFlagged -= q.id
                }
            }
        }
        return f.snapshot()
    }

    private fun assertSameState(a: QuestState, b: QuestState, msg: String) {
        assertEquals(a.quests, b.quests, msg)
        assertEquals(a.steps, b.steps, msg)
        assertEquals(a.proposals, b.proposals, msg)
        assertEquals(a.slotCap, b.slotCap, msg)
        assertEquals(a.counters, b.counters, msg)
        assertEquals(a.achievements, b.achievements, msg)
    }

    @Test
    fun randomMultiDeviceHistories_holdEveryInvariant() {
        repeat(60) { round ->
            val rnd = Random(1_000 + round)
            var now = Instant.parse("2026-02-01T07:00:00Z").toEpochMilliseconds()
            val idClock = { Ids.newId(now, rnd) }
            val devices = (0 until rnd.nextInt(1, 4)).map { i ->
                val skew = rnd.nextLong(-120_000, 120_000)
                Device(Ids.newId(now + i), tz) { now + skew }
            }
            repeat(rnd.nextInt(40, 160)) {
                now += rnd.nextLong(1, 14L * 3_600_000)
                val d = devices.random(rnd)
                randomAction(d, rnd, idClock)
                if (rnd.nextInt(5) == 0) {
                    // Sync: one device pushes to everyone.
                    val src = devices.random(rnd)
                    devices.forEach { it.receive(src.known.values) }
                }
            }
            val all = devices.flatMap { it.known.values }.distinctBy { it.id }
            devices.forEach { it.receive(all) }

            val reference = foldChecking(all)
            // Convergence: every replica, and any arrival order, reaches the same state.
            devices.forEach { assertSameState(reference, it.folder.snapshot(), "replica ${it.id} round $round") }
            assertSameState(reference, Fold.of(all.shuffled(rnd), tz), "shuffled round $round")
            assertSameState(reference, Fold.of(all + all.take(5), tz), "duplicates round $round")

            // From scratch == incremental.
            val xpScratch = reference.steps.values.count { it.isDone } * Xp.STEP +
                reference.quests.values.count { it.status == QuestStatus.DONE && it.isRoot } * Xp.QUEST +
                reference.quests.values.count { it.status == QuestStatus.DONE && !it.isRoot } * Xp.SUBQUEST
            assertEquals(xpScratch.toLong(), reference.xp, "xp round $round")
            val stepDays = reference.steps.values.mapNotNull { s -> s.doneDay?.let { it to s.doneMinute!! } }
            assertEquals(Streaks.compute(stepDays, now, tz), reference.streak(now), "streak round $round")
            // Counters recounted from the entities (independent of the fold's bookkeeping)...
            val recount = Stats.recount(reference)
            assertEquals(recount, reference.counters, "counters round $round")
            // ...unlock exactly the achievements the fold recorded.
            assertEquals(Achievement.unlockedBy(recount), reference.achievements.keys, "achievements round $round")
            assertEquals(reference.steps.values.count { it.isDone }, reference.counters.stepsDone)
        }
    }
}
