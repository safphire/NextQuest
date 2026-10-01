// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProposalTest {
    private fun errors(s: Scenario, op: ProposalOp, payload: Any) =
        ProposalRules.validate(s.state, op.wire, ProposalOps.encode(payload))

    @Test
    fun proposalsAreInertUntilTheUserDecides() {
        val s = Scenario()
        val q = s.tracked("Q", "step")
        val id = s.proposal(ProposalOp.SET_NEXT_STEP, SetNextStepPayload(q, "AI step"))
        assertEquals("step", s.state.effectiveStep(q)!!.text)
        assertEquals(ProposalStatus.PENDING, s.state.proposals.getValue(id).status)
        assertEquals(1, s.state.pendingProposals.size)
        assertEquals(ProposalSource.CLAUDE, s.state.proposals.getValue(id).source)

        s.decide(id, Decision.Accept())
        assertEquals("AI step", s.state.effectiveStep(q)!!.text)
        assertEquals(ProposalStatus.ACCEPTED, s.state.proposals.getValue(id).status)
        assertEquals(1, s.state.counters.proposalsAccepted)
        assertTrue(Achievement.FIRST_PROPOSAL in s.state.achievements)
        assertTrue(s.state.historyOf(q, 50).any { it.text.startsWith("Accepted Claude's proposal") })
        assertFailsWith<ProposalException> { ProposalRules.decide(s.state, id, Decision.Reject) }
        assertFailsWith<ProposalException> { ProposalRules.decide(s.state, "nope", Decision.Reject) }
    }

    @Test
    fun rejectAndEdit() {
        val s = Scenario()
        val q = s.tracked("Q", "step")
        val r = s.proposal(ProposalOp.PARK_QUEST, ParkQuestPayload(q), client = "ChatGPT")
        s.decide(r, Decision.Reject)
        assertEquals(ProposalStatus.REJECTED, s.state.proposals.getValue(r).status)
        assertEquals(QuestStatus.TRACKED, s.state.quests.getValue(q).status)
        assertEquals(ProposalSource.CHATGPT, s.state.proposals.getValue(r).source)

        val e = s.proposal(ProposalOp.SET_NEXT_STEP, SetNextStepPayload(q, "vague"))
        s.decide(e, Decision.Accept(edited = ProposalOps.encode(SetNextStepPayload(q, "concrete"))))
        assertEquals("concrete", s.state.effectiveStep(q)!!.text)
        val p = s.state.proposals.getValue(e)
        assertEquals(ProposalStatus.EDITED, p.status)
        assertEquals("concrete", (p.payload["text"] as JsonPrimitive).content)
        assertEquals(1, s.state.counters.proposalsAccepted)
    }

    @Test
    fun addQuest_toInboxOrTrack_withSwapWhenFull() {
        val s = Scenario()
        val inbox = s.proposal(ProposalOp.ADD_QUEST, AddQuestPayload("Learn Kotlin", nextStep = "Read chapter 1"))
        s.decide(inbox, Decision.Accept())
        val learn = s.state.quests.values.single { it.title == "Learn Kotlin" }
        assertEquals(QuestStatus.INBOX, learn.status)
        assertEquals("Read chapter 1", s.state.openStep(learn.id)!!.text)

        repeat(5) { s.tracked("T$it") }
        // Full: a tracked add without a park is refused at proposal time…
        assertContains(errors(s, ProposalOp.ADD_QUEST, AddQuestPayload("Run", nextStep = "shoes", track = true)).single(), "slots are full")
        // …but allowed once a park of a tracked quest is pending.
        val victim = s.state.tracked.first().id
        s.proposal(ProposalOp.PARK_QUEST, ParkQuestPayload(victim))
        assertEquals(1, ProposalRules.availableSlotsForNewTracks(s.state))
        val add = s.proposal(ProposalOp.ADD_QUEST, AddQuestPayload("Run", nextStep = "shoes", track = true))
        assertEquals(0, ProposalRules.availableSlotsForNewTracks(s.state))
        assertTrue(errors(s, ProposalOp.ADD_QUEST, AddQuestPayload("Swim", nextStep = "x", track = true)).isNotEmpty())

        // Accepting Track while full without choosing a quest to park fails in the fold.
        val pending = ProposalRules.decide(s.state, add, Decision.Accept(track = true))
        val res = s.commit(*pending.toTypedArray())
        assertTrue(res is Commits.Result.Rejected && "slots are full" in res.reason)
        // The swap dialog supplies the quest to park.
        s.decide(add, Decision.Accept(track = true, parkQuestId = victim))
        assertEquals(QuestStatus.LATER, s.state.quests.getValue(victim).status)
        assertTrue(s.state.tracked.any { it.title == "Run" })
        assertEquals(5, s.state.tracked.size)

        val preview = ProposalRules.preview(s.state, s.state.proposals.getValue(add))
        assertEquals("New quest", preview.opLabel)
    }

    @Test
    fun addQuest_trackNeedsAStep_andToInboxOverridesTrack() {
        val s = Scenario()
        assertContains(errors(s, ProposalOp.ADD_QUEST, AddQuestPayload("No step", track = true)).single(), "nextStep")
        val id = s.proposal(ProposalOp.ADD_QUEST, AddQuestPayload("No step"))
        assertEquals(ProposalRules.NEEDS_NEXT_STEP,
            assertFailsWith<ProposalException> { ProposalRules.decide(s.state, id, Decision.Accept(track = true)) }.message)
        s.decide(id, Decision.Accept(track = true, nextStepText = "user typed"))
        val q = s.state.tracked.single()
        assertEquals("user typed", s.state.effectiveStep(q.id)!!.text)

        val id2 = s.proposal(ProposalOp.ADD_QUEST, AddQuestPayload("Suggested track", nextStep = "go", track = true))
        s.decide(id2, Decision.Accept(track = false))
        assertEquals(QuestStatus.INBOX, s.state.quests.values.single { it.title == "Suggested track" }.status)
    }

    @Test
    fun completeStep_requiresNextStepForTrackedQuests() {
        val s = Scenario()
        val q = s.tracked("Q", "step")
        val st = s.stepOf(q)
        val id = s.proposal(ProposalOp.COMPLETE_STEP, CompleteStepPayload(q, st))
        assertEquals(ProposalRules.NEEDS_NEXT_STEP,
            assertFailsWith<ProposalException> { ProposalRules.decide(s.state, id, Decision.Accept()) }.message)
        s.decide(id, Decision.Accept(nextStepText = "follow-up"))
        assertTrue(s.state.steps.getValue(st).isDone)
        assertEquals(StepVia.PROPOSAL, s.state.steps.getValue(st).doneVia)
        assertEquals("follow-up", s.state.effectiveStep(q)!!.text)

        val id2 = s.proposal(ProposalOp.COMPLETE_STEP, CompleteStepPayload(q, s.stepOf(q)))
        s.decide(id2, Decision.Accept(finishQuest = true))
        assertEquals(QuestStatus.DONE, s.state.quests.getValue(q).status)
    }

    @Test
    fun completeStep_onFocusedSubquest_sendsNextStepToNewFocus() {
        val s = Scenario()
        val root = s.tracked("Root", "root step")
        val a = s.sub(root, "A")
        s.advanceMinutes(1)
        val b = s.sub(root, "B")
        s.ok(Events.questFocusSet(QuestFocusSet(root, a, next = s.next(a, "a step"))))
        val id = s.proposal(ProposalOp.COMPLETE_STEP, CompleteStepPayload(a, s.stepOf(root)))
        s.decide(id, Decision.Accept(finishQuest = true, nextStepText = "b step"))
        assertEquals(b, s.state.quests.getValue(root).focusedChildId)
        assertEquals("b step", s.state.effectiveStep(root)!!.text)
        assertEquals(b, s.state.effectiveStep(root)!!.questId)
    }

    @Test
    fun addSubquest_schedule_park() {
        val s = Scenario()
        val q = s.tracked("Q", "step")
        val sub = s.proposal(ProposalOp.ADD_SUBQUEST, AddSubquestPayload(q, "Child", nextStep = "child step"))
        s.decide(sub, Decision.Accept())
        val child = s.state.children(q).single()
        assertEquals("child step", s.state.openStep(child.id)!!.text)
        assertEquals("step", s.state.effectiveStep(q)!!.text) // focus unchanged

        val st = s.stepOf(q)
        val sch = s.proposal(ProposalOp.SCHEDULE_STEP, ScheduleStepPayload(q, st, 1_800_000_000_000, alarm = true))
        val preview = ProposalRules.preview(s.state, s.state.proposals.getValue(sch))
        assertEquals("step · no date", preview.before)
        assertContains(preview.after, "alarm")
        s.decide(sch, Decision.Accept())
        assertEquals(1_800_000_000_000, s.state.steps.getValue(st).dueAt)
        assertTrue(s.state.steps.getValue(st).alarm)

        val unsch = s.proposal(ProposalOp.SCHEDULE_STEP, ScheduleStepPayload(q, st, null))
        s.decide(unsch, Decision.Accept())
        assertNull(s.state.steps.getValue(st).dueAt)

        val park = s.proposal(ProposalOp.PARK_QUEST, ParkQuestPayload(q))
        assertEquals("tracked", ProposalRules.preview(s.state, s.state.proposals.getValue(park)).before)
        s.decide(park, Decision.Accept())
        assertEquals(QuestStatus.LATER, s.state.quests.getValue(q).status)
    }

    @Test
    fun validationMessagesTellTheModelWhatIsWrong() {
        val s = Scenario()
        val q = s.tracked("Q", "step")
        val child = s.sub(q, "child")
        val done = s.capture("Done one")
        s.ok(Events.questCompleted(QuestCompleted(done)))
        val missing = Ids.newId()

        assertContains(ProposalRules.validate(s.state, "delete_everything", JsonObject(emptyMap())).single(), "unknown operation")
        assertContains(ProposalRules.validate(s.state, "add_quest", JsonObject(emptyMap())).single(), "expected fields title, why?, nextStep?, dueAt?, track?")
        assertContains(errors(s, ProposalOp.ADD_QUEST, AddQuestPayload(" ")).single(), "title must not be empty")
        assertContains(errors(s, ProposalOp.ADD_QUEST, AddQuestPayload("x".repeat(600))).single(), "longer than")
        assertContains(errors(s, ProposalOp.SET_NEXT_STEP, SetNextStepPayload(missing, "x")).single(), "does not exist")
        assertContains(errors(s, ProposalOp.SET_NEXT_STEP, SetNextStepPayload(done, "x")).single(), "already done")
        assertContains(errors(s, ProposalOp.COMPLETE_STEP, CompleteStepPayload(q, missing)).single(), "does not exist")
        val otherStep = s.step(child, "child step")
        assertContains(errors(s, ProposalOp.COMPLETE_STEP, CompleteStepPayload(q, otherStep)).single(), "does not belong")
        s.done(otherStep)
        assertContains(errors(s, ProposalOp.COMPLETE_STEP, CompleteStepPayload(child, otherStep)).single(), "already done")
        assertContains(errors(s, ProposalOp.ADD_SUBQUEST, AddSubquestPayload(child, "x")).single(), "top-level")
        assertContains(errors(s, ProposalOp.SCHEDULE_STEP, ScheduleStepPayload(q, missing, 1)).single(), "not the current step")
        assertContains(errors(s, ProposalOp.PARK_QUEST, ParkQuestPayload(child)).single(), "top-level")
        s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.LATER)))
        assertContains(errors(s, ProposalOp.PARK_QUEST, ParkQuestPayload(q)).single(), "already parked")
        assertTrue(errors(s, ProposalOp.ADD_QUEST, AddQuestPayload("fine", nextStep = "ok", track = true)).isEmpty())
    }

    @Test
    fun duplicateParkProposalsDoNotFreeTwoSlots() {
        val s = Scenario()
        repeat(5) { s.tracked("T$it") }
        val victim = s.state.tracked.first().id
        s.proposal(ProposalOp.PARK_QUEST, ParkQuestPayload(victim))
        assertContains(errors(s, ProposalOp.PARK_QUEST, ParkQuestPayload(victim)).single(), "already waiting")
        // Even if a second one slipped in (an older server), it frees the slot only once.
        s.proposal(ProposalOp.PARK_QUEST, ParkQuestPayload(victim))
        assertEquals(1, ProposalRules.availableSlotsForNewTracks(s.state))
    }

    @Test
    fun setNextStep_onAQuestWhoseStepBelongsToAFocusedSubquest_pointsTheModelThere() {
        val s = Scenario()
        val root = s.tracked("Root", "r")
        val child = s.sub(root, "Child")
        s.ok(Events.questFocusSet(QuestFocusSet(root, child, next = s.next(child, "c"))))
        assertContains(errors(s, ProposalOp.SET_NEXT_STEP, SetNextStepPayload(root, "x")).single(), child)
        assertTrue(errors(s, ProposalOp.SET_NEXT_STEP, SetNextStepPayload(child, "x")).isEmpty())
        // A stale one already stored is refused at accept time with the same explanation.
        val id = s.proposal(ProposalOp.SET_NEXT_STEP, SetNextStepPayload(child, "x"))
        s.ok(Events.questFocusSet(QuestFocusSet(root, null)))
        s.decide(id, Decision.Accept())
        assertEquals("x", s.state.openStep(child)!!.text)
    }

    @Test
    fun theClientNameMustMatchTheActor() {
        val s = Scenario()
        val created = Events.proposalCreated(
            ProposalCreated(Ids.newId(), ProposalSource.CLAUDE, "Claude", "add_quest", ProposalOps.encode(AddQuestPayload("x"))),
        )
        assertContains(s.rejected(created, actor = Actors.mcp("SomeoneElse")), "does not match")
    }

    @Test
    fun previewsShowBeforeAfter_andStaleness() {
        val s = Scenario()
        val q = s.tracked("Write", "draft")
        val set = s.proposal(ProposalOp.SET_NEXT_STEP, SetNextStepPayload(q, "edit"))
        val p = ProposalRules.preview(s.state, s.state.proposals.getValue(set))
        assertEquals(ProposalPreview("Set next step", "Write", "draft", "edit"), p)
        val comp = s.proposal(ProposalOp.COMPLETE_STEP, CompleteStepPayload(q, s.stepOf(q)))
        assertEquals("draft", ProposalRules.preview(s.state, s.state.proposals.getValue(comp)).before)
        val sub = s.proposal(ProposalOp.ADD_SUBQUEST, AddSubquestPayload(q, "Part", "go"))
        assertEquals("Part — next: go", ProposalRules.preview(s.state, s.state.proposals.getValue(sub)).after)
        val add = s.proposal(ProposalOp.ADD_QUEST, AddQuestPayload("New", nextStep = "n", track = true))
        assertTrue(ProposalRules.preview(s.state, s.state.proposals.getValue(add)).isNewQuestToTrack)

        s.done(s.stepOf(q), finish = true)
        assertContains(ProposalRules.preview(s.state, s.state.proposals.getValue(set)).stale!!, "already done")

        // Unknown op from a newer server: shown as unreadable, never applied.
        val weird = Ids.newId()
        s.ok(
            Events.proposalCreated(ProposalCreated(weird, ProposalSource.OTHER, "future", "teleport", JsonObject(emptyMap()))),
            actor = Actors.mcp("future"),
        )
        assertEquals("unreadable", ProposalRules.preview(s.state, s.state.proposals.getValue(weird)).stale)
        assertFailsWith<ProposalException> { ProposalRules.decide(s.state, weird, Decision.Accept()) }
        assertEquals("teleport", ProposalOps.label("teleport"))
        assertNull(ProposalOps.questIdOf(JsonObject(emptyMap())))
        assertFailsWith<IllegalArgumentException> { ProposalOps.encode("not a payload") }
    }

    @Test
    fun acceptingTheSameProposalOnTwoDevices_createsNothingTwice() {
        val phoneId = Ids.newId()
        val base = Scenario(deviceId = phoneId)
        val q = base.tracked("Q", "step")
        val add = base.proposal(ProposalOp.ADD_QUEST, AddQuestPayload("From AI", nextStep = "go"))
        val sub = base.proposal(ProposalOp.ADD_SUBQUEST, AddSubquestPayload(q, "Part", nextStep = "p"))
        val set = base.proposal(ProposalOp.SET_NEXT_STEP, SetNextStepPayload(q, "AI step"))
        val shared = base.log.toList()
        val state = base.state

        val phone = EventFactory(phoneId, lastSeq = base.factory.currentSeq, lastTs = base.factory.currentTs, clock = { base.now + 1_000 })
        val tablet = EventFactory(Ids.newId(), lastTs = base.factory.currentTs, clock = { base.now + 5_000 })
        val fromPhone = listOf(add, sub, set).flatMap { phone.createAll(ProposalRules.decide(state, it, Decision.Accept())) }
        val fromTablet = listOf(add, sub, set).flatMap {
            tablet.createAll(ProposalRules.decide(state, it, if (it == set) Decision.Accept(edited = ProposalOps.encode(SetNextStepPayload(q, "edited"))) else Decision.Accept()))
        }
        val merged = Fold.of(shared + fromTablet + fromPhone, base.tz)
        assertEquals(1, merged.quests.values.count { it.title == "From AI" })
        assertEquals(1, merged.quests.values.count { it.title == "Part" })
        assertEquals("AI step", merged.effectiveStep(q)!!.text) // the phone accepted first
        assertEquals(3, merged.counters.proposalsAccepted)
        assertEquals(Ids.derive(add, "quest"), Ids.derive(add, "quest"))
        assertTrue(Ids.isValid(Ids.derive(add, "quest")))
        assertTrue(Ids.derive(add, "quest") != Ids.derive(add, "step"))
    }

    @Test
    fun proposalEventsAreValidatedByTheFold() {
        val s = Scenario()
        val id = s.proposal(ProposalOp.ADD_QUEST, AddQuestPayload("x"))
        assertContains(s.rejected(Events.proposalAccepted(Ids.newId())), "unknown proposal")
        s.decide(id, Decision.Reject)
        assertContains(s.rejected(Events.proposalAccepted(id)), "already decided")
        assertContains(s.rejected(Events.proposalEdited(id, JsonObject(emptyMap()))), "already decided")
        assertContains(s.rejected(Events.proposalEdited(Ids.newId(), JsonObject(emptyMap()))), "unknown proposal")
        assertContains(
            s.rejected(
                Events.proposalCreated(ProposalCreated(id, ProposalSource.OTHER, "x", "add_quest", JsonObject(emptyMap()))),
                actor = Actors.mcp("x"),
            ),
            "already exists",
        )
        assertContains(
            s.rejected(
                Events.proposalCreated(ProposalCreated(Ids.newId(), ProposalSource.OTHER, "x", " ", JsonObject(emptyMap()))),
                actor = Actors.mcp("x"),
            ),
            "op is empty",
        )
        assertFalse(s.state.proposals.getValue(id).status == ProposalStatus.PENDING)
    }
}
