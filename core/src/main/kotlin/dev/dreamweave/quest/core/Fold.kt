// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/** One line of human-readable history, derived from an accepted event. */
data class HistoryEntry(
    val eventId: String,
    val ts: Long,
    val actor: String,
    val type: String,
    /** The quest (or subquest) the event is about; null for global events. */
    val questId: String?,
    /** The root quest of [questId], so a quest's history includes its subquests. */
    val rootId: String?,
    val text: String,
    val xp: Int = 0,
)

data class SkippedEvent(val eventId: String, val type: String, val actor: String, val reason: String)

sealed interface Outcome {
    data object Applied : Outcome
    data class Skipped(val reason: String) : Outcome
}

/**
 * Immutable snapshot of everything derived from the event log.
 * Produced by [Folder.snapshot]; cheap to read, never mutated.
 */
class QuestState internal constructor(
    val quests: Map<String, Quest>,
    val steps: Map<String, Step>,
    val proposals: Map<String, Proposal>,
    val slotCap: Int,
    val counters: Counters,
    /** Unlocked achievements with the timestamp of the event that unlocked them. */
    val achievements: Map<Achievement, Long>,
    /** Chronological history of accepted events. */
    val history: List<HistoryEntry>,
    /** Events the fold refused, with the reason (diagnostics, import reports). */
    val skipped: List<SkippedEvent>,
    val lastUserActivityAt: Long?,
    val appliedCount: Int,
    private val streakTracker: StreakTracker,
    /** The time zone this replica uses for "today" (streak liveness, calendar). */
    val timeZone: TimeZone,
) {
    val xp: Long get() = counters.xp
    val level: LevelInfo get() = Levels.infoFor(counters.xp)

    fun streak(nowMs: Long): StreakInfo = streakTracker.info(nowMs, timeZone)

    /** Tracked root quests, in the order they were tracked. */
    val tracked: List<Quest> by lazy {
        quests.values.filter { it.status == QuestStatus.TRACKED && it.isRoot }
            .sortedWith(compareBy<Quest>({ it.trackedAt ?: 0 }, { it.id }))
    }

    val freeSlots: Int get() = (slotCap - tracked.size).coerceAtLeast(0)

    fun children(questId: String): List<Quest> =
        quests.values.filter { it.parentId == questId }.sortedWith(compareBy<Quest>({ it.order }, { it.id }))

    fun openStep(questId: String): Step? = quests[questId]?.openStepId?.let { steps[it] }

    /**
     * The single step shown for a root quest: the focused subquest's step when a
     * subquest is focused, otherwise the quest's own step.
     */
    fun effectiveStep(rootId: String): Step? = effectiveStepId(quests, rootId)?.let { steps[it] }

    /** The quest that owns the effective step (root or focused subquest). */
    fun effectiveStepOwner(rootId: String): Quest? {
        val root = quests[rootId] ?: return null
        val focused = root.focusedChildId?.let { quests[it] }
        return if (focused != null && focused.status.isOpen) focused else root
    }

    /** Tracked but without a next step — only possible after a notification Done. */
    fun needsNextStep(rootId: String): Boolean {
        val q = quests[rootId] ?: return false
        return q.status == QuestStatus.TRACKED && effectiveStepId(quests, rootId) == null
    }

    fun rootOf(questId: String): String? = rootOf(quests, questId)

    /** History lines for a quest and its subquests, newest last. */
    fun historyOf(rootId: String, limit: Int = 10): List<HistoryEntry> =
        history.filter { it.rootId == rootId }.takeLast(limit)

    val pendingProposals: List<Proposal> by lazy {
        proposals.values.filter { it.status == ProposalStatus.PENDING }.sortedWith(compareBy<Proposal>({ it.createdAt }, { it.id }))
    }

    internal companion object {
        fun effectiveStepId(quests: Map<String, Quest>, rootId: String): String? {
            val root = quests[rootId] ?: return null
            val focused = root.focusedChildId?.let { quests[it] }
            return if (focused != null && focused.status.isOpen) focused.openStepId else root.openStepId
        }

        fun rootOf(quests: Map<String, Quest>, questId: String): String? {
            var current = quests[questId] ?: return null
            var guard = 0
            while (current.parentId != null && guard++ < 32) {
                current = quests[current.parentId] ?: return current.id
            }
            return current.id
        }
    }
}

/**
 * The fold: applies events, in canonical order, to mutable state. Each event is a
 * transaction — it is validated against the invariants and either fully applied or
 * skipped with a reason. Because every replica folds the same events in the same order
 * with the same rules, every replica reaches the same state (see docs/SYNC.md).
 *
 * Invariants enforced here, for every path (UI, import, sync, MCP):
 * - never more tracked root quests than the slot cap;
 * - a tracked quest always has a next step, except right after a step completed from a
 *   notification (the quest then shows "Needs next step");
 * - AI clients (`mcp:*` actors) can only create proposals; everything else needs `user`.
 */
class Folder(val tz: TimeZone) {
    private val quests = HashMap<String, Quest>()
    private val steps = HashMap<String, Step>()
    private val proposals = HashMap<String, Proposal>()
    private val appliedIds = HashSet<String>()
    /** Every event id ever offered (applied or skipped): a re-delivered event is a duplicate, never re-judged. */
    private val seenIds = HashSet<String>()
    /** (deviceId, seq) pairs in use: a device never writes two events with one sequence number. */
    private val deviceSeqs = HashSet<String>()
    private val stepless = HashSet<String>()
    private var cap = Limits.DEFAULT_CAP
    private var counters = Counters()
    private val achievements = LinkedHashMap<Achievement, Long>()
    private val history = ArrayList<HistoryEntry>()
    private val skipped = ArrayList<SkippedEvent>()
    private var streak = StreakTracker()
    private var lastUserActivityAt: Long? = null
    private var lastApplied: Event? = null

    /** True when [event] sorts before the last applied event, so the log must be refolded. */
    fun isOutOfOrder(event: Event): Boolean = lastApplied?.let { Event.ORDER.compare(event, it) < 0 } ?: false

    fun hasApplied(eventId: String): Boolean = eventId in appliedIds

    /** True once [eventId] was offered to this folder, whether it was applied or skipped. */
    fun hasSeen(eventId: String): Boolean = eventId in seenIds

    fun copy(): Folder = Folder(tz).also { f ->
        f.quests.putAll(quests)
        f.steps.putAll(steps)
        f.proposals.putAll(proposals)
        f.appliedIds.addAll(appliedIds)
        f.seenIds.addAll(seenIds)
        f.deviceSeqs.addAll(deviceSeqs)
        f.stepless.addAll(stepless)
        f.cap = cap
        f.counters = counters
        f.achievements.putAll(achievements)
        f.history.addAll(history)
        f.skipped.addAll(skipped)
        f.streak = streak.copy()
        f.lastUserActivityAt = lastUserActivityAt
        f.lastApplied = lastApplied
    }

    fun snapshot(): QuestState = QuestState(
        quests = HashMap(quests),
        steps = HashMap(steps),
        proposals = HashMap(proposals),
        slotCap = cap,
        counters = counters,
        achievements = LinkedHashMap(achievements),
        history = ArrayList(history),
        skipped = ArrayList(skipped),
        lastUserActivityAt = lastUserActivityAt,
        appliedCount = appliedIds.size,
        streakTracker = streak.copy(),
        timeZone = tz,
    )

    fun apply(event: Event): Outcome {
        if (!seenIds.add(event.id)) return Outcome.Skipped("duplicate event") // idempotent; not recorded
        val tx = Tx(event)
        val reason = try {
            checkEnvelope(event)
            deviceSeqs += "${event.deviceId}#${event.seq}" // reserved even if the event is skipped below
            checkActor(event)
            when (event.type) {
                EventTypes.QUEST_CREATED -> tx.questCreated(decode(event, QuestCreated.serializer()))
                EventTypes.QUEST_EDITED -> tx.questEdited(decode(event, QuestEdited.serializer()))
                EventTypes.QUEST_STATUS_SET -> tx.questStatusSet(decode(event, QuestStatusSet.serializer()))
                EventTypes.QUEST_COMPLETED -> tx.questCompleted(decode(event, QuestCompleted.serializer()))
                EventTypes.QUEST_FOCUS_SET -> tx.questFocusSet(decode(event, QuestFocusSet.serializer()))
                EventTypes.QUEST_REORDERED -> tx.questReordered(decode(event, QuestReordered.serializer()))
                EventTypes.STEP_SET -> tx.stepSet(decode(event, StepSet.serializer()))
                EventTypes.STEP_EDITED -> tx.stepEdited(decode(event, StepEdited.serializer()))
                EventTypes.STEP_COMPLETED -> tx.stepCompleted(decode(event, StepCompleted.serializer()))
                EventTypes.STEP_SNOOZED -> tx.stepSnoozed(decode(event, StepSnoozed.serializer()))
                EventTypes.STEP_REMINDER_DISMISSED ->
                    tx.stepReminderDismissed(decode(event, StepReminderDismissed.serializer()))
                EventTypes.SLOT_CAP_SET -> tx.slotCapSet(decode(event, SlotCapSet.serializer()))
                EventTypes.PROPOSAL_CREATED -> tx.proposalCreated(decode(event, ProposalCreated.serializer()))
                EventTypes.PROPOSAL_ACCEPTED -> tx.proposalDecided(decode(event, ProposalDecided.serializer()), ProposalStatus.ACCEPTED)
                EventTypes.PROPOSAL_REJECTED -> tx.proposalDecided(decode(event, ProposalDecided.serializer()), ProposalStatus.REJECTED)
                EventTypes.PROPOSAL_EDITED -> tx.proposalEdited(decode(event, ProposalEdited.serializer()))
                else -> throw Reject("unknown event type '${event.type}'")
            }
            tx.checkInvariants()
            tx.commit()
            null
        } catch (r: Reject) {
            tx.rollback()
            r.reason
        }
        lastApplied = maxOf(lastApplied ?: event, event, Event.ORDER)
        if (reason != null) {
            skipped += SkippedEvent(event.id, event.type, event.actor, reason)
            return Outcome.Skipped(reason)
        }
        return Outcome.Applied
    }

    private class Reject(val reason: String) : Exception(reason, null, false, false)

    private fun <T> decode(event: Event, serializer: kotlinx.serialization.KSerializer<T>): T =
        Events.decode(serializer, event.payload) ?: throw Reject("malformed payload")

    private fun checkEnvelope(event: Event) {
        if (!Ids.isValid(event.id)) throw Reject("event id is not a UUIDv7")
        if (!Ids.isValid(event.deviceId)) throw Reject("device id is not a UUIDv7")
        if (event.seq < 1) throw Reject("sequence number must be positive")
        if (event.ts <= 0) throw Reject("timestamp must be positive")
        if ("${event.deviceId}#${event.seq}" in deviceSeqs) throw Reject("sequence number reused by another event")
    }

    private fun checkActor(event: Event) {
        val isUser = Actors.isUser(event.actor)
        val isMcp = Actors.isMcp(event.actor)
        when {
            !isUser && !isMcp -> throw Reject("unknown actor '${event.actor}'")
            isMcp && event.type != EventTypes.PROPOSAL_CREATED ->
                throw Reject("AI clients can only create proposals")
            isUser && event.type == EventTypes.PROPOSAL_CREATED ->
                throw Reject("proposals are created by AI clients")
        }
    }

    /** One event's worth of changes, with an undo log so a rejected event leaves no trace. */
    private inner class Tx(val event: Event) {
        val ts = event.ts
        private val questBefore = HashMap<String, Quest?>()
        private val stepBefore = HashMap<String, Step?>()
        private val proposalBefore = HashMap<String, Proposal?>()
        private val touchedRoots = LinkedHashSet<String>()
        private val capBefore = cap
        private var allowFlagFor: String? = null
        private var xp = 0
        private var c = counters
        private var completedDay: Pair<kotlinx.datetime.LocalDate, Int>? = null
        private val lines = ArrayList<HistoryEntry>()

        // ---- primitives -------------------------------------------------------------

        fun quest(id: String): Quest = quests[id] ?: throw Reject("unknown quest $id")
        fun step(id: String): Step = steps[id] ?: throw Reject("unknown step $id")

        fun put(q: Quest) {
            if (!questBefore.containsKey(q.id)) questBefore[q.id] = quests[q.id]
            quests[q.id] = q
            QuestState.rootOf(quests, q.id)?.let { touchedRoots += it }
        }

        fun put(s: Step) {
            if (!stepBefore.containsKey(s.id)) stepBefore[s.id] = steps[s.id]
            steps[s.id] = s
            QuestState.rootOf(quests, s.questId)?.let { touchedRoots += it }
        }

        fun put(p: Proposal) {
            if (!proposalBefore.containsKey(p.id)) proposalBefore[p.id] = proposals[p.id]
            proposals[p.id] = p
        }

        fun line(questId: String?, text: String, xp: Int = 0) {
            lines += HistoryEntry(
                event.id, ts, event.actor, event.type, questId,
                questId?.let { QuestState.rootOf(quests, it) }, text, xp,
            )
        }

        private fun newId(id: String, existing: Map<String, *>, what: String) {
            if (!Ids.isValid(id)) throw Reject("$what id is not a UUIDv7")
            if (id in existing) throw Reject("$what $id already exists")
        }

        private fun title(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) throw Reject("title is empty")
            if (t.length > Limits.MAX_TITLE) throw Reject("title is too long")
            return t
        }

        private fun text(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) throw Reject("step text is empty")
            if (t.length > Limits.MAX_TEXT) throw Reject("step text is too long")
            return t
        }

        private fun touch(q: Quest) {
            put(q.copy(touchedAt = maxOf(q.touchedAt, ts)))
            q.parentId?.let { quests[it] }?.let { parent -> touch(parent) }
        }

        private fun touch(questId: String) = touch(quest(questId))

        private fun trackedCount(): Int = quests.values.count { it.status == QuestStatus.TRACKED && it.isRoot }

        // ---- shared operations -----------------------------------------------------

        private fun setStep(stepId: String, questId: String, rawText: String, dueAt: Long?, alarm: Boolean) {
            newId(stepId, steps, "step")
            val q = quest(questId)
            if (!q.status.isOpen) throw Reject("cannot add a step to a finished quest")
            val t = text(rawText)
            q.openStepId?.let { old -> steps[old]?.let { put(it.copy(superseded = true)) } }
            put(Step(stepId, questId, t, dueAt, alarm, createdAt = ts))
            put(quest(questId).copy(openStepId = stepId))
            touch(questId)
            line(questId, "Next step: $t")
        }

        private fun applyNext(next: NextStep?) {
            if (next != null) setStep(next.stepId, next.questId, next.text, next.dueAt, next.alarm)
        }

        private fun advanceFocusFrom(parentId: String, leavingChild: Quest) {
            val parent = quest(parentId)
            if (parent.focusedChildId != leavingChild.id) return
            val open = quests.values
                .filter { it.parentId == parentId && it.id != leavingChild.id && it.status.isOpen }
                .sortedWith(compareBy<Quest>({ it.order }, { it.id }))
            val nextChild = open.firstOrNull { it.order > leavingChild.order } ?: open.firstOrNull()
            put(parent.copy(focusedChildId = nextChild?.id))
            nextChild?.let { line(it.id, "Focus moved to “${it.title}”") }
        }

        private fun completeQuest(questId: String) {
            val q = quest(questId)
            if (!q.status.isOpen) throw Reject("quest is already finished")
            q.openStepId?.let { old -> steps[old]?.let { put(it.copy(superseded = true)) } }
            put(q.copy(status = QuestStatus.DONE, completedAt = ts, openStepId = null))
            touch(questId)
            if (q.isRoot) {
                xp += Xp.QUEST
                c = c.copy(questsDone = c.questsDone + 1)
                line(questId, "Completed quest “${q.title}”", Xp.QUEST)
            } else {
                xp += Xp.SUBQUEST
                c = c.copy(subquestsDone = c.subquestsDone + 1)
                line(questId, "Completed subquest “${q.title}”", Xp.SUBQUEST)
                advanceFocusFrom(q.parentId!!, q)
            }
        }

        // ---- handlers ---------------------------------------------------------------

        fun questCreated(p: QuestCreated) {
            newId(p.questId, quests, "quest")
            val t = title(p.title)
            if (p.status != QuestStatus.INBOX && p.status != QuestStatus.LATER) {
                throw Reject("new quests start in INBOX or LATER")
            }
            if (p.parentId != null) {
                val parent = quest(p.parentId)
                if (!parent.status.isOpen) throw Reject("cannot add a subquest to a finished quest")
            }
            val order = p.order ?: ts.toDouble()
            if (!order.isFinite()) throw Reject("order must be finite")
            put(
                Quest(
                    id = p.questId, title = t, why = p.why?.trim()?.ifEmpty { null },
                    status = if (p.parentId != null) QuestStatus.INBOX else p.status,
                    parentId = p.parentId, order = order, focusedChildId = null,
                    createdAt = ts, touchedAt = ts,
                ),
            )
            if (p.parentId != null) {
                touch(p.parentId)
                c = c.copy(subquestsCreated = c.subquestsCreated + 1)
                line(p.questId, "Added subquest “$t”")
            } else {
                line(p.questId, "Captured “$t”")
            }
        }

        fun questEdited(p: QuestEdited) {
            var q = quest(p.questId)
            if (p.title != null) q = q.copy(title = title(p.title))
            if (p.clearWhy) q = q.copy(why = null) else if (p.why != null) q = q.copy(why = p.why.trim().ifEmpty { null })
            put(q)
            touch(p.questId)
            line(p.questId, "Edited “${q.title}”")
        }

        fun questStatusSet(p: QuestStatusSet) {
            val q = quest(p.questId)
            if (q.status == QuestStatus.DONE) throw Reject("quest is already done")
            if (p.status == QuestStatus.DONE) throw Reject("use quest.completed to finish a quest")
            if (p.status == q.status) throw Reject("status unchanged")
            if (!q.isRoot && p.status != QuestStatus.INBOX && p.status != QuestStatus.DROPPED) {
                throw Reject("subquests can only be dropped or restored")
            }
            if (p.status == QuestStatus.TRACKED && trackedCount() >= cap) {
                throw Reject("all $cap slots are full")
            }
            val sortsInbox = q.isRoot && q.status == QuestStatus.INBOX && q.leftInboxAt == null
            if (sortsInbox) c = c.copy(inboxSorted = c.inboxSorted + 1)
            put(
                q.copy(
                    status = p.status,
                    trackedAt = if (p.status == QuestStatus.TRACKED) ts else q.trackedAt,
                    leftInboxAt = if (sortsInbox) ts else q.leftInboxAt,
                ),
            )
            touch(p.questId)
            if (!q.isRoot && p.status == QuestStatus.DROPPED) advanceFocusFrom(q.parentId!!, q)
            line(
                p.questId,
                when (p.status) {
                    QuestStatus.TRACKED -> "Tracked “${q.title}”"
                    QuestStatus.LATER -> "Parked “${q.title}” for later"
                    QuestStatus.INBOX -> "Moved “${q.title}” to the inbox"
                    QuestStatus.DROPPED -> "Dropped “${q.title}”"
                    QuestStatus.DONE -> error("unreachable")
                },
            )
            applyNext(p.next)
        }

        fun questCompleted(p: QuestCompleted) {
            completeQuest(p.questId)
            applyNext(p.next)
        }

        fun questFocusSet(p: QuestFocusSet) {
            val q = quest(p.questId)
            if (!q.isRoot) throw Reject("only top-level quests have a focused subquest")
            if (!q.status.isOpen) throw Reject("quest is finished")
            if (p.childId != null) {
                val child = quest(p.childId)
                if (child.parentId != q.id) throw Reject("not a subquest of this quest")
                if (!child.status.isOpen) throw Reject("subquest is finished")
            }
            if (p.childId == q.focusedChildId && p.next == null) throw Reject("focus unchanged")
            put(q.copy(focusedChildId = p.childId))
            touch(p.questId)
            line(p.childId ?: p.questId, p.childId?.let { "Focused “${quest(it).title}”" } ?: "Focused the quest itself")
            applyNext(p.next)
        }

        fun questReordered(p: QuestReordered) {
            val q = quest(p.questId)
            if (!p.order.isFinite()) throw Reject("order must be finite")
            put(q.copy(order = p.order))
        }

        fun stepSet(p: StepSet) = setStep(p.stepId, p.questId, p.text, p.dueAt, p.alarm)

        fun stepEdited(p: StepEdited) {
            var s = step(p.stepId)
            if (quest(s.questId).openStepId != s.id) throw Reject("only the current step can be edited")
            if (p.text != null) s = s.copy(text = text(p.text))
            if (p.clearDue) {
                s = s.copy(dueAt = null, alarm = false)
            } else if (p.dueAt != null) {
                s = s.copy(dueAt = p.dueAt, snoozedUntil = null, reminderDismissed = false)
            }
            if (p.alarm != null) s = s.copy(alarm = p.alarm && s.dueAt != null)
            put(s)
            touch(s.questId)
            line(s.questId, if (p.dueAt != null || p.clearDue) "Scheduled “${s.text}”" else "Edited step “${s.text}”")
        }

        fun stepCompleted(p: StepCompleted) {
            val s = step(p.stepId)
            if (s.isDone) throw Reject("step already done")
            val stamp = p.local?.let { l ->
                val day = runCatching { kotlinx.datetime.LocalDate.parse(l.day) }.getOrNull()
                // The local day must be the stamp's own instant seen from some time zone (±1 day of UTC).
                val utcDay = kotlinx.datetime.Instant.fromEpochMilliseconds(l.at).toLocalDateTime(TimeZone.UTC).date
                val consistent = day != null && kotlin.math.abs(utcDay.daysUntil(day)) <= 1
                if (!consistent || l.minute !in 0 until 24 * 60 || l.at <= 0) null else Triple(l.at, day!!, l.minute)
            } ?: run {
                val dt = kotlinx.datetime.Instant.fromEpochMilliseconds(ts).toLocalDateTime(tz)
                Triple(ts, dt.date, dt.hour * 60 + dt.minute)
            }
            put(s.copy(doneAt = stamp.first, doneVia = p.via, doneDay = stamp.second, doneMinute = stamp.third))
            completedDay = stamp.second to stamp.third
            val q = quest(s.questId)
            if (q.openStepId == s.id) put(q.copy(openStepId = null))
            touch(s.questId)
            xp += Xp.STEP
            c = c.copy(stepsDone = c.stepsDone + 1)
            line(s.questId, "Done: ${s.text}", Xp.STEP)
            if (p.finishQuest) completeQuest(s.questId)
            if (p.via.mayLeaveQuestWithoutStep) allowFlagFor = QuestState.rootOf(quests, s.questId)
            applyNext(p.next)
        }

        fun stepSnoozed(p: StepSnoozed) {
            val s = step(p.stepId)
            if (s.isDone) throw Reject("step already done")
            put(s.copy(snoozedUntil = p.until))
        }

        fun stepReminderDismissed(p: StepReminderDismissed) {
            val s = step(p.stepId)
            if (s.isDone) throw Reject("step already done")
            put(s.copy(reminderDismissed = true))
        }

        fun slotCapSet(p: SlotCapSet) {
            if (p.cap !in Limits.MIN_CAP..Limits.MAX_CAP) {
                throw Reject("slot cap must be between ${Limits.MIN_CAP} and ${Limits.MAX_CAP}")
            }
            if (p.cap == cap) throw Reject("slot cap unchanged")
            if (trackedCount() > p.cap) throw Reject("park quests before lowering the cap to ${p.cap}")
            cap = p.cap
            line(null, "Slot cap set to ${p.cap}")
        }

        fun proposalCreated(p: ProposalCreated) {
            newId(p.proposalId, proposals, "proposal")
            if (p.op.isBlank()) throw Reject("proposal op is empty")
            if (Actors.mcpClient(event.actor) != p.client.trim()) throw Reject("proposal client does not match the actor")
            if (ProposalSource.fromClientName(p.client) != p.source) throw Reject("proposal source does not match the client")
            put(
                Proposal(
                    id = p.proposalId, source = p.source, client = p.client, createdAt = ts,
                    op = p.op, payload = p.payload, reason = p.reason?.trim()?.ifEmpty { null },
                ),
            )
            line(null, "${p.client} proposed: ${ProposalOps.label(p.op)}")
        }

        fun proposalDecided(p: ProposalDecided, status: ProposalStatus) {
            val prop = proposals[p.proposalId] ?: throw Reject("unknown proposal")
            if (prop.status != ProposalStatus.PENDING) throw Reject("proposal already decided")
            put(prop.copy(status = status, decidedAt = ts))
            if (status == ProposalStatus.ACCEPTED) c = c.copy(proposalsAccepted = c.proposalsAccepted + 1)
            val verb = if (status == ProposalStatus.ACCEPTED) "Accepted" else "Rejected"
            line(ProposalOps.questIdOf(prop.payload), "$verb ${prop.client}'s proposal: ${ProposalOps.label(prop.op)}")
        }

        fun proposalEdited(p: ProposalEdited) {
            val prop = proposals[p.proposalId] ?: throw Reject("unknown proposal")
            if (prop.status != ProposalStatus.PENDING) throw Reject("proposal already decided")
            put(prop.copy(status = ProposalStatus.EDITED, decidedAt = ts, payload = p.payload))
            c = c.copy(proposalsAccepted = c.proposalsAccepted + 1)
            line(ProposalOps.questIdOf(p.payload), "Accepted an edited version of ${prop.client}'s proposal: ${ProposalOps.label(prop.op)}")
        }

        // ---- invariants & commit --------------------------------------------------

        fun checkInvariants() {
            if (trackedCount() > cap) throw Reject("all $cap slots are full")
            for (root in touchedRoots) {
                val q = quests[root] ?: continue
                val isStepless = q.status == QuestStatus.TRACKED && QuestState.effectiveStepId(quests, root) == null
                if (isStepless && root !in stepless && root != allowFlagFor) {
                    throw Reject(
                        if (questBefore[root]?.status != QuestStatus.TRACKED && q.status == QuestStatus.TRACKED) {
                            "a quest needs a next step before it can be tracked"
                        } else {
                            "a tracked quest needs a next step"
                        },
                    )
                }
            }
        }

        fun commit() {
            for (root in touchedRoots) {
                val q = quests[root] ?: continue
                if (q.status == QuestStatus.TRACKED && QuestState.effectiveStepId(quests, root) == null) {
                    stepless += root
                } else {
                    stepless -= root
                }
            }
            appliedIds += event.id
            c = c.copy(xp = c.xp + xp)
            completedDay?.let { (day, minute) -> streak.onStep(day, minute) }
            c = c.copy(bestStreak = streak.best)
            counters = c
            for (a in Achievement.entries) {
                if (a !in achievements && a.isUnlocked(c)) achievements[a] = ts
            }
            history += lines
            if (Actors.isUser(event.actor)) lastUserActivityAt = maxOf(lastUserActivityAt ?: ts, ts)
        }

        /** Undo everything this transaction touched (a [Reject] is thrown before [commit]). */
        fun rollback() {
            for ((id, q) in questBefore) if (q == null) quests.remove(id) else quests[id] = q
            for ((id, s) in stepBefore) if (s == null) steps.remove(id) else steps[id] = s
            for ((id, p) in proposalBefore) if (p == null) proposals.remove(id) else proposals[id] = p
            cap = capBefore
        }
    }
}

/** Folds a whole log from scratch, in canonical order. */
object Fold {
    fun of(events: Iterable<Event>, tz: TimeZone): QuestState = folder(events, tz).snapshot()

    fun folder(events: Iterable<Event>, tz: TimeZone): Folder {
        val f = Folder(tz)
        for (e in events.sortedWith(Event.ORDER)) f.apply(e)
        return f
    }
}
