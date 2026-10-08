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
    /** Focus blocks that ran to their end, in log order (v1.1). */
    val focusBlocks: List<FocusBlock> = emptyList(),
    /** Completions that can still be undone: event id → when they happened (v1.1). */
    val undoable: Map<String, Long> = emptyMap(),
) {
    /** True while [eventId] (a completion) can be undone at [nowMs]. */
    fun canUndo(eventId: String, nowMs: Long): Boolean =
        undoable[eventId]?.let { nowMs - it <= Undo.WINDOW_MS } ?: false

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
    private val focusBlocks = ArrayList<FocusBlock>()
    private val effortByDay = HashMap<kotlinx.datetime.LocalDate, Int>()

    /** What each recent completion changed, so `completion.undone` can reverse it exactly (D-1112). */
    private val undoRecords = LinkedHashMap<String, UndoRecord>()
    private val undone = HashSet<String>()

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
        f.focusBlocks.addAll(focusBlocks)
        f.effortByDay.putAll(effortByDay)
        f.undoRecords.putAll(undoRecords)
        f.undone.addAll(undone)
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
        focusBlocks = ArrayList(focusBlocks),
        undoable = undoRecords.mapValuesTo(LinkedHashMap()) { it.value.ts },
    )

    fun apply(event: Event): Outcome {
        if (!seenIds.add(event.id)) return Outcome.Skipped("duplicate event") // idempotent; not recorded
        // A malformed envelope never touches state, so it must not move the ordering marker
        // either (an absurd timestamp would otherwise force refolds and block local commits).
        Envelope.problem(event)?.let { reason ->
            skipped += SkippedEvent(event.id, event.type, event.actor, reason)
            return Outcome.Skipped(reason)
        }
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
                EventTypes.QUEST_RHYTHM_SET -> tx.questRhythmSet(decode(event, QuestRhythmSet.serializer()))
                EventTypes.FOCUS_BLOCK_COMPLETED -> tx.focusBlockCompleted(decode(event, FocusBlockCompleted.serializer()))
                EventTypes.COMPLETION_UNDONE -> tx.completionUndone(decode(event, CompletionUndone.serializer()))
                EventTypes.FRESH_START -> tx.freshStart(decode(event, FreshStart.serializer()))
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
        Envelope.problem(event)?.let { throw Reject(it) }
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
        private val countersBefore = counters
        private val steplessBefore: Set<String> = HashSet(stepless)

        /** Roots that may be left without a step by this event (notification Done, or an undo restoring that state). */
        private val allowFlags = HashSet<String>()
        private var xp = 0
        private var c = counters
        private var completedDay: Pair<kotlinx.datetime.LocalDate, Int>? = null
        private val lines = ArrayList<HistoryEntry>()
        private var block: FocusBlock? = null
        private var undoing: UndoRecord? = null
        private val recordsUndo = event.type == EventTypes.STEP_COMPLETED || event.type == EventTypes.QUEST_COMPLETED

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

        fun removeQuest(id: String) {
            val q = quests[id] ?: return
            if (!questBefore.containsKey(id)) questBefore[id] = q
            QuestState.rootOf(quests, id)?.let { touchedRoots += it }
            quests.remove(id)
        }

        fun removeStep(id: String) {
            val s = steps[id] ?: return
            if (!stepBefore.containsKey(id)) stepBefore[id] = s
            QuestState.rootOf(quests, s.questId)?.let { touchedRoots += it }
            steps.remove(id)
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

        private fun cue(raw: String?): String? {
            val t = raw?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null } ?: return null
            if (t.length > Limits.MAX_CUE) throw Reject("cue is too long")
            return t
        }

        private fun note(raw: String?, what: String): String? {
            val t = raw?.trim()?.ifEmpty { null } ?: return null
            if (t.length > Limits.MAX_NOTE) throw Reject("$what is too long")
            return t
        }

        private fun rhythm(r: Rhythm): Rhythm {
            r.problem()?.let { throw Reject("rhythm: $it") }
            return r.normalized()
        }

        private fun touch(q: Quest) {
            put(q.copy(touchedAt = maxOf(q.touchedAt, ts)))
            q.parentId?.let { quests[it] }?.let { parent -> touch(parent) }
        }

        private fun touch(questId: String) = touch(quest(questId))

        private fun trackedCount(): Int = quests.values.count { it.status == QuestStatus.TRACKED && it.isRoot }

        // ---- shared operations -----------------------------------------------------

        private fun setStep(stepId: String, questId: String, rawText: String, dueAt: Long?, alarm: Boolean, rawCue: String? = null, session: Boolean = false) {
            newId(stepId, steps, "step")
            val q = quest(questId)
            if (!q.status.isOpen) throw Reject("cannot add a step to a finished quest")
            val t = text(rawText)
            q.openStepId?.let { old -> steps[old]?.let { put(it.copy(superseded = true)) } }
            put(Step(stepId, questId, t, dueAt, alarm && dueAt != null, createdAt = ts, cue = cue(rawCue)))
            put(quest(questId).copy(openStepId = stepId))
            touch(questId)
            line(questId, if (session) "Next session: $t" else "Next step: $t")
        }

        private fun applyNext(next: NextStep?) {
            if (next != null) setStep(next.stepId, next.questId, next.text, next.dueAt, next.alarm, next.cue)
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
            if (q.isPractice) throw Reject("practices never complete; park or drop it instead")
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
            if (p.parentId != null && quest(p.parentId).isPractice) throw Reject("practices have no subquests")
            if (p.rhythm != null && p.parentId != null) throw Reject("only top-level quests can be practices")
            val order = p.order ?: ts.toDouble()
            if (!order.isFinite()) throw Reject("order must be finite")
            put(
                Quest(
                    id = p.questId, title = t, why = p.why?.trim()?.ifEmpty { null },
                    status = if (p.parentId != null) QuestStatus.INBOX else p.status,
                    parentId = p.parentId, order = order, focusedChildId = null,
                    createdAt = ts, touchedAt = ts, rhythm = p.rhythm?.let(::rhythm),
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
            if (p.clearObstacle) q = q.copy(obstacle = null) else if (p.obstacle != null) q = q.copy(obstacle = note(p.obstacle, "obstacle"))
            if (p.clearPlan) q = q.copy(plan = null) else if (p.plan != null) q = q.copy(plan = note(p.plan, "plan"))
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

        fun stepSet(p: StepSet) = setStep(p.stepId, p.questId, p.text, p.dueAt, p.alarm, p.cue)

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
            if (p.clearCue) s = s.copy(cue = null) else if (p.cue != null) s = s.copy(cue = cue(p.cue))
            put(s.copy(touchedAt = maxOf(s.touchedAt, ts)))
            touch(s.questId)
            line(s.questId, if (p.dueAt != null || p.clearDue) "Scheduled “${s.text}”" else "Edited step “${s.text}”")
        }

        fun stepCompleted(p: StepCompleted) {
            val s = step(p.stepId)
            if (s.isDone) throw Reject("step already done")
            val practice = quest(s.questId).takeIf { it.isPractice }
            if (practice != null && p.finishQuest) throw Reject("practices never complete; park or drop it instead")
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
            line(s.questId, if (practice != null) "Session: ${s.text}" else "Done: ${s.text}", Xp.STEP)
            if (p.finishQuest) completeQuest(s.questId)
            if (p.via.mayLeaveQuestWithoutStep) QuestState.rootOf(quests, s.questId)?.let { allowFlags += it }
            applyNext(p.next)
            // A practice's next session appears by itself, with the same text and cue, on every
            // path (app, notification, widget, proposal). Its id is derived from the completed
            // step, so every replica creates the same one.
            if (practice != null && p.next == null && practice.status.isOpen && quest(practice.id).openStepId == null) {
                setStep(Ids.derive(s.id, "session"), practice.id, s.text, null, false, s.cue, session = true)
            }
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
                    id = p.proposalId, source = p.source, client = p.client.trim(), createdAt = ts,
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

        // ---- v1.1 -------------------------------------------------------------------

        fun questRhythmSet(p: QuestRhythmSet) {
            val q = quest(p.questId)
            if (!q.isRoot) throw Reject("only top-level quests can be practices")
            if (!q.status.isOpen) throw Reject("quest is finished")
            val r = p.rhythm?.let(::rhythm)
            if (r == q.rhythm) throw Reject("rhythm unchanged")
            if (r != null && quests.values.any { it.parentId == q.id && it.status.isOpen }) {
                throw Reject("practices have no subquests; finish or drop them first")
            }
            put(q.copy(rhythm = r, focusedChildId = if (r != null) null else q.focusedChildId))
            touch(p.questId)
            line(p.questId, if (r == null) "“${q.title}” is a regular quest now" else if (q.rhythm == null) "“${q.title}” is now a practice" else "Changed the rhythm of “${q.title}”")
        }

        fun focusBlockCompleted(p: FocusBlockCompleted) {
            val q = quest(p.questId)
            val s = step(p.stepId)
            if (s.questId != q.id) throw Reject("step does not belong to this quest")
            if (p.minutes < Focus.MIN_MINUTES) throw Reject("a focus block is at least ${Focus.MIN_MINUTES} minutes")
            if (p.minutes > Focus.MAX_MINUTES) throw Reject("a focus block is at most ${Focus.MAX_MINUTES} minutes")
            val day = localDay(p.local)
            val earned = Focus.xpFor((effortByDay[day] ?: 0))
            xp += earned
            c = c.copy(focusBlocks = c.focusBlocks + 1, focusMinutes = c.focusMinutes + p.minutes)
            block = FocusBlock(event.id, q.id, s.id, p.minutes, day, earned, ts)
            if (!s.isDone) put(s.copy(touchedAt = maxOf(s.touchedAt, ts)))
            line(q.id, "Focused ${p.minutes} min on “${s.text}”", earned)
        }

        /**
         * Reverses a completion exactly (D-1112): every quest and step it changed goes back to
         * how it was, and its XP, counters, streak day and the achievements they unlocked are
         * taken back. Refused once anything it changed has changed again.
         */
        fun completionUndone(p: CompletionUndone) {
            val rec = undoRecords[p.eventId]
                ?: throw Reject(if (p.eventId in undone) "already undone" else "nothing to undo for that event")
            if (ts - rec.ts > Undo.WINDOW_MS) throw Reject("too late to undo")
            for ((id, after) in rec.questsAfter) if (quests[id] != after) throw Reject("it changed since, so it can't be undone")
            for ((id, after) in rec.stepsAfter) if (steps[id] != after) throw Reject("it changed since, so it can't be undone")
            for ((id, before) in rec.questsBefore) if (before == null) removeQuest(id) else put(before)
            for ((id, before) in rec.stepsBefore) if (before == null) removeStep(id) else put(before)
            allowFlags += rec.steplessRoots
            undoing = rec
            line(rec.questId, "Undid: ${rec.label}")
        }

        fun freshStart(p: FreshStart) {
            val tracked = quests.values.filter { it.status == QuestStatus.TRACKED && it.isRoot }
            if (p.parkOthers) {
                val keep = p.keepQuestId?.let { quest(it) } ?: throw Reject("pick the one quest to keep")
                if (keep.status != QuestStatus.TRACKED || !keep.isRoot) throw Reject("the quest to keep must be tracked")
            }
            val trackedIds = tracked.map { it.id }.toSet()
            for (s in steps.values.toList()) {
                if (s.isDone || s.superseded) continue
                val root = QuestState.rootOf(quests, s.questId) ?: continue
                if (root !in trackedIds || quests[s.questId]?.openStepId != s.id) continue
                val past = s.dueAt != null && s.dueAt < ts
                if (s.snoozedUntil == null && !past && !s.reminderDismissed) continue
                put(
                    s.copy(
                        snoozedUntil = null, reminderDismissed = false,
                        dueAt = if (past) null else s.dueAt, alarm = if (past) false else s.alarm,
                    ),
                )
            }
            if (p.parkOthers) {
                for (q in tracked) if (q.id != p.keepQuestId) put(q.copy(status = QuestStatus.LATER))
            }
            line(null, "Fresh start")
        }

        private fun localDay(stamp: LocalStamp?): kotlinx.datetime.LocalDate = stamp?.let { l ->
            val day = runCatching { kotlinx.datetime.LocalDate.parse(l.day) }.getOrNull()
            val utcDay = kotlinx.datetime.Instant.fromEpochMilliseconds(l.at).toLocalDateTime(TimeZone.UTC).date
            day?.takeIf { l.at > 0 && kotlin.math.abs(utcDay.daysUntil(it)) <= 1 }
        } ?: kotlinx.datetime.Instant.fromEpochMilliseconds(ts).toLocalDateTime(tz).date

        // ---- invariants & commit --------------------------------------------------

        fun checkInvariants() {
            if (trackedCount() > cap) throw Reject("all $cap slots are full")
            for (root in touchedRoots) {
                val q = quests[root] ?: continue
                val isStepless = q.status == QuestStatus.TRACKED && QuestState.effectiveStepId(quests, root) == null
                if (isStepless && root !in stepless && root !in allowFlags) {
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
            block?.let { b ->
                focusBlocks += b
                effortByDay[b.day] = (effortByDay[b.day] ?: 0) + b.xp
            }
            undoing?.let { rec ->
                // Take back exactly what the completion added; the streak forgets its day.
                c = c.minus(rec.countersAdded)
                rec.streakDay?.let { (day, minute) -> streak.onUndo(day, minute) }
                undoRecords.remove(rec.eventId)
                undone += rec.eventId
            }
            c = c.copy(bestStreak = streak.best)
            counters = c
            // Achievements are a function of the lifetime counters, which only an undo lowers.
            if (undoing != null) achievements.keys.retainAll { it.isUnlocked(c) }
            for (a in Achievement.entries) {
                if (a !in achievements && a.isUnlocked(c)) achievements[a] = ts
            }
            history += lines
            if (Actors.isUser(event.actor)) lastUserActivityAt = maxOf(lastUserActivityAt ?: ts, ts)
            if (recordsUndo) {
                undoRecords[event.id] = UndoRecord(
                    eventId = event.id, ts = ts,
                    questId = lines.firstOrNull { it.xp > 0 }?.questId ?: lines.firstOrNull()?.questId,
                    label = lines.firstOrNull { it.xp > 0 }?.text ?: event.type,
                    questsBefore = HashMap(questBefore),
                    stepsBefore = HashMap(stepBefore),
                    questsAfter = questBefore.keys.associateWith { quests[it] },
                    stepsAfter = stepBefore.keys.associateWith { steps[it] },
                    countersAdded = counters.minus(countersBefore).copy(bestStreak = 0),
                    streakDay = completedDay,
                    steplessRoots = touchedRoots.filter { it in steplessBefore }.toSet(),
                )
            }
            // Old completions can no longer be undone; forget what they changed.
            val it = undoRecords.values.iterator()
            while (it.hasNext()) {
                if (ts - it.next().ts > Undo.WINDOW_MS) it.remove() else break
            }
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

/** One focus block that ran to its end (v1.1). [xp] is the effort XP it earned after the daily cap. */
data class FocusBlock(
    val eventId: String,
    val questId: String,
    val stepId: String,
    val minutes: Int,
    val day: kotlinx.datetime.LocalDate,
    val xp: Int,
    val at: Long,
)

object Undo {
    /**
     * How long after a completion the fold still accepts its undo. The app offers Undo for
     * 10 seconds (5 minutes from a notification); the margin covers sync delays.
     */
    const val WINDOW_MS = 60 * 60 * 1000L
}

/** Everything one completion changed: enough to reverse it exactly (D-1112). */
internal data class UndoRecord(
    val eventId: String,
    val ts: Long,
    val questId: String?,
    val label: String,
    val questsBefore: Map<String, Quest?>,
    val stepsBefore: Map<String, Step?>,
    val questsAfter: Map<String, Quest?>,
    val stepsAfter: Map<String, Step?>,
    /** Additive counters only (best streak is re-derived from the streak's days). */
    val countersAdded: Counters,
    val streakDay: Pair<kotlinx.datetime.LocalDate, Int>?,
    /** Roots that already showed "Needs next step" before the completion. */
    val steplessRoots: Set<String>,
)

/** Field-wise difference of the additive counters (not [Counters.bestStreak]). */
internal fun Counters.minus(o: Counters) = Counters(
    stepsDone = stepsDone - o.stepsDone,
    questsDone = questsDone - o.questsDone,
    subquestsDone = subquestsDone - o.subquestsDone,
    inboxSorted = inboxSorted - o.inboxSorted,
    subquestsCreated = subquestsCreated - o.subquestsCreated,
    proposalsAccepted = proposalsAccepted - o.proposalsAccepted,
    bestStreak = bestStreak,
    xp = xp - o.xp,
    focusBlocks = focusBlocks - o.focusBlocks,
    focusMinutes = focusMinutes - o.focusMinutes,
)

/** Structural checks on an event envelope, shared by the fold, imports and the sync server. */
object Envelope {
    const val MAX_TYPE = 64
    const val MAX_ACTOR = 128

    /** 9999-12-31T23:59:59.999Z — anything later is a broken clock, not a date. */
    const val MAX_TS = 253_402_300_799_999L

    /**
     * Ingestion points (sync push and pull, snapshot import) refuse events more than this
     * far ahead of their own clock, so one device with a wrong clock can't drag everyone's
     * timestamps — and the dates shown from them — into the future.
     */
    const val FUTURE_TOLERANCE_MS = 24 * 60 * 60 * 1000L

    fun tooFarAhead(event: Event, nowMs: Long): Boolean = event.ts > nowMs + FUTURE_TOLERANCE_MS

    fun problem(event: Event): String? = when {
        !Ids.isValid(event.id) -> "event id is not a UUIDv7"
        !Ids.isValid(event.deviceId) -> "device id is not a UUIDv7"
        event.seq < 1 -> "sequence number must be positive"
        event.ts <= 0 -> "timestamp must be positive"
        event.ts > MAX_TS -> "timestamp is out of range"
        event.type.isBlank() || event.type.length > MAX_TYPE -> "event type is missing or too long"
        event.actor.isBlank() || event.actor.length > MAX_ACTOR -> "actor is missing or too long"
        else -> null
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
