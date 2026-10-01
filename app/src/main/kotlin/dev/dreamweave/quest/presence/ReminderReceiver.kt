// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Views
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime

/**
 * Fires scheduled reminders and handles notification actions. Done from a notification
 * completes the step without opening the app; a tracked quest is then flagged
 * "Needs next step" (the one exception to principle 4).
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as QuestApp
        val c = app.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                handle(context, c, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, c: dev.dreamweave.quest.AppContainer, intent: Intent) {
        val stepId = intent.getStringExtra(EXTRA_STEP_ID)
        val state = c.repository.current()
        val now = System.currentTimeMillis()
        val zone = c.repository.zone
        when (intent.action) {
            ACTION_FIRE_DIGEST -> {
                val today = Views.today(state, now, zone)
                Notifications.digest(context, today.cards, today.streak.current)
            }
            ACTION_FIRE_GUARD -> {
                val day = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
                val stepToday = state.steps.values.any { it.doneDay == day }
                val today = Views.today(state, now, zone)
                if (!stepToday && today.cards.isNotEmpty()) {
                    val first = today.cards.firstOrNull { it.step != null }?.let { it.quest to it.step!! }
                    Notifications.streakGuard(context, today.streak.current, first)
                }
            }
            ACTION_FIRE_STEP -> if (stepId != null) {
                val step = state.steps[stepId]
                val quest = step?.let { state.quests[it.questId] }
                if (step != null && quest != null && !step.isDone && quest.openStepId == step.id) {
                    Notifications.stepReminder(context, quest, step, asAlarm = step.alarm)
                    c.scheduler.markFired(step.id, step.snoozedUntil?.takeIf { it > (step.dueAt ?: 0) } ?: step.dueAt ?: now)
                }
            }
            ACTION_DONE -> if (stepId != null) {
                Notifications.cancelStep(context, stepId)
                // A rejection means it was already done elsewhere: nothing to undo.
                c.actions.completeStepFromNotification(stepId)
            }
            ACTION_SNOOZE -> if (stepId != null) {
                Notifications.cancelStep(context, stepId)
                c.actions.snooze(stepId, now + SNOOZE_MS)
            }
            ACTION_SKIP -> if (stepId != null) {
                Notifications.cancelStep(context, stepId)
                c.actions.dismissReminder(stepId)
            }
        }
        // Digest and guard re-arm for tomorrow; step actions changed state (the change
        // listener reschedules too, this just covers the no-change cases).
        c.presence.refresh()
    }

    companion object {
        const val ACTION_FIRE_DIGEST = "dev.dreamweave.quest.reminder.DIGEST"
        const val ACTION_FIRE_GUARD = "dev.dreamweave.quest.reminder.GUARD"
        const val ACTION_FIRE_STEP = "dev.dreamweave.quest.reminder.STEP"
        const val ACTION_DONE = "dev.dreamweave.quest.reminder.DONE"
        const val ACTION_SNOOZE = "dev.dreamweave.quest.reminder.SNOOZE"
        const val ACTION_SKIP = "dev.dreamweave.quest.reminder.SKIP"
        const val EXTRA_STEP_ID = "dev.dreamweave.quest.extra.STEP_ID"
        const val SNOOZE_MS = 60 * 60 * 1000L
    }
}

/** Boot, clock/time-zone change, app update, exact-alarm grant: re-arm everything. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as QuestApp).container
        val pending = goAsync()
        c.appScope.launch {
            try {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) c.repository.refold()
                c.presence.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}
