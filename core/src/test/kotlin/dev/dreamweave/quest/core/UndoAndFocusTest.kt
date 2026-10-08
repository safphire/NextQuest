// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoAndFocusTest {
    private fun Scenario.lastId() = log.last().id

    private fun Scenario.complete(stepId: String, next: String? = null, finish: Boolean = false, via: StepVia = StepVia.APP): String {
        val owner = Completion.plan(state, stepId, finish).nextOwnerId
        ok(Events.stepCompleted(StepCompleted(stepId, via, finish, next?.let { n -> owner?.let { NextStep(Ids.newId(now), it, n) } }, LocalStamp.of(now, tz))))
        return lastId()
    }

    private fun Scenario.undo(eventId: String) = ok(Events.completionUndone(CompletionUndone(eventId)))

    @Test
    fun undoingAStep_restoresXpStreakAchievementsAndTheStep() {
        val s = Scenario()
        val q = s.tracked("Run", "Shoes on")
        val before = s.state
        val step = s.stepOf(q)
        val e = s.complete(step, next = "Run 1 km")
        assertEquals(15, s.state.xp)
        assertTrue(Achievement.FIRST_STEP in s.state.achievements)
        assertEquals(1, s.state.streak(s.now).current)
        assertTrue(s.state.canUndo(e, s.now))

        s.advanceMinutes(1)
        s.undo(e)
        val after = s.state
        assertEquals(0, after.xp)
        assertEquals(before.counters, after.counters)
        assertTrue(after.achievements.isEmpty())
        assertEquals(0, after.streak(s.now).current)
        assertEquals(step, after.effectiveStep(q)!!.id)
        assertEquals(before.steps, after.steps)
        assertEquals(before.quests, after.quests)
        // The history keeps both: nothing is silently deleted.
        assertTrue(after.historyOf(q).any { it.text == "Done: Shoes on" })
        assertTrue(after.historyOf(q).any { it.text.startsWith("Undid:") })
        assertFalse(after.canUndo(e, s.now))
        assertEquals("already undone", s.rejected(Events.completionUndone(CompletionUndone(e))))
        assertEquals(Stats.recount(after), after.counters)
    }

    @Test
    fun undoingAQuestCompletion_restoresFocusAndTheQuest() {
        val s = Scenario()
        val q = s.tracked("Ship it", "Plan")
        val a = s.sub(q, "Design")
        val b = s.sub(q, "Build")
        s.ok(Events.questFocusSet(QuestFocusSet(q, a, next = s.next(a, "Sketch"))))
        val before = s.state
        // Finishing subquest A moves focus to B, which has no step yet: the completion carries one.
        val e = s.complete(s.stepOf(q), next = "Wire it", finish = true)
        assertEquals(b, s.state.quests.getValue(q).focusedChildId)
        s.undo(e)
        assertEquals(a, s.state.quests.getValue(q).focusedChildId)
        assertEquals(before.quests, s.state.quests)
        assertEquals(before.counters, s.state.counters)
    }

    @Test
    fun undoingAFinishedQuest_putsItBackInItsSlot() {
        val s = Scenario()
        val q = s.tracked("Ship it", "Last bit")
        val e = s.complete(s.stepOf(q), finish = true)
        assertEquals(QuestStatus.DONE, s.state.quests.getValue(q).status)
        assertEquals(Xp.STEP + Xp.QUEST.toLong(), s.state.xp)
        s.undo(e)
        assertEquals(QuestStatus.TRACKED, s.state.quests.getValue(q).status)
        assertEquals(0, s.state.xp)
        assertTrue(Views.chronicle(s.state).isEmpty())
    }

    @Test
    fun undoIsRefusedOnceAnythingItChangedChanged_orTheCapWouldBreak() {
        val s = Scenario()
        val q = s.tracked("Run", "Shoes on")
        val e = s.complete(s.stepOf(q), next = "Run 1 km")
        s.ok(Events.stepEdited(StepEdited(s.stepOf(q), text = "Run 2 km")))
        assertTrue(s.rejected(Events.completionUndone(CompletionUndone(e))).contains("changed"))

        // Finishing a quest frees a slot; once another quest takes it, the undo would exceed the cap.
        val t = Scenario()
        repeat(Limits.DEFAULT_CAP) { t.tracked("Q$it") }
        val first = t.state.tracked.first().id
        val done = t.complete(t.stepOf(first), finish = true)
        t.tracked("Newcomer")
        assertTrue(t.rejected(Events.completionUndone(CompletionUndone(done))).contains("slots are full"))
        assertEquals(Limits.DEFAULT_CAP, t.state.tracked.size)
    }

    @Test
    fun undoWindowAndUnknownEvents() {
        val s = Scenario()
        val q = s.tracked("Run", "Shoes on")
        val e = s.complete(s.stepOf(q), next = "Run 1 km")
        assertTrue(s.rejected(Events.completionUndone(CompletionUndone(Ids.newId()))).contains("nothing to undo"))
        s.advanceMinutes(61)
        assertFalse(s.state.canUndo(e, s.now))
        val reason = s.rejected(Events.completionUndone(CompletionUndone(e)))
        assertTrue(reason.contains("too late") || reason.contains("nothing to undo"), reason)
    }

    @Test
    fun undoingANotificationDone_putsTheStepBack() {
        val s = Scenario()
        val q = s.tracked("Run", "Shoes on")
        val e = s.complete(s.stepOf(q), via = StepVia.NOTIFICATION)
        assertTrue(s.state.needsNextStep(q))
        s.undo(e)
        assertFalse(s.state.needsNextStep(q))
        assertEquals("Shoes on", s.state.effectiveStep(q)!!.text)
    }

    @Test
    fun undoingAPracticeSession_removesTheAutomaticNextSession() {
        val s = Scenario()
        val q = Ids.newId(s.now)
        s.ok(Events.questCreated(QuestCreated(q, "Sing", rhythm = Rhythm(RhythmKind.DAILY))))
        s.step(q, "Scales")
        s.track(q)
        val before = s.state
        val first = s.stepOf(q)
        val e = s.complete(first)
        assertTrue(s.state.steps.containsKey(Ids.derive(first, "session")))
        s.undo(e)
        assertEquals(before.steps, s.state.steps)
        assertEquals(first, s.stepOf(q))
    }

    @Test
    fun undoKeepsAStreakDayThatHasOtherSteps() {
        val s = Scenario()
        val a = s.tracked("A")
        val b = s.tracked("B")
        s.complete(s.stepOf(a), next = "a2")
        s.advanceMinutes(5)
        val e = s.complete(s.stepOf(b), next = "b2")
        s.undo(e)
        assertEquals(1, s.state.streak(s.now).current)
        assertEquals(Stats.recount(s.state), s.state.counters)
    }

    @Test
    fun focusBlocks_earnEffortXp_cappedPerDay() {
        val s = Scenario()
        val q = s.tracked("Write", "Draft intro")
        val step = s.stepOf(q)
        fun block(minutes: Int) = Events.focusBlockCompleted(FocusBlockCompleted(q, step, minutes, LocalStamp.of(s.now, s.tz)))
        assertTrue(s.rejected(block(9)).contains("at least"))
        assertTrue(s.rejected(block(500)).contains("at most"))
        s.ok(block(25))
        assertEquals(Xp.FOCUS_BLOCK.toLong(), s.state.xp)
        assertTrue(Achievement.FIRST_FOCUS in s.state.achievements)
        repeat(20) {
            s.advanceMinutes(30)
            s.ok(block(25))
        }
        assertEquals(Xp.FOCUS_DAILY_CAP.toLong(), s.state.xp, "effort XP is capped at 60 a day")
        assertEquals(21 * 25, s.state.counters.focusMinutes)
        assertEquals(21, s.state.counters.focusBlocks)
        // A new local day has a fresh allowance.
        s.at("2026-01-06T09:00:00Z")
        s.ok(block(10))
        assertEquals(Xp.FOCUS_DAILY_CAP.toLong() + Xp.FOCUS_BLOCK, s.state.xp)
        assertEquals(Stats.recount(s.state), s.state.counters)
        assertEquals(s.state.counters, Fold.of(s.log, s.tz).counters)
        // 10 hours focused unlocks the achievement.
        assertTrue(Achievement.FOCUS_10H !in s.state.achievements) // 535 minutes so far
        s.advanceMinutes(1)
        s.ok(block(240))
        assertTrue(s.state.counters.focusMinutes >= 600)
        assertTrue(Achievement.FOCUS_10H in s.state.achievements)
        assertEquals(s.state.counters.focusMinutes, Views.stats(s.state, s.now, s.tz).focusMinutes)
    }

    @Test
    fun aFocusBlock_touchesItsStep_soItIsNotStuck() {
        val s = Scenario()
        val q = s.tracked("Write", "Draft intro")
        s.advanceHours(24 * 3 + 1)
        assertTrue(Views.isStuck(s.state, q, s.now))
        assertTrue(Views.today(s.state, s.now, s.tz).cards.single().stuck)
        assertFalse(Views.isStuck(s.state, q, s.now, stuckDays = 4))
        s.ok(Events.focusBlockCompleted(FocusBlockCompleted(q, s.stepOf(q), 10, LocalStamp.of(s.now, s.tz))))
        assertFalse(Views.isStuck(s.state, q, s.now))
        s.advanceHours(24 * 3 + 1)
        assertTrue(Views.isStuck(s.state, q, s.now))
        s.ok(Events.stepEdited(StepEdited(s.stepOf(q), text = "Write one sentence")))
        assertFalse(Views.isStuck(s.state, q, s.now))
        // Practices are never "stuck".
        val p = Ids.newId(s.now)
        s.ok(Events.questCreated(QuestCreated(p, "Sing", rhythm = Rhythm(RhythmKind.DAILY))))
        s.step(p, "Scales")
        s.track(p)
        s.advanceHours(24 * 10)
        assertFalse(Views.isStuck(s.state, p, s.now))
        // A focus block on someone else's step is refused.
        val other = s.tracked("Other")
        assertTrue(s.rejected(Events.focusBlockCompleted(FocusBlockCompleted(q, s.stepOf(other), 10))).contains("does not belong"))
    }

    @Test
    fun freshStart_clearsSnoozesAndPastDates_andParksAllButOne() {
        val s = Scenario()
        val a = s.tracked("A", "a")
        val b = s.tracked("B", "b")
        val c = s.tracked("C", "c")
        val past = s.now - 3_600_000
        val future = s.now + 86_400_000
        s.ok(Events.stepEdited(StepEdited(s.stepOf(a), dueAt = past, alarm = true)))
        s.ok(Events.stepEdited(StepEdited(s.stepOf(b), dueAt = future, alarm = true)))
        s.ok(Events.stepSnoozed(StepSnoozed(s.stepOf(c), s.now + 3_600_000)))
        s.ok(Events.stepReminderDismissed(StepReminderDismissed(s.stepOf(b))))
        s.ok(Events.focusBlockCompleted(FocusBlockCompleted(a, s.stepOf(a), 25, LocalStamp.of(s.now, s.tz))))
        s.complete(s.stepOf(a), next = "a2")
        s.ok(Events.stepEdited(StepEdited(s.stepOf(a), dueAt = past)))
        val before = s.state

        s.ok(Events.freshStart(FreshStart()))
        val st = s.state
        assertNull(st.effectiveStep(a)!!.dueAt)
        assertEquals(future, st.effectiveStep(b)!!.dueAt) // future dates stay
        assertTrue(st.effectiveStep(b)!!.alarm)
        assertFalse(st.effectiveStep(b)!!.reminderDismissed)
        assertNull(st.effectiveStep(c)!!.snoozedUntil)
        assertEquals(3, st.tracked.size)
        // XP, level, achievements and the streak are untouched.
        assertEquals(before.counters, st.counters)
        assertEquals(before.achievements, st.achievements)
        assertEquals(before.streak(s.now), st.streak(s.now))

        assertTrue(s.rejected(Events.freshStart(FreshStart(parkOthers = true))).contains("pick"))
        s.ok(Events.freshStart(FreshStart(keepQuestId = b, parkOthers = true)))
        assertEquals(listOf(b), s.state.tracked.map { it.id })
        assertEquals(QuestStatus.LATER, s.state.quests.getValue(a).status)
        assertTrue(s.rejected(Events.freshStart(FreshStart(keepQuestId = a, parkOthers = true))).contains("tracked"))
        assertTrue(s.state.history.any { it.text == "Fresh start" })
    }

    @Test
    fun cuesAndObstacles() {
        val s = Scenario()
        val q = s.capture("Write the report")
        s.ok(Events.stepSet(StepSet(Ids.newId(s.now), q, "Open the doc", cue = "  after lunch,   at my desk ")))
        assertEquals("after lunch, at my desk", s.state.openStep(q)!!.cue)
        s.ok(Events.stepEdited(StepEdited(s.stepOf(q), cue = "before standup")))
        assertEquals("before standup", s.state.openStep(q)!!.cue)
        s.ok(Events.stepEdited(StepEdited(s.stepOf(q), clearCue = true)))
        assertNull(s.state.openStep(q)!!.cue)
        assertTrue(s.rejected(Events.stepEdited(StepEdited(s.stepOf(q), cue = "x".repeat(Limits.MAX_CUE + 1)))).contains("cue"))
        // The next step from "What's next?" carries its cue too.
        s.track(q)
        s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), next = NextStep(Ids.newId(s.now), q, "Outline", cue = "at 9, kitchen table"))))
        assertEquals("at 9, kitchen table", s.state.openStep(q)!!.cue)

        s.ok(Events.questEdited(QuestEdited(q, obstacle = "I get distracted by email", plan = "If I open email, I close it and write one line")))
        assertEquals("I get distracted by email", s.state.quests.getValue(q).obstacle)
        assertEquals("If I open email, I close it and write one line", s.state.quests.getValue(q).plan)
        s.ok(Events.questEdited(QuestEdited(q, clearObstacle = true)))
        assertNull(s.state.quests.getValue(q).obstacle)
        assertTrue(s.rejected(Events.questEdited(QuestEdited(q, plan = "x".repeat(Limits.MAX_NOTE + 1)))).contains("plan"))
    }
}
