// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** v1.1 proposals (practices, cues), return policies, the breakdown prompt and the vault note. */
class FocusLayerTest {
    private val daily = Rhythm(RhythmKind.DAILY, reminderMinute = 7 * 60 + 30)

    @Test
    fun addPracticeProposal_isValidated_andAcceptingCreatesAnInboxPractice() {
        val s = Scenario()
        assertTrue(ProposalRules.validate(s.state, "add_practice", ProposalOps.encode(AddPracticePayload("Sing", Rhythm(RhythmKind.WEEKDAYS), "Scales"))).single().contains("weekday"))
        assertTrue(ProposalRules.validate(s.state, "add_practice", ProposalOps.encode(AddPracticePayload("Sing", daily, " "))).single().contains("firstStep"))
        val pid = s.proposal(ProposalOp.ADD_PRACTICE, AddPracticePayload("Sing", daily, "20 min scales", why = "Choir in May"))
        val preview = ProposalRules.preview(s.state, s.state.proposals.getValue(pid))
        assertEquals("New practice", preview.opLabel)
        assertTrue(preview.after.contains("Daily · 07:30"))
        assertNull(preview.stale)
        // Nothing changed before acceptance.
        assertTrue(s.state.quests.isEmpty())
        s.decide(pid, Decision.Accept())
        val q = s.state.quests.values.single()
        assertEquals(QuestStatus.INBOX, q.status)
        assertEquals(daily, q.rhythm)
        assertEquals("Choir in May", q.why)
        assertEquals("20 min scales", s.state.openStep(q.id)!!.text)
        assertEquals(ProposalStatus.ACCEPTED, s.state.proposals.getValue(pid).status)
    }

    @Test
    fun subquestProposals_forAPractice_areRefused() {
        val s = Scenario()
        val p = Ids.newId(s.now)
        s.ok(Events.questCreated(QuestCreated(p, "Sing", rhythm = daily)))
        val problems = ProposalRules.problems(s.state, "add_subquest", ProposalOps.encode(AddSubquestPayload(p, "Breathing")))
        assertEquals(ProblemCode.PRACTICE, problems.single().code)
    }

    @Test
    fun cuesThroughProposals() {
        val s = Scenario()
        val q = s.tracked("Report", "Open the doc")
        val step = s.stepOf(q)
        val set = s.proposal(ProposalOp.SET_NEXT_STEP, SetNextStepPayload(q, "Write the intro", cue = "after lunch, at my desk"))
        assertTrue(ProposalRules.preview(s.state, s.state.proposals.getValue(set)).after.contains("after lunch"))
        s.decide(set, Decision.Accept())
        assertEquals("after lunch, at my desk", s.state.openStep(q)!!.cue)
        assertTrue(s.state.steps.getValue(step).superseded)

        val newStep = s.stepOf(q)
        val due = Instant.parse("2026-01-06T09:00:00Z").toEpochMilliseconds()
        s.ok(Events.stepEdited(StepEdited(newStep, dueAt = due)))
        // Only the cue: the date stays.
        val cueOnly = s.proposal(ProposalOp.SCHEDULE_STEP, ScheduleStepPayload(q, newStep, null, cue = "at the library", keepDue = true))
        s.decide(cueOnly, Decision.Accept())
        assertEquals("at the library", s.state.openStep(q)!!.cue)
        assertEquals(due, s.state.openStep(q)!!.dueAt)
        // An empty cue removes it.
        val clear = s.proposal(ProposalOp.SCHEDULE_STEP, ScheduleStepPayload(q, newStep, due, cue = ""))
        s.decide(clear, Decision.Accept())
        assertNull(s.state.openStep(q)!!.cue)
        // Nothing to change is refused.
        assertTrue(ProposalRules.validate(s.state, "schedule_step", ProposalOps.encode(ScheduleStepPayload(q, newStep, null, keepDue = true))).isNotEmpty())
        assertTrue(ProposalRules.validate(s.state, "set_next_step", ProposalOps.encode(SetNextStepPayload(q, "x", cue = "y".repeat(300)))).single().contains("cue"))
    }

    @Test
    fun freshStartOffers_afterTimeAway_newWeek_newMonth_andRespectPauses() {
        val mon = LocalDate(2026, 1, 12)
        val sun = LocalDate(2026, 1, 11)
        assertNull(Returning.freshStartOffer(null, mon, null, null), "never on the very first open")
        assertNull(Returning.freshStartOffer(mon, mon, 0, null), "not twice a day")
        assertEquals(Returning.FreshStartReason.NEW_WEEK, Returning.freshStartOffer(sun, mon, 1, null))
        assertEquals(Returning.FreshStartReason.AWAY, Returning.freshStartOffer(LocalDate(2026, 1, 8), mon, 4, null))
        assertEquals(Returning.FreshStartReason.NEW_MONTH, Returning.freshStartOffer(LocalDate(2026, 4, 30), LocalDate(2026, 5, 1), 1, null))
        assertNull(Returning.freshStartOffer(LocalDate(2026, 1, 13), LocalDate(2026, 1, 14), 1, null), "an ordinary Wednesday")
        assertNull(Returning.freshStartOffer(sun, mon, 1, LocalDate(2026, 2, 1)), "paused")
        assertEquals(Returning.FreshStartReason.NEW_WEEK, Returning.freshStartOffer(sun, mon, 1, mon), "the pause has ended")
        // Two dismissals in a row pause it for 30 days; one doesn't.
        assertEquals(1 to null, Returning.afterDismissal(0, mon))
        assertEquals(0 to LocalDate(2026, 2, 11), Returning.afterDismissal(1, mon))
    }

    @Test
    fun forgiveness_afterADayWithoutAStep_neverForNewcomersOrAfterLongAbsences() {
        val s = Scenario(start = "2026-01-05T09:00:00Z") // Monday
        val q = s.tracked("Run", "Shoes on")
        assertFalse(Returning.forgivenessDue(s.state, s.now, s.tz, null), "nothing done yet: nothing to forgive")
        s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), next = s.next(q, "Run 1 km"), local = LocalStamp.of(s.now, s.tz))))
        s.at("2026-01-06T10:00:00Z") // Tuesday: active, captures something, no step
        s.capture("Idea")
        s.at("2026-01-07T08:00:00Z") // Wednesday
        assertTrue(Returning.forgivenessDue(s.state, s.now, s.tz, null))
        assertFalse(Returning.forgivenessDue(s.state, s.now, s.tz, LocalDate(2026, 1, 7)), "dismissed today")
        s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), next = s.next(q, "Run 2 km"), local = LocalStamp.of(s.now, s.tz))))
        assertFalse(Returning.forgivenessDue(s.state, s.now, s.tz, null), "already back at it today")
        s.at("2026-01-12T08:00:00Z")
        assertFalse(Returning.forgivenessDue(s.state, s.now, s.tz, null), "away for days: the resume card speaks instead")
        val days = (0 until 8).map { Returning.variant(LocalDate(2026, 1, 1 + it), 4) }
        assertEquals(setOf(0, 1, 2, 3), days.toSet())
    }

    @Test
    fun breakdownPrompt_carriesTheQuestAndAsksForSmallSteps() {
        val s = Scenario()
        val q = s.tracked("Write the report", "Open the doc")
        s.ok(Events.questEdited(QuestEdited(q, why = "My team needs it", obstacle = "Blank page", plan = "If stuck, I write one ugly sentence")))
        s.sub(q, "Gather numbers")
        val offline = Breakdown.prompt(s.state, q, paired = false)
        listOf("Write the report", "My team needs it", "Blank page", "one ugly sentence", "Open the doc", "Gather numbers", "3 to 7", "under 10 minutes", "one subquest per line")
            .forEach { assertTrue(it in offline, "missing '$it'") }
        assertFalse("propose_add_subquest" in offline)
        val paired = Breakdown.prompt(s.state, q, paired = true)
        assertTrue("propose_add_subquest" in paired && q in paired)
        assertFailsWith<IllegalArgumentException> { Breakdown.prompt(s.state, Ids.newId(), false) }
    }

    @Test
    fun pastedLists_becomeOneInboxItemPerLine() {
        val reply = """
            1. Open the doc
            2) Write a title
            - List three numbers
            * **Ask Sam**
            [ ] Send it

        """.trimIndent()
        assertEquals(listOf("Open the doc", "Write a title", "List three numbers", "Ask Sam", "Send it"), CaptureText.items(reply))
        assertTrue(CaptureText.isList(reply))
        assertFalse(CaptureText.isList("Just one thing"))
        assertEquals(listOf("2026 plans"), CaptureText.items("2026 plans"))
    }

    @Test
    fun vaultNote_forAPractice_withCueObstacleAndPlan() {
        val s = Scenario(start = "2026-01-05T09:00:00Z")
        val q = Ids.newId(s.now)
        s.ok(Events.questCreated(QuestCreated(q, "Sing", rhythm = Rhythm(RhythmKind.WEEKDAYS, listOf(1, 3, 5)))))
        s.ok(Events.stepSet(StepSet(Ids.newId(s.now), q, "20 min scales", cue = "after breakfast")))
        s.track(q)
        s.ok(Events.questEdited(QuestEdited(q, obstacle = "Neighbours", plan = "If they're home, I hum")))
        s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), local = LocalStamp.of(s.now, s.tz))))
        val note = VaultMarkdown.render(s.state, s.tz, nowMs = s.now).single().content
        listOf("type: practice", "rhythm: \"Mon, Wed, Fri\"", "next-session:: 20 min scales", "cue:: after breakfast", "this-week:: 1/3", "obstacle:: Neighbours", "plan:: If they're home, I hum", "Session: 20 min scales")
            .forEach { assertTrue(it in note, "missing '$it' in\n$note") }
    }

    @Test
    fun undoableCompletionsAreListed() {
        val s = Scenario()
        val q = s.tracked("Run")
        s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), next = s.next(q, "again"))))
        val e = s.log.last().id
        assertIs<Long>(s.state.undoable[e])
    }
}
