// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.dreamweave.quest.AppContainer
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Views
import dev.dreamweave.quest.data.CommitResult
import dev.dreamweave.quest.data.QuestActions
import kotlinx.coroutines.CancellationException
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
        // Null only when onReceive is called directly (tests), not by the system.
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        c.appScope.launch {
            try {
                handle(context, c, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed reminder must never take the whole app down.
                Log.e(TAG, "reminder ${intent.action} failed", e)
            } finally {
                pending?.finish()
            }
        }
    }

    /** The work behind [onReceive]; internal so tests can run it synchronously. */
    internal suspend fun handle(context: Context, c: AppContainer, intent: Intent) {
        val stepId = intent.getStringExtra(EXTRA_STEP_ID)
        val state = c.repository.current()
        val now = System.currentTimeMillis()
        val zone = c.repository.zone
        when (intent.action) {
            ACTION_FIRE_DIGEST -> {
                val today = Views.today(state, now, zone, c.settings.current().stuckDays)
                // Nothing tracked: stay quiet (re-entry without guilt). The alarm still re-arms.
                if (today.cards.isNotEmpty()) Notifications.digest(context, today.cards, today.streak.current)
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
                // Re-check everything: the step may have been done, replaced, dismissed or
                // snoozed (here or on another device) since the alarm was armed.
                if (ReminderPlan.stillDue(state, stepId, now)) {
                    val step = state.steps.getValue(stepId)
                    val quest = state.quests.getValue(step.questId)
                    Notifications.stepReminder(context, quest, step, asAlarm = step.alarm)
                    // A practice's reminder is marked with when it fired, so tomorrow's is next.
                    c.scheduler.markFired(step.id, if (quest.isPractice) now else ReminderPlan.reminderTime(step) ?: now)
                } else if (state.steps[stepId]?.let { state.quests[it.questId]?.isPractice } == true) {
                    c.scheduler.markFired(stepId, now)
                }
            }
            // A stale notification (its step was done or replaced elsewhere) is only removed:
            // Done must never complete — and reward — a step that is no longer current.
            ACTION_DONE -> if (stepId != null) {
                Notifications.cancelStep(context, stepId)
                if (ReminderPlan.isOpenStep(state, stepId)) {
                    val text = state.steps.getValue(stepId).text
                    val r = c.actions.completeStepFromNotification(stepId)
                    // Undo stays available for 5 minutes, from a silent confirmation (B2).
                    QuestActions.completionOf(r)?.let { Notifications.undoConfirmation(context, it, stepId, text) }
                }
            }
            ACTION_UNDO -> intent.getStringExtra(EXTRA_EVENT_ID)?.let { eventId ->
                Notifications.cancelUndo(context, eventId)
                // Refused (it changed since): say so calmly instead of just vanishing.
                if (c.actions.undo(eventId) is CommitResult.Rejected) Notifications.undoRefused(context, eventId)
            }
            ACTION_SNOOZE -> if (stepId != null) {
                Notifications.cancelStep(context, stepId)
                if (ReminderPlan.isOpenStep(state, stepId)) c.actions.snooze(stepId, now + SNOOZE_MS)
            }
            ACTION_SKIP -> if (stepId != null) {
                Notifications.cancelStep(context, stepId)
                // Skipping a practice's reminder skips today's only (it already counts as fired);
                // dismissing the step would silence every session after it.
                val practice = state.steps[stepId]?.let { state.quests[it.questId]?.isPractice } == true
                if (!practice && ReminderPlan.isOpenStep(state, stepId)) c.actions.dismissReminder(stepId)
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
        const val ACTION_UNDO = "dev.dreamweave.quest.reminder.UNDO"
        const val EXTRA_EVENT_ID = "dev.dreamweave.quest.extra.EVENT_ID"
        const val EXTRA_STEP_ID = "dev.dreamweave.quest.extra.STEP_ID"
        const val SNOOZE_MS = 60 * 60 * 1000L
        internal const val TAG = "QuestReminders"
    }
}

/** Boot, clock/time-zone change, app update, exact-alarm grant: re-arm everything. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as QuestApp).container
        // Null only when onReceive is called directly (tests), not by the system.
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        c.appScope.launch {
            try {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) c.repository.refold()
                c.presence.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(ReminderReceiver.TAG, "re-arming after ${intent.action} failed", e)
            } finally {
                pending?.finish()
            }
        }
    }
}
