// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.util.Format
import kotlinx.datetime.TimeZone

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
            val due = step.dueAt ?: continue
            if (step.isDone || step.superseded || step.reminderDismissed) continue
            val quest = state.quests[step.questId] ?: continue
            if (!quest.status.isOpen || quest.openStepId != step.id) continue
            val at = step.snoozedUntil?.takeIf { it > due } ?: due
            if ((firedAt[step.id] ?: Long.MIN_VALUE) >= at) continue // already fired for this time
            out += Planned.StepReminder(maxOf(at, now), step.id, step.alarm)
        }
        return out.sortedBy { it.at }
    }

    /** Today at [minutesOfDay] if still ahead, otherwise tomorrow. */
    fun nextDaily(now: Long, minutesOfDay: Int, zone: TimeZone): Long {
        val today = Format.epochOf(Format.dateOf(now, zone), minutesOfDay, zone)
        return if (today > now) today else Format.tomorrowAt(now, minutesOfDay, zone)
    }
}
