// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Fold
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.LocalStamp
import dev.dreamweave.quest.core.NextStep
import dev.dreamweave.quest.core.PendingEvent
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.QuestStatusSet
import dev.dreamweave.quest.core.Rhythm
import dev.dreamweave.quest.core.RhythmKind
import dev.dreamweave.quest.core.StepCompleted
import dev.dreamweave.quest.core.StepSnoozed
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.ui.sheets.WhenWhere
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Practice reminders (B1) fire on session days at their time, never pile up, and Skip skips one day. */
class PracticeReminderTest {
    private val utc = TimeZone.UTC
    private fun t(iso: String) = Instant.parse(iso).toEpochMilliseconds()
    private var clock = t("2026-03-04T06:00:00Z") // a Wednesday
    private val factory = EventFactory(Ids.newId(), clock = { clock })
    private val log = mutableListOf<Event>()
    private fun add(vararg p: PendingEvent) { log += p.map { factory.create(it.type, it.payload) } }
    private fun state(): QuestState = Fold.of(log, utc)
    private val quiet = AppSettings(digestEnabled = false, streakGuardEnabled = false)

    /** Mon / Wed / Fri at 07:30. */
    private fun practice(): String {
        val q = Ids.newId(clock)
        add(
            Events.questCreated(QuestCreated(q, "Sing", rhythm = Rhythm(RhythmKind.WEEKDAYS, listOf(1, 3, 5), reminderMinute = 7 * 60 + 30))),
            Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED, NextStep(Ids.newId(clock), q, "20 min scales"))),
        )
        return q
    }

    @Test
    fun sessionDaysOnly_atTheirTime() {
        val q = practice()
        val step = state().openStep(q)!!.id
        assertEquals(listOf(Planned.StepReminder(t("2026-03-04T07:30:00Z"), step, alarm = false)), ReminderPlan.compute(state(), quiet, clock, utc, emptyMap()))
        // Fired today: the next one is Friday (Thursday isn't a session day).
        val fired = mapOf(step to t("2026-03-04T07:30:00Z"))
        assertEquals(t("2026-03-06T07:30:00Z"), ReminderPlan.compute(state(), quiet, t("2026-03-04T07:31:00Z"), utc, fired).single().at)
        // A time that passed while the phone was off isn't caught up: tomorrow's (Friday's) is next.
        assertEquals(t("2026-03-06T07:30:00Z"), ReminderPlan.compute(state(), quiet, t("2026-03-04T12:00:00Z"), utc, emptyMap()).single().at)
        assertTrue(ReminderPlan.stillDue(state(), step, t("2026-03-04T12:00:00Z")))
        assertFalse(ReminderPlan.stillDue(state(), step, t("2026-03-05T12:00:00Z")), "Thursday wants no session")
    }

    @Test
    fun aLoggedSession_movesTheReminderToTheNextSessionDay() {
        val q = practice()
        clock = t("2026-03-04T07:00:00Z")
        add(Events.stepCompleted(StepCompleted(state().openStep(q)!!.id, local = LocalStamp.of(clock, utc))))
        val next = state().openStep(q)!!
        assertEquals(t("2026-03-06T07:30:00Z"), ReminderPlan.compute(state(), quiet, clock, utc, emptyMap()).single { it is Planned.StepReminder }.at)
        assertFalse(ReminderPlan.stillDue(state(), next.id, t("2026-03-04T08:00:00Z")), "today's session is done")
    }

    @Test
    fun snoozeMovesItLater_andANonTrackedPracticeIsSilent() {
        val q = practice()
        val step = state().openStep(q)!!.id
        add(Events.stepSnoozed(StepSnoozed(step, t("2026-03-04T09:00:00Z"))))
        assertEquals(t("2026-03-04T09:00:00Z"), ReminderPlan.practiceReminder(state(), step, clock, utc, firedAt = t("2026-03-04T07:30:00Z")))
        add(Events.questStatusSet(QuestStatusSet(q, QuestStatus.LATER)))
        assertNull(ReminderPlan.practiceReminder(state(), step, clock, utc, null))
        assertTrue(ReminderPlan.compute(state(), quiet, clock, utc, emptyMap()).isEmpty())
    }

    @Test
    fun aTimeCue_isTodayOrTomorrow() {
        val now = t("2026-03-04T10:00:00Z")
        assertEquals(t("2026-03-04T14:30:00Z"), WhenWhere.dueAt(now, 14 * 60 + 30, utc))
        assertEquals(t("2026-03-05T09:00:00Z"), WhenWhere.dueAt(now, 9 * 60, utc))
    }
}
