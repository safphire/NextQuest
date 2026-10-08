// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Fold
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.PendingEvent
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.StepCompleted
import dev.dreamweave.quest.core.StepReminderDismissed
import dev.dreamweave.quest.core.StepSet
import dev.dreamweave.quest.core.StepSnoozed
import dev.dreamweave.quest.data.AppSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderPlanTest {
    private val utc = TimeZone.UTC
    private fun t(iso: String) = Instant.parse(iso).toEpochMilliseconds()
    private var clock = t("2026-03-04T10:00:00Z")
    private val factory = EventFactory(Ids.newId(), clock = { clock })
    private val log = mutableListOf<dev.dreamweave.quest.core.Event>()
    private fun add(vararg p: PendingEvent) { log += p.map { factory.create(it.type, it.payload) } }
    private fun state(): QuestState = Fold.of(log, utc)

    @Test
    fun digestAndGuard_nextOccurrence() {
        val plan = ReminderPlan.compute(state(), AppSettings(), clock, utc, emptyMap())
        assertEquals(t("2026-03-05T08:00:00Z"), plan.single { it is Planned.Digest }.at) // 08:00 already passed today
        assertEquals(t("2026-03-04T21:00:00Z"), plan.single { it is Planned.StreakGuard }.at)
        val off = ReminderPlan.compute(state(), AppSettings(digestEnabled = false, streakGuardEnabled = false), clock, utc, emptyMap())
        assertTrue(off.isEmpty())
    }

    @Test
    fun stepReminders_followDueSnoozeDismissAndDone() {
        val q = Ids.newId(clock)
        val s1 = Ids.newId(clock)
        add(Events.questCreated(QuestCreated(q, "Q")), Events.stepSet(StepSet(s1, q, "call", dueAt = t("2026-03-04T15:00:00Z"), alarm = true)))
        val settings = AppSettings(digestEnabled = false, streakGuardEnabled = false)
        val plan = ReminderPlan.compute(state(), settings, clock, utc, emptyMap())
        assertEquals(listOf(Planned.StepReminder(t("2026-03-04T15:00:00Z"), s1, alarm = true)), plan)

        // Snoozed: fires at the snooze time.
        add(Events.stepSnoozed(StepSnoozed(s1, t("2026-03-04T16:00:00Z"))))
        assertEquals(t("2026-03-04T16:00:00Z"), ReminderPlan.compute(state(), settings, clock, utc, emptyMap()).single().at)

        // Missed while the phone was off: fires once, right away…
        clock = t("2026-03-04T18:00:00Z")
        assertEquals(clock, ReminderPlan.compute(state(), settings, clock, utc, emptyMap()).single().at)
        // …and not again once it fired for that time.
        assertTrue(ReminderPlan.compute(state(), settings, clock, utc, mapOf(s1 to t("2026-03-04T16:00:00Z"))).isEmpty())

        // Skip dismisses it; a new date re-arms it.
        add(Events.stepReminderDismissed(StepReminderDismissed(s1)))
        assertTrue(ReminderPlan.compute(state(), settings, clock, utc, emptyMap()).isEmpty())
        add(Events.stepEdited(dev.dreamweave.quest.core.StepEdited(s1, dueAt = t("2026-03-05T09:00:00Z"))))
        assertEquals(t("2026-03-05T09:00:00Z"), ReminderPlan.compute(state(), settings, clock, utc, emptyMap()).single().at)

        // Done steps and replaced steps never fire.
        add(Events.stepCompleted(StepCompleted(s1)))
        assertTrue(ReminderPlan.compute(state(), settings, clock, utc, emptyMap()).isEmpty())
        val s2 = Ids.newId(clock)
        add(Events.stepSet(StepSet(s2, q, "old", dueAt = t("2026-03-06T09:00:00Z"))))
        add(Events.stepSet(StepSet(Ids.newId(clock + 1), q, "new")))
        assertTrue(ReminderPlan.compute(state(), settings, clock, utc, emptyMap()).isEmpty())
    }

    @Test
    fun stillDue_followsTheOpenStep() {
        val q = Ids.newId(clock)
        val s1 = Ids.newId(clock)
        add(Events.questCreated(QuestCreated(q, "Q")), Events.stepSet(StepSet(s1, q, "call", dueAt = t("2026-03-04T09:00:00Z"))))
        assertTrue(ReminderPlan.isOpenStep(state(), s1))
        assertTrue(ReminderPlan.stillDue(state(), s1, clock))

        // Snoozed to later (maybe on another device): not showing until then.
        add(Events.stepSnoozed(StepSnoozed(s1, t("2026-03-04T11:00:00Z"))))
        assertFalse(ReminderPlan.stillDue(state(), s1, clock))
        assertTrue(ReminderPlan.stillDue(state(), s1, t("2026-03-04T11:00:00Z")))

        // Skipped: still the open step (Done stays valid), but the reminder is gone.
        add(Events.stepReminderDismissed(StepReminderDismissed(s1)))
        assertFalse(ReminderPlan.stillDue(state(), s1, t("2026-03-04T12:00:00Z")))
        assertTrue(ReminderPlan.isOpenStep(state(), s1))

        // Replaced: the old step is no longer open; the new one has no reminder.
        val s2 = Ids.newId(clock + 1)
        add(Events.stepSet(StepSet(s2, q, "new")))
        assertFalse(ReminderPlan.isOpenStep(state(), s1))
        assertTrue(ReminderPlan.isOpenStep(state(), s2))
        assertFalse(ReminderPlan.stillDue(state(), s2, clock))

        // Done or unknown: never open.
        add(Events.stepCompleted(StepCompleted(s2)))
        assertFalse(ReminderPlan.isOpenStep(state(), s2))
        assertFalse(ReminderPlan.isOpenStep(state(), "missing"))
    }
}
