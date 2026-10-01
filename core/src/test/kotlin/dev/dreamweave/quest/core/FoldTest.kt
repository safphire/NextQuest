// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoldTest {
    // ---- the core loop ----------------------------------------------------------------

    @Test
    fun captureTrackDoneNext_loop() {
        val s = Scenario()
        val q = s.capture("Write the novel")
        assertEquals(QuestStatus.INBOX, s.state.quests.getValue(q).status)
        val first = s.step(q, "Outline chapter 1")
        s.track(q)
        assertEquals(listOf(q), s.state.tracked.map { it.id })
        assertEquals("Outline chapter 1", s.state.effectiveStep(q)!!.text)

        s.done(first, nextText = "Draft chapter 1")
        assertEquals("Draft chapter 1", s.state.effectiveStep(q)!!.text)
        assertTrue(s.state.steps.getValue(first).isDone)
        assertEquals(Xp.STEP.toLong(), s.state.xp)
        assertEquals(1, s.state.counters.stepsDone)
        assertEquals(1, s.state.counters.inboxSorted)
        assertTrue(Achievement.FIRST_STEP in s.state.achievements)

        s.done(s.stepOf(q), finish = true)
        val quest = s.state.quests.getValue(q)
        assertEquals(QuestStatus.DONE, quest.status)
        assertEquals((2 * Xp.STEP + Xp.QUEST).toLong(), s.state.xp)
        assertTrue(s.state.tracked.isEmpty())
        assertTrue(Achievement.FIRST_QUEST in s.state.achievements)
        assertTrue(s.state.historyOf(q).any { it.xp == Xp.QUEST })
    }

    @Test
    fun captureNeverForcesSorting_andTrimsInput() {
        val s = Scenario()
        val q = s.capture("   Buy milk  ")
        assertEquals("Buy milk", s.state.quests.getValue(q).title)
        assertNull(s.state.quests.getValue(q).openStepId)
        assertContains(s.rejected(Events.questCreated(QuestCreated(Ids.newId(), "   "))), "title is empty")
        assertContains(s.rejected(Events.questCreated(QuestCreated(Ids.newId(), "x".repeat(501)))), "too long")
        assertContains(s.rejected(Events.questCreated(QuestCreated("bad-id", "x"))), "UUIDv7")
        assertContains(s.rejected(Events.questCreated(QuestCreated(q, "dup"))), "already exists")
        assertContains(
            s.rejected(Events.questCreated(QuestCreated(Ids.newId(), "x", status = QuestStatus.TRACKED))),
            "INBOX or LATER",
        )
        val later = s.capture("Someday", QuestStatus.LATER)
        assertEquals(QuestStatus.LATER, s.state.quests.getValue(later).status)
    }

    // ---- principle 3: the cap --------------------------------------------------------

    @Test
    fun cap_cannotBeExceeded() {
        val s = Scenario()
        repeat(5) { s.tracked("Q$it") }
        val sixth = s.capture("Q6")
        s.step(sixth, "go")
        assertContains(s.rejected(Events.questStatusSet(QuestStatusSet(sixth, QuestStatus.TRACKED))), "slots are full")
        assertEquals(5, s.state.tracked.size)
        assertEquals(0, s.state.freeSlots)

        // Swap: park one, then track — in one commit.
        s.ok(
            Events.questStatusSet(QuestStatusSet(s.state.tracked.first().id, QuestStatus.LATER)),
            Events.questStatusSet(QuestStatusSet(sixth, QuestStatus.TRACKED)),
        )
        assertEquals(5, s.state.tracked.size)
        assertTrue(sixth in s.state.tracked.map { it.id })
    }

    @Test
    fun cap_isConfigurableBetween3And7_andCannotDropBelowTracked() {
        val s = Scenario()
        repeat(4) { s.tracked("Q$it") }
        assertContains(s.rejected(Events.slotCapSet(SlotCapSet(3))), "park quests")
        assertContains(s.rejected(Events.slotCapSet(SlotCapSet(2))), "between 3 and 7")
        assertContains(s.rejected(Events.slotCapSet(SlotCapSet(8))), "between 3 and 7")
        assertContains(s.rejected(Events.slotCapSet(SlotCapSet(5))), "unchanged")
        s.ok(Events.slotCapSet(SlotCapSet(7)))
        repeat(3) { s.tracked("More$it") }
        assertEquals(7, s.state.tracked.size)
        val eighth = s.capture("Q8")
        s.step(eighth, "x")
        assertContains(s.rejected(Events.questStatusSet(QuestStatusSet(eighth, QuestStatus.TRACKED))), "7 slots")
    }

    @Test
    fun cap_concurrentTracksFromTwoDevicesResolveDeterministically() {
        val phoneId = Ids.newId()
        val tabletId = Ids.newId()
        val a = Scenario(deviceId = phoneId)
        repeat(4) { a.tracked("Shared$it") }
        val qa = a.capture("From phone"); a.step(qa, "a")
        val qb = a.capture("From tablet"); a.step(qb, "b")
        val shared = a.log.toList()

        // Both devices track a 5th quest while offline.
        val phone = EventFactory(phoneId, lastSeq = a.factory.currentSeq, lastTs = a.factory.currentTs, clock = { a.now + 1_000 })
        val tablet = EventFactory(tabletId, lastTs = a.factory.currentTs, clock = { a.now + 2_000 })
        val e1 = phone.create(EventTypes.QUEST_STATUS_SET, Events.questStatusSet(QuestStatusSet(qa, QuestStatus.TRACKED)).payload)
        val e2 = tablet.create(EventTypes.QUEST_STATUS_SET, Events.questStatusSet(QuestStatusSet(qb, QuestStatus.TRACKED)).payload)
        val e3 = tablet.create(EventTypes.SLOT_CAP_SET, Events.slotCapSet(SlotCapSet(3)).payload)

        val merged1 = Fold.of(shared + e1 + e2 + e3, a.tz)
        val merged2 = Fold.of(listOf(e3, e2) + shared.reversed() + e1, a.tz)
        assertEquals(5, merged1.tracked.size)
        assertEquals(merged1.tracked.map { it.id }, merged2.tracked.map { it.id })
        assertTrue(qa in merged1.tracked.map { it.id }) // earlier ts wins the last slot
        assertEquals(QuestStatus.INBOX, merged1.quests.getValue(qb).status)
        assertEquals(5, merged1.slotCap) // lowering below tracked count is refused everywhere
        assertEquals(setOf(e2.id, e3.id), merged1.skipped.map { it.eventId }.toSet())
    }

    // ---- principle 4: next-step invariant --------------------------------------------

    @Test
    fun cannotTrackWithoutANextStep() {
        val s = Scenario()
        val q = s.capture("No step yet")
        assertContains(s.rejected(Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED))), "needs a next step")
        // ...but tracking with a step in the same event works.
        s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED, next = s.next(q, "start"))))
        assertEquals("start", s.state.effectiveStep(q)!!.text)
    }

    @Test
    fun appDoneRequiresANextStep_orFinishingTheQuest() {
        val s = Scenario()
        val q = s.tracked("Q", "only step")
        val step = s.stepOf(q)
        assertContains(s.rejected(Events.stepCompleted(StepCompleted(step))), "needs a next step")
        assertContains(s.rejected(Events.stepCompleted(StepCompleted(step, StepVia.PROPOSAL))), "needs a next step")
        assertFalse(s.state.steps.getValue(step).isDone) // rejected commit left no trace
        assertEquals(0, s.state.xp)
        s.done(step, finish = true)
        assertEquals(QuestStatus.DONE, s.state.quests.getValue(q).status)
    }

    @Test
    fun notificationDone_flagsNeedsNextStep_untilTheUserSetsOne() {
        for (via in listOf(StepVia.NOTIFICATION)) {
            val s = Scenario()
            val q = s.tracked("Q")
            s.done(s.stepOf(q), via = via)
            assertTrue(s.state.needsNextStep(q))
            assertNull(s.state.effectiveStep(q))
            assertEquals(QuestStatus.TRACKED, s.state.quests.getValue(q).status)
            // Unrelated edits on a flagged quest are fine; it stays flagged.
            s.ok(Events.questEdited(QuestEdited(q, why = "matters")))
            assertTrue(s.state.needsNextStep(q))
            s.step(q, "set later")
            assertFalse(s.state.needsNextStep(q))
            // The flag cannot be re-entered from the app.
            assertContains(s.rejected(Events.stepCompleted(StepCompleted(s.stepOf(q)))), "needs a next step")
        }
    }

    @Test
    fun widgetDone_isNotAnException_itMustCarryTheNextStep() {
        val s = Scenario()
        val q = s.tracked("Q")
        assertContains(s.rejected(Events.stepCompleted(StepCompleted(s.stepOf(q), StepVia.WIDGET))), "needs a next step")
        s.done(s.stepOf(q), nextText = "from the widget dialog", via = StepVia.WIDGET)
        assertFalse(s.state.needsNextStep(q))
    }

    @Test
    fun untrackedQuestsMayBeStepless() {
        val s = Scenario()
        val q = s.capture("Inbox item")
        val st = s.step(q, "a step")
        s.done(st)
        assertNull(s.state.openStep(q))
        assertFalse(s.state.needsNextStep(q))
    }

    @Test
    fun newStepSupersedesTheOldOne_andSupersededStepsStillEarnXp() {
        val s = Scenario()
        val q = s.tracked("Q", "old")
        val old = s.stepOf(q)
        val new = s.step(q, "new")
        assertTrue(s.state.steps.getValue(old).superseded)
        assertEquals(new, s.stepOf(q))
        s.done(old)
        assertEquals(Xp.STEP.toLong(), s.state.xp)
        assertEquals(new, s.stepOf(q))
        assertContains(s.rejected(Events.stepCompleted(StepCompleted(old))), "already done")
    }

    // ---- subquests & focus -----------------------------------------------------------

    @Test
    fun focusedSubquestOwnsTheVisibleStep_andAutoAdvances() {
        val s = Scenario()
        val root = s.tracked("Ship app", "root step")
        val a = s.sub(root, "Design")
        s.advanceMinutes(1)
        val b = s.sub(root, "Build")
        s.advanceMinutes(1)
        val c = s.sub(root, "Release")
        assertTrue(Achievement.FIRST_TREE in s.state.achievements)
        assertEquals(Progress(0, 3), Views.progress(s.state, root))

        // Focusing a subquest with no step would hide the root's step: needs `next`.
        assertContains(s.rejected(Events.questFocusSet(QuestFocusSet(root, a))), "needs a next step")
        s.ok(Events.questFocusSet(QuestFocusSet(root, a, next = s.next(a, "sketch screens"))))
        assertEquals("sketch screens", s.state.effectiveStep(root)!!.text)
        assertEquals(a, s.state.effectiveStepOwner(root)!!.id)

        // Finishing the focused subquest moves focus to the next one; it has no step → next required.
        val st = s.stepOf(root)
        assertContains(s.rejected(Events.stepCompleted(StepCompleted(st, finishQuest = true))), "needs a next step")
        s.done(st, finish = true, nextText = "set up project", nextQuest = b)
        assertEquals(b, s.state.quests.getValue(root).focusedChildId)
        assertEquals(QuestStatus.DONE, s.state.quests.getValue(a).status)
        assertEquals((Xp.STEP + Xp.SUBQUEST).toLong(), s.state.xp)
        assertEquals(Progress(1, 3), Views.progress(s.state, root))

        // Dropping the focused subquest advances to the next one too.
        s.ok(Events.questStatusSet(QuestStatusSet(b, QuestStatus.DROPPED, next = s.next(c, "write notes"))))
        assertEquals(c, s.state.quests.getValue(root).focusedChildId)
        assertEquals(Progress(1, 2), Views.progress(s.state, root))

        // Completing the last focused subquest hands focus back to the root's own step.
        s.ok(Events.questCompleted(QuestCompleted(c)))
        assertNull(s.state.quests.getValue(root).focusedChildId)
        assertEquals("root step", s.state.effectiveStep(root)!!.text)
        assertEquals(2, s.state.counters.subquestsDone)
    }

    @Test
    fun focusRules() {
        val s = Scenario()
        val root = s.tracked("Root")
        val other = s.capture("Other")
        val child = s.sub(root, "Child")
        s.step(child, "child step")
        assertContains(s.rejected(Events.questFocusSet(QuestFocusSet(child, null))), "top-level")
        assertContains(s.rejected(Events.questFocusSet(QuestFocusSet(root, other))), "not a subquest")
        s.ok(Events.questFocusSet(QuestFocusSet(root, child)))
        assertContains(s.rejected(Events.questFocusSet(QuestFocusSet(root, child))), "unchanged")
        s.ok(Events.questFocusSet(QuestFocusSet(root, null)))
        s.ok(Events.questCompleted(QuestCompleted(child)))
        assertContains(s.rejected(Events.questFocusSet(QuestFocusSet(root, child))), "finished")
        assertContains(s.rejected(Events.questCreated(QuestCreated(Ids.newId(), "x", parentId = child))), "finished")
    }

    @Test
    fun subquestStatusRules() {
        val s = Scenario()
        val root = s.capture("Root")
        val child = s.sub(root, "Child")
        assertContains(s.rejected(Events.questStatusSet(QuestStatusSet(child, QuestStatus.TRACKED))), "subquests")
        assertContains(s.rejected(Events.questStatusSet(QuestStatusSet(child, QuestStatus.LATER))), "subquests")
        s.ok(Events.questStatusSet(QuestStatusSet(child, QuestStatus.DROPPED)))
        s.ok(Events.questStatusSet(QuestStatusSet(child, QuestStatus.INBOX)))
        assertEquals(QuestStatus.INBOX, s.state.quests.getValue(child).status)
    }

    // ---- principle 5: actors -----------------------------------------------------------

    @Test
    fun aiClientsCanOnlyPropose() {
        val s = Scenario()
        val q = s.tracked("Q")
        val mcp = Actors.mcp("Claude")
        assertContains(s.rejected(Events.questCreated(QuestCreated(Ids.newId(), "sneaky")), actor = mcp), "only create proposals")
        assertContains(s.rejected(Events.stepCompleted(StepCompleted(s.stepOf(q), finishQuest = true)), actor = mcp), "only create proposals")
        assertContains(s.rejected(Events.proposalAccepted(Ids.newId()), actor = mcp), "only create proposals")
        assertContains(s.rejected(Events.slotCapSet(SlotCapSet(7)), actor = mcp), "only create proposals")
        assertContains(s.rejected(Events.questEdited(QuestEdited(q, "x")), actor = "server"), "unknown actor")
        val created = Events.proposalCreated(
            ProposalCreated(Ids.newId(), ProposalSource.OTHER, "x", "park_quest", ProposalOps.encode(ParkQuestPayload(q))),
        )
        assertContains(s.rejected(created), "created by AI clients")
        assertEquals(QuestStatus.TRACKED, s.state.quests.getValue(q).status)
    }

    // ---- remaining handlers ----------------------------------------------------------

    @Test
    fun editsReorderSnoozeDismiss() {
        val s = Scenario()
        val q = s.tracked("Title", "step")
        s.ok(Events.questEdited(QuestEdited(q, title = "New title", why = " because ")))
        assertEquals("New title", s.state.quests.getValue(q).title)
        assertEquals("because", s.state.quests.getValue(q).why)
        s.ok(Events.questEdited(QuestEdited(q, clearWhy = true)))
        assertNull(s.state.quests.getValue(q).why)
        s.ok(Events.questReordered(QuestReordered(q, 1.5)))
        assertEquals(1.5, s.state.quests.getValue(q).order)

        val st = s.stepOf(q)
        s.ok(Events.stepEdited(StepEdited(st, text = "renamed", dueAt = 1_000, alarm = true)))
        assertEquals("renamed", s.state.steps.getValue(st).text)
        assertTrue(s.state.steps.getValue(st).alarm)
        s.ok(Events.stepSnoozed(StepSnoozed(st, 99)))
        assertEquals(99, s.state.steps.getValue(st).snoozedUntil)
        s.ok(Events.stepReminderDismissed(StepReminderDismissed(st)))
        assertTrue(s.state.steps.getValue(st).reminderDismissed)
        s.ok(Events.stepEdited(StepEdited(st, dueAt = 2_000)))
        assertFalse(s.state.steps.getValue(st).reminderDismissed) // rescheduling re-arms the reminder
        assertNull(s.state.steps.getValue(st).snoozedUntil)
        s.ok(Events.stepEdited(StepEdited(st, clearDue = true)))
        assertNull(s.state.steps.getValue(st).dueAt)
        assertFalse(s.state.steps.getValue(st).alarm)
        s.ok(Events.stepEdited(StepEdited(st, alarm = true)))
        assertFalse(s.state.steps.getValue(st).alarm) // no alarm without a time

        val old = st
        s.step(q, "newer")
        assertContains(s.rejected(Events.stepEdited(StepEdited(old, text = "x"))), "current step")
        s.done(s.stepOf(q), nextText = "after")
        val doneStep = s.state.steps.values.first { it.isDone }.id
        assertContains(s.rejected(Events.stepSnoozed(StepSnoozed(doneStep, 1))), "already done")
        assertContains(s.rejected(Events.stepReminderDismissed(StepReminderDismissed(doneStep))), "already done")
        assertContains(s.rejected(Events.stepEdited(StepEdited(s.stepOf(q), text = " "))), "empty")
    }

    @Test
    fun statusTransitions() {
        val s = Scenario()
        val q = s.tracked("Q")
        assertContains(s.rejected(Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED))), "unchanged")
        assertContains(s.rejected(Events.questStatusSet(QuestStatusSet(q, QuestStatus.DONE))), "quest.completed")
        s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.DROPPED)))
        assertTrue(s.state.tracked.isEmpty())
        s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.INBOX)))
        s.ok(Events.questCompleted(QuestCompleted(q)))
        assertContains(s.rejected(Events.questStatusSet(QuestStatusSet(q, QuestStatus.INBOX))), "already done")
        assertContains(s.rejected(Events.questCompleted(QuestCompleted(q))), "already finished")
        assertContains(s.rejected(Events.stepSet(StepSet(Ids.newId(), q, "x"))), "finished quest")
        assertContains(s.rejected(Events.stepSet(StepSet(Ids.newId(), Ids.newId(), "x"))), "unknown quest")
        assertContains(s.rejected(Events.stepCompleted(StepCompleted(Ids.newId()))), "unknown step")
        assertContains(s.rejected(Events.questEdited(QuestEdited(q, title = ""))), "empty")
    }

    @Test
    fun malformedUnknownAndDuplicateEventsAreSkipped() {
        val f = Folder(kotlinx.datetime.TimeZone.UTC)
        val d = Ids.newId()
        val bad = Event(Ids.newId(), d, 1, 1, "user", EventTypes.STEP_SET, JsonObject(mapOf("x" to JsonPrimitive(1))))
        assertEquals(Outcome.Skipped("malformed payload"), f.apply(bad))
        val future = Event(Ids.newId(), d, 2, 2, "user", "quest.teleported", JsonObject(emptyMap()))
        assertIs<Outcome.Skipped>(f.apply(future))
        val good = Events.questCreated(QuestCreated(Ids.newId(), "ok"))
        val e = Event(Ids.newId(), d, 3, 3, "user", good.type, good.payload)
        assertEquals(Outcome.Applied, f.apply(e))
        assertEquals(Outcome.Skipped("duplicate event"), f.apply(e))
        assertTrue(f.hasApplied(e.id))
        assertTrue(f.hasSeen(bad.id))
        assertFalse(f.hasApplied(bad.id))
        assertEquals(Outcome.Skipped("duplicate event"), f.apply(bad)) // a skipped event is never re-judged
        // Envelope checks: ids are UUIDv7, seq and ts positive, (device, seq) never reused.
        fun env(id: String = Ids.newId(), dev: String = d, seq: Long = 99, ts: Long = 99) =
            f.apply(Event(id, dev, seq, ts, "user", good.type, Events.questCreated(QuestCreated(Ids.newId(), "x")).payload))
        assertEquals(Outcome.Skipped("event id is not a UUIDv7"), env(id = "not-a-uuid"))
        assertEquals(Outcome.Skipped("device id is not a UUIDv7"), env(dev = "device-a"))
        assertEquals(Outcome.Skipped("sequence number must be positive"), env(seq = 0))
        assertEquals(Outcome.Skipped("timestamp must be positive"), env(ts = -100))
        assertEquals(Outcome.Skipped("sequence number reused by another event"), env(seq = 3))
        val snap = f.snapshot()
        assertEquals(7, snap.skipped.size)
        assertEquals(1, snap.appliedCount)
        assertTrue(f.isOutOfOrder(Event("z", d, 0, 0, "user", "t", JsonObject(emptyMap()))))
        assertFalse(f.isOutOfOrder(Event("z", d, 999, 999, "user", "t", JsonObject(emptyMap()))))
    }

    @Test
    fun aLocalEditOnAFastPeersQuest_survivesRefold() {
        val s = Scenario(start = "2026-03-01T09:00:00Z")
        // Peer B (clock 2 days ahead) creates a quest; this device sees it, then tracks it.
        val peerClock = s.now + 2L * 86_400_000
        val peer = EventFactory(Ids.newId(), clock = { peerClock })
        val q = Ids.newId(s.now)
        val created = peer.create(EventTypes.QUEST_CREATED, Events.questCreated(QuestCreated(q, "From B")).payload)
        s.folder.apply(created)
        s.log += created
        s.factory.observe(created.ts)
        s.ok(Events.stepSet(StepSet(Ids.newId(s.now), q, "go")), Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED)))
        val refolded = Fold.of(s.log, s.tz)
        assertEquals(QuestStatus.TRACKED, refolded.quests.getValue(q).status)
        assertEquals(s.state.quests, refolded.quests)
        // A hand-made local event that would sort before applied ones is refused outright.
        val stale = Event(Ids.newId(s.now), s.factory.deviceId, 999, 1, "user", EventTypes.QUEST_CREATED, Events.questCreated(QuestCreated(Ids.newId(s.now), "x")).payload)
        assertIs<Commits.Result.Rejected>(Commits.validate(s.folder, listOf(stale)))
    }

    @Test
    fun inboxSorted_countsEachTopLevelQuestOnce() {
        val s = Scenario()
        val q = s.capture("Bouncy")
        repeat(10) {
            s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.LATER)))
            s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.INBOX)))
        }
        val root = s.capture("Root")
        val child = s.sub(root, "Child")
        s.ok(Events.questStatusSet(QuestStatusSet(child, QuestStatus.DROPPED)))
        assertEquals(1, s.state.counters.inboxSorted)
        assertEquals(Stats.recount(s.state), s.state.counters)
    }

    @Test
    fun historyAndLastActivity() {
        val s = Scenario()
        val q = s.tracked("Story")
        val sub = s.sub(q, "Part")
        s.done(s.stepOf(q), nextText = "next")
        val h = s.state.historyOf(q, limit = 100)
        assertTrue(h.any { it.text.startsWith("Captured") })
        assertTrue(h.any { it.text.startsWith("Tracked") })
        assertTrue(h.any { it.questId == sub && it.rootId == q })
        assertTrue(h.any { it.xp == Xp.STEP })
        assertTrue(s.state.historyOf(q, limit = 2).size == 2)
        assertNotNull(s.state.lastUserActivityAt)
        assertNull(s.state.rootOf("missing"))
        assertNull(s.state.effectiveStepOwner("missing"))
        assertFalse(s.state.needsNextStep("missing"))
    }
}
