// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PracticeTest {
    private val mwf = Rhythm(RhythmKind.WEEKDAYS, days = listOf(5, 1, 3))

    /** A tracked practice with its first session; returns the quest id. */
    private fun Scenario.practice(title: String = "Sing", rhythm: Rhythm = mwf, session: String = "20 min scales"): String {
        val id = Ids.newId(now)
        ok(Events.questCreated(QuestCreated(id, title, rhythm = rhythm)))
        step(id, session)
        track(id)
        return id
    }

    private fun Scenario.session(q: String, via: StepVia = StepVia.APP) {
        ok(Events.stepCompleted(StepCompleted(stepOf(q), via, local = LocalStamp.of(now, tz))))
    }

    @Test
    fun rhythmIsValidatedAndNormalized() {
        val s = Scenario() // Monday 2026-01-05
        val q = s.practice(rhythm = mwf)
        assertEquals(listOf(1, 3, 5), s.state.quests.getValue(q).rhythm!!.days)
        assertTrue(s.rejected(Events.questCreated(QuestCreated(Ids.newId(s.now), "x", rhythm = Rhythm(RhythmKind.WEEKDAYS)))).contains("weekday"))
        assertTrue(s.rejected(Events.questCreated(QuestCreated(Ids.newId(s.now), "x", rhythm = Rhythm(RhythmKind.PER_WEEK, times = 9)))).contains("times"))
        assertTrue(s.rejected(Events.questCreated(QuestCreated(Ids.newId(s.now), "x", rhythm = Rhythm(RhythmKind.DAILY, reminderMinute = 2000)))).contains("reminder"))
        assertTrue(s.rejected(Events.questCreated(QuestCreated(Ids.newId(s.now), "x", rhythm = Rhythm(RhythmKind.WEEKDAYS, listOf(1, 1))))).contains("twice"))
        // Subquests can't be practices, and practices have no subquests.
        assertTrue(s.rejected(Events.questCreated(QuestCreated(Ids.newId(s.now), "x", parentId = q))).contains("no subquests"))
        assertEquals("Mon, Wed, Fri", Practices.label(s.state.quests.getValue(q).rhythm!!))
        assertEquals("Weekdays · 07:30", Practices.label(Rhythm(RhythmKind.WEEKDAYS, listOf(1, 2, 3, 4, 5), reminderMinute = 450)))
        assertEquals("3× a week", Practices.label(Rhythm(RhythmKind.PER_WEEK, times = 3)))
        assertEquals("Once a week", Practices.label(Rhythm(RhythmKind.PER_WEEK, times = 1)))
        assertEquals("Daily", Practices.label(Rhythm(RhythmKind.DAILY)))
        assertEquals("Weekends", Practices.label(Rhythm(RhythmKind.WEEKDAYS, listOf(7, 6))))
    }

    @Test
    fun sessionDone_logsXp_andTheNextSessionAppearsByItself_onEveryPath() {
        val s = Scenario()
        val q = s.practice()
        val first = s.stepOf(q)
        s.session(q)
        val st = s.state
        assertEquals(Xp.STEP.toLong(), st.xp)
        val next = st.effectiveStep(q)
        assertNotNull(next)
        assertEquals("20 min scales", next.text)
        assertEquals(Ids.derive(first, "session"), next.id)
        assertFalse(st.needsNextStep(q))
        // From a notification too: a practice never shows "Needs next step".
        s.advanceHours(48)
        s.session(q, StepVia.NOTIFICATION)
        assertFalse(s.state.needsNextStep(q))
        assertEquals(2 * Xp.STEP.toLong(), s.state.xp)
        // An edited next session (from the sheet) replaces the automatic one.
        s.advanceHours(48)
        s.ok(Events.stepCompleted(StepCompleted(s.stepOf(q), next = s.next(q, "30 min songs"), local = LocalStamp.of(s.now, s.tz))))
        assertEquals("30 min songs", s.state.effectiveStep(q)!!.text)
    }

    @Test
    fun practicesNeverComplete_butCanBeParkedOrDropped() {
        val s = Scenario()
        val q = s.practice()
        assertTrue(s.rejected(Events.questCompleted(QuestCompleted(q))).contains("never complete"))
        assertTrue(s.rejected(Events.stepCompleted(StepCompleted(s.stepOf(q), finishQuest = true))).contains("never complete"))
        val plan = Completion.plan(s.state, s.stepOf(q), finishOwner = false)
        assertFalse(plan.nextRequired)
        assertFalse(plan.canFinishOwner)
        s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.LATER)))
        s.ok(Events.questStatusSet(QuestStatusSet(q, QuestStatus.DROPPED)))
    }

    @Test
    fun practicesCountAgainstTheCap() {
        val s = Scenario()
        repeat(Limits.DEFAULT_CAP) { s.practice("P$it") }
        val extra = Ids.newId(s.now)
        s.ok(Events.questCreated(QuestCreated(extra, "One more", rhythm = Rhythm(RhythmKind.DAILY))))
        s.step(extra, "go")
        assertTrue(s.rejected(Events.questStatusSet(QuestStatusSet(extra, QuestStatus.TRACKED))).contains("slots are full"))
    }

    @Test
    fun missingSessions_neverPileUp_onlyThisWeekChanges() {
        val s = Scenario(start = "2026-01-05T09:00:00Z") // Monday
        val q = s.practice(rhythm = mwf)
        val monday = LocalDate(2026, 1, 5)
        var status = Practices.status(s.state, s.state.quests.getValue(q), monday)!!
        assertTrue(status.dueToday)
        assertEquals(Practices.Week(0, 3), status.week)
        s.session(q)
        status = Practices.status(s.state, s.state.quests.getValue(q), monday)!!
        assertFalse(status.dueToday)
        assertEquals(LocalDate(2026, 1, 7), status.nextSessionDay)
        assertEquals(Practices.Week(1, 3), status.week)

        // A whole week with nothing: next Monday it's simply due again. One step, no backlog, no count.
        val nextMonday = LocalDate(2026, 1, 19)
        val stepsBefore = s.state.steps.values.count { it.questId == q && !it.isDone }
        status = Practices.status(s.state, s.state.quests.getValue(q), nextMonday)!!
        assertTrue(status.dueToday)
        assertEquals(Practices.Week(0, 3), status.week)
        assertEquals(1, stepsBefore)
        assertNull(s.state.effectiveStep(q)!!.dueAt)
    }

    @Test
    fun perWeekAndDailyRhythms() {
        val s = Scenario(start = "2026-01-05T09:00:00Z")
        val q = s.practice("Draw", Rhythm(RhythmKind.PER_WEEK, times = 2))
        s.session(q)
        s.at("2026-01-06T09:00:00Z")
        s.session(q)
        val wed = LocalDate(2026, 1, 7)
        val st = Practices.status(s.state, s.state.quests.getValue(q), wed)!!
        assertFalse(st.dueToday)
        assertEquals(LocalDate(2026, 1, 12), st.nextSessionDay) // next week
        assertEquals(Practices.Week(2, 2), st.week)

        val d = s.practice("Study", Rhythm(RhythmKind.DAILY))
        s.session(d)
        val tue = LocalDate(2026, 1, 6)
        assertEquals(LocalDate(2026, 1, 7), Practices.status(s.state, s.state.quests.getValue(d), tue)!!.nextSessionDay)
    }

    @Test
    fun calendar_showsLoggedAndUpcomingSessions_neverMissedOnes() {
        val s = Scenario(start = "2026-01-05T09:00:00Z")
        val q = s.practice(rhythm = mwf)
        s.session(q) // Monday
        val today = LocalDate(2026, 1, 9) // Friday; Wednesday passed without a session
        val days = Views.practiceCalendar(s.state, LocalDate(2026, 1, 5), LocalDate(2026, 1, 16), today)
        assertEquals(
            listOf(LocalDate(2026, 1, 5) to true, LocalDate(2026, 1, 9) to false, LocalDate(2026, 1, 12) to false, LocalDate(2026, 1, 14) to false, LocalDate(2026, 1, 16) to false),
            days.map { it.date to it.done },
        )
        // "N per week" has no fixed days to plan.
        val p = s.practice("Draw", Rhythm(RhythmKind.PER_WEEK, times = 2))
        assertTrue(Views.practiceCalendar(s.state, today, today, today).none { it.quest.id == p })
    }

    @Test
    fun rhythmSet_turnsAQuestIntoAPracticeAndBack() {
        val s = Scenario()
        val q = s.tracked("Guitar")
        val sub = s.sub(q, "Learn chords")
        assertTrue(s.rejected(Events.questRhythmSet(QuestRhythmSet(q, Rhythm(RhythmKind.DAILY)))).contains("no subquests"))
        s.ok(Events.questStatusSet(QuestStatusSet(sub, QuestStatus.DROPPED)))
        s.ok(Events.questRhythmSet(QuestRhythmSet(q, Rhythm(RhythmKind.DAILY))))
        assertTrue(s.state.quests.getValue(q).isPractice)
        assertTrue(s.rejected(Events.questRhythmSet(QuestRhythmSet(q, Rhythm(RhythmKind.DAILY)))).contains("unchanged"))
        assertTrue(s.rejected(Events.questRhythmSet(QuestRhythmSet(sub, Rhythm(RhythmKind.DAILY)))).isNotEmpty())
        s.ok(Events.questRhythmSet(QuestRhythmSet(q, null)))
        assertFalse(s.state.quests.getValue(q).isPractice)
        val today = Views.today(s.state, s.now, s.tz)
        assertNull(today.cards.single().practice)
    }

    @Test
    fun todayCard_carriesThePracticeStatus() {
        val s = Scenario()
        val q = s.practice()
        val card = Views.today(s.state, s.now, s.tz).cards.single { it.quest.id == q }
        assertNotNull(card.practice)
        assertTrue(card.practice!!.dueToday)
        assertFalse(card.practice!!.doneToday)
        assertFalse(card.stuck)
    }
}
