// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import dev.dreamweave.quest.core.Practices
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.Step
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.util.Format
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** One thing that should fire at [at]. Pure data, so the plan is unit-testable. */
sealed interface Planned {
    val at: Long
    val key: String

    data class Digest(override val at: Long) : Planned { override val key = "digest" }
    data class StreakGuard(override val at: Long) : Planned { override val key = "guard" }
    data class StepReminder(override val at: Long, val stepId: String, val alarm: Boolean) : Planned {
        override val key = "step:$stepId"
    }
}

/**
 * Computes every reminder that should be armed, from the state and settings alone.
 * [firedAt] holds the trigger time each step reminder last fired for, so a reminder
 * whose time passed while the phone was off fires once after boot — never repeatedly.
 */
object ReminderPlan {
    fun compute(state: QuestState, settings: AppSettings, now: Long, zone: TimeZone, firedAt: Map<String, Long>): List<Planned> {
        val out = mutableListOf<Planned>()
        if (settings.digestEnabled) out += Planned.Digest(nextDaily(now, settings.digestMinutes, zone))
        if (settings.streakGuardEnabled) out += Planned.StreakGuard(nextDaily(now, settings.streakGuardMinutes, zone))
        for (step in state.steps.values) {
            if (step.reminderDismissed || !isOpenStep(state, step.id)) continue
            val at = reminderTime(step) ?: continue
            if ((firedAt[step.id] ?: Long.MIN_VALUE) >= at) continue // already fired for this time
            out += Planned.StepReminder(maxOf(at, now), step.id, step.alarm)
        }
        for (q in state.tracked) {
            val step = state.openStep(q.id) ?: continue
            practiceReminder(state, step.id, now, zone, firedAt[step.id])?.let { out += Planned.StepReminder(it, step.id, alarm = false) }
        }
        return out.sortedBy { it.at }
    }

    /**
     * A tracked practice's next session reminder (v1.1): its reminder time on the next day the
     * rhythm wants a session, never for a day that already has one. A time that passed today
     * is not caught up (no nagging about a session not done yet); a snooze moves it later.
     */
    fun practiceReminder(state: QuestState, stepId: String, now: Long, zone: TimeZone, firedAt: Long?): Long? {
        val step = state.steps[stepId] ?: return null
        val quest = state.quests[step.questId] ?: return null
        val rhythm = quest.rhythm ?: return null
        if (quest.status != QuestStatus.TRACKED || !isOpenStep(state, stepId)) return null
        step.snoozedUntil?.takeIf { it > now && it > (firedAt ?: Long.MIN_VALUE) }?.let { return it }
        val minute = rhythm.reminderMinute ?: return null
        val days = Practices.sessionDays(state, quest.id)
        val today = Format.dateOf(now, zone)
        for (i in 0..14) {
            val day = today.plus(i, DateTimeUnit.DAY)
            if (!Practices.wantsSession(rhythm, days, day)) continue
            val at = Format.epochOf(day, minute, zone)
            if (at > now && at > (firedAt ?: Long.MIN_VALUE)) return at
        }
        return null
    }

    /**
     * True while [stepId] is still its quest's open step: it exists, is not done or
     * replaced, and its quest is open. Notification, alarm and widget actions check this
     * before committing, so a stale reminder never completes a superseded step.
     */
    fun isOpenStep(state: QuestState, stepId: String): Boolean {
        val step = state.steps[stepId] ?: return false
        if (step.isDone || step.superseded) return false
        val quest = state.quests[step.questId] ?: return false
        return quest.status.isOpen && quest.openStepId == step.id
    }

    /** When the step's reminder is due: the snooze time if it moves the reminder later, else the due time. */
    fun reminderTime(step: Step): Long? {
        val due = step.dueAt ?: return null
        return step.snoozedUntil?.takeIf { it > due } ?: due
    }

    /**
     * Whether a posted reminder for [stepId] should still be showing at [now]: the step is
     * still open, its reminder was not dismissed, and it is not snoozed or moved to later.
     */
    fun stillDue(state: QuestState, stepId: String, now: Long): Boolean {
        if (!isOpenStep(state, stepId)) return false
        val step = state.steps.getValue(stepId)
        if (step.reminderDismissed) return false
        val quest = state.quests[step.questId]
        if (quest?.rhythm != null && step.dueAt == null) {
            // A practice session: due while today wants one and it isn't snoozed past now.
            val today = Format.dateOf(now, state.timeZone)
            val due = Practices.status(state, quest, today)?.dueToday == true
            return due && quest.status == QuestStatus.TRACKED && (step.snoozedUntil ?: Long.MIN_VALUE) <= now
        }
        val at = reminderTime(step) ?: return false
        return at <= now
    }

    /** Today at [minutesOfDay] if still ahead, otherwise tomorrow. */
    fun nextDaily(now: Long, minutesOfDay: Int, zone: TimeZone): Long {
        val today = Format.epochOf(Format.dateOf(now, zone), minutesOfDay, zone)
        return if (today > now) today else Format.tomorrowAt(now, minutesOfDay, zone)
    }
}
