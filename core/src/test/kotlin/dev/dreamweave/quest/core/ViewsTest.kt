// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewsTest {
    @Test
    fun today_showsOneStepPerTrackedQuest() {
        val s = Scenario()
        val a = s.tracked("A", "a1")
        s.advanceMinutes(1)
        val b = s.tracked("B", "b1")
        s.sub(b, "B child")
        s.done(s.stepOf(b), via = StepVia.NOTIFICATION)
        s.proposal(ProposalOp.PARK_QUEST, ParkQuestPayload(a))
        val v = Views.today(s.state, s.now, s.tz)
        assertEquals(listOf(a, b), v.cards.map { it.quest.id })
        assertEquals("a1", v.cards[0].step!!.text)
        assertFalse(v.cards[0].needsNextStep)
        assertNull(v.cards[1].step)
        assertTrue(v.cards[1].needsNextStep)
        assertEquals(Progress(0, 1), v.cards[1].progress)
        assertEquals(2, v.slotsUsed)
        assertEquals(5, v.slotCap)
        assertEquals(1, v.pendingProposals)
        assertEquals(15, v.xp)
        assertEquals(1, v.streak.current)
        assertNull(v.resumeAfterDays)
        assertEquals(0f, Progress(0, 0).fraction)
        assertEquals(0.5f, Progress(1, 2).fraction)
    }

    @Test
    fun resumeCard_after2DaysAway() {
        val s = Scenario()
        s.tracked("A")
        assertNull(Views.today(s.state, s.now + 86_400_000, s.tz).resumeAfterDays)
        assertEquals(2, Views.today(s.state, s.now + 2 * 86_400_000, s.tz).resumeAfterDays)
        assertNull(Views.daysAway(Scenario().state, 0, s.tz))
    }

    @Test
    fun board_tabsSearchAndSort() {
        val s = Scenario()
        val zebra = s.capture("Zebra")
        s.advanceMinutes(1)
        val apple = s.capture("apple pie")
        s.advanceMinutes(1)
        val later = s.capture("Mango", QuestStatus.LATER)
        s.advanceMinutes(1)
        s.ok(Events.questEdited(QuestEdited(zebra, why = "fruit salad")))
        assertEquals(listOf(zebra, apple), Views.board(s.state, BoardTab.INBOX).map { it.id })
        assertEquals(listOf(apple, zebra), Views.board(s.state, BoardTab.INBOX, sort = BoardSort.CREATED).map { it.id })
        assertEquals(listOf(apple, zebra), Views.board(s.state, BoardTab.INBOX, sort = BoardSort.ALPHABETICAL).map { it.id })
        assertEquals(listOf(zebra), Views.board(s.state, BoardTab.INBOX, query = "SALAD").map { it.id })
        assertEquals(listOf(apple), Views.board(s.state, BoardTab.INBOX, query = "pie").map { it.id })
        assertEquals(listOf(later), Views.board(s.state, BoardTab.LATER).map { it.id })
    }

    @Test
    fun calendar_listsDatedStepsInRange() {
        val s = Scenario()
        val due = Instant.parse("2026-01-07T15:00:00Z").toEpochMilliseconds()
        val q = s.capture("Q")
        val st = s.step(q, "dated", dueAt = due)
        val other = s.capture("Other")
        s.step(other, "far", dueAt = Instant.parse("2026-02-01T10:00:00Z").toEpochMilliseconds())
        val dropped = s.capture("Dropped")
        s.step(dropped, "gone", dueAt = due)
        s.ok(Events.questStatusSet(QuestStatusSet(dropped, QuestStatus.DROPPED)))
        val items = Views.calendar(s.state, LocalDate(2026, 1, 5), LocalDate(2026, 1, 11), TimeZone.UTC)
        assertEquals(listOf(st), items.map { it.step.id })
        assertEquals(LocalDate(2026, 1, 7), items.single().date)
        assertEquals(q, items.single().root.id)
    }

    @Test
    fun chronicleAndStats() {
        val s = Scenario()
        val q = s.tracked("Epic")
        val c = s.sub(q, "Part")
        s.ok(Events.questCompleted(QuestCompleted(c)))
        s.done(s.stepOf(q), finish = true)
        val chron = Views.chronicle(s.state)
        assertEquals(1, chron.size)
        assertEquals(1, chron.single().stepsDone)
        assertEquals(1, chron.single().subquestsDone)
        val stats = Views.stats(s.state, s.now, s.tz)
        assertEquals(1, stats.questsDone)
        assertEquals(1, stats.subquestsDone)
        assertEquals(1, stats.activeDays)
        assertEquals((Xp.STEP + Xp.QUEST + Xp.SUBQUEST).toLong(), stats.xp)
        assertEquals(Achievement.entries.size, stats.achievementsTotal)
        assertTrue(stats.achievementsUnlocked >= 3)
    }

    @Test
    fun completionPlan() {
        val s = Scenario()
        val root = s.tracked("Root", "r")
        val plan = Completion.plan(s.state, s.stepOf(root), finishOwner = false)
        assertTrue(plan.nextRequired)
        assertEquals(root, plan.nextOwnerId)
        assertTrue(plan.ownerIsRoot)
        val finish = Completion.plan(s.state, s.stepOf(root), finishOwner = true)
        assertFalse(finish.nextRequired)
        assertNull(finish.nextOwnerId)

        val child = s.sub(root, "Child")
        val childStep = s.step(child, "c")
        // Completing a non-focused child's step never requires a root step.
        val p2 = Completion.plan(s.state, childStep, finishOwner = true)
        assertFalse(p2.nextRequired)
        assertEquals(root, p2.nextOwnerId)
        assertFalse(p2.ownerIsRoot)

        val inbox = s.capture("Inbox")
        val inboxStep = s.step(inbox, "i")
        assertFalse(Completion.plan(s.state, inboxStep, false).nextRequired)
        assertFailsWith<IllegalArgumentException> { Completion.plan(s.state, "missing", false) }
    }

    @Test
    fun leavePlan() {
        val s = Scenario()
        val root = s.tracked("Root", "r")
        val a = s.sub(root, "A")
        s.advanceMinutes(1)
        val b = s.sub(root, "B")
        assertEquals(Completion.LeavePlan(root, null, false), Completion.planLeave(s.state, root))
        // Not focused: leaving changes nothing about the visible step.
        assertEquals(Completion.LeavePlan(root, root, false), Completion.planLeave(s.state, a))
        s.ok(Events.questFocusSet(QuestFocusSet(root, a, next = s.next(a, "a"))))
        // Focused, next subquest has no step → required.
        assertEquals(Completion.LeavePlan(root, b, true), Completion.planLeave(s.state, a))
        s.step(b, "b")
        assertEquals(Completion.LeavePlan(root, b, false), Completion.planLeave(s.state, a))
        assertEquals(Completion.LeavePlan(root, a, false), Completion.planLeave(s.state, b))
        assertFailsWith<IllegalArgumentException> { Completion.planLeave(s.state, "missing") }
    }
}
