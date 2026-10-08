// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dreamweave.quest.MainActivity
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Quest
import dev.dreamweave.quest.core.Step
import dev.dreamweave.quest.core.TodayCard

/** Notification channels and builders. Wording is calm: no "overdue", no red. */
object Notifications {
    const val CH_DIGEST = "digest"
    const val CH_STREAK = "streak"
    const val CH_REMINDERS = "reminders"
    const val CH_ALARMS = "alarms"
    const val CH_FOCUS = "focus"

    const val ID_DIGEST = 1
    const val ID_GUARD = 2

    /** Every step notification uses this id with the step id as its tag, so two steps never collide. */
    const val ID_STEP = 3

    /** "Done · Undo" after a notification Done, tagged with the completion's event id. */
    const val ID_UNDO = 4

    /** How long a notification Done can be undone from its confirmation. */
    const val UNDO_NOTIFICATION_MS = 5 * 60 * 1000L

    /**
     * Unique per step, set as the data of every step intent (alarm, notification actions,
     * content and full-screen intents) so their PendingIntents never merge
     * (`Intent.filterEquals` compares data, not extras or request codes).
     */
    fun stepUri(stepId: String): Uri = Uri.parse("quest-reminder://step/$stepId")

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_DIGEST, "Morning digest", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Your tracked quests and their next steps, once a day."
                },
                NotificationChannel(CH_STREAK, "Streak guard", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "An evening nudge, only on days without a completed step."
                },
                NotificationChannel(CH_REMINDERS, "Step reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a scheduled step is due."
                },
                NotificationChannel(CH_ALARMS, "Step alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alarms you turned on for specific steps."
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    enableVibration(true)
                },
                // The running timer is silent; the end of a block chimes with the default sound.
                NotificationChannel(CH_FOCUS, context.getString(R.string.focus_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.focus_channel_description)
                },
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Full-screen alarms need a special grant on Android 14+. */
    fun canUseFullScreen(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true

    private fun openApp(context: Context, requestCode: Int, questId: String? = null, action: String? = null, uri: Uri? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (questId != null) putExtra(MainActivity.EXTRA_QUEST_ID, questId)
            if (action != null) this.action = action
            if (uri != null) data = uri
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        post(context, null, id, builder.build())
    }

    private fun post(context: Context, tag: String?, id: Int, notification: Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call: nothing to do.
        }
    }

    /**
     * The morning digest: each tracked quest's one step (with its when/where cue), practices
     * only on days they want a session, and at most one gentle "Stuck?" mention.
     */
    fun digest(context: Context, cards: List<TodayCard>, streak: Int) {
        val title = if (cards.isEmpty()) "Nothing tracked today" else "Today: ${cards.size} quest${if (cards.size == 1) "" else "s"}"
        val lines = cards.filter { it.practice == null || it.practice!!.dueToday }.map { c ->
            val step = c.step?.let { s -> s.text + (s.cue?.let { " ($it)" } ?: "") } ?: "needs a next step"
            if (c.practice != null) "${c.quest.title} — session: $step" else "${c.quest.title} — $step"
        } + listOfNotNull(cards.firstOrNull { it.stuck }?.let { "Stuck? “${it.quest.title}” might just need a smaller step." })
        val style = NotificationCompat.InboxStyle().also { s -> lines.take(7).forEach { s.addLine(it) } }
        if (streak > 0) style.setSummaryText("Streak: $streak day${if (streak == 1) "" else "s"}")
        val capture = NotificationCompat.Action(0, "Capture", openApp(context, 11, action = MainActivity.ACTION_CAPTURE))
        post(
            context, ID_DIGEST,
            NotificationCompat.Builder(context, CH_DIGEST)
                .setSmallIcon(R.drawable.ic_stat_quest)
                .setContentTitle(title)
                .setContentText(lines.firstOrNull() ?: "Capture something, or track a quest from the Board.")
                .setStyle(style)
                .setContentIntent(openApp(context, 10))
                .addAction(capture)
                .setAutoCancel(true),
        )
    }

    fun streakGuard(context: Context, streak: Int, firstStep: Pair<Quest, Step>?) {
        val text = firstStep?.let { (q, s) -> "One small step keeps it going: ${s.text} (${q.title})." }
            ?: "One small step today keeps it going."
        post(
            context, ID_GUARD,
            NotificationCompat.Builder(context, CH_STREAK)
                .setSmallIcon(R.drawable.ic_stat_quest)
                .setContentTitle(if (streak > 0) "Your $streak-day streak" else "A step today?")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context, 20, questId = firstStep?.first?.id))
                .setAutoCancel(true),
        )
    }

    /**
     * A due step with Done / Snooze 1h / Skip. As an alarm it also gets a full-screen intent.
     * [silent]: posted without sound or vibration (the alarm screen was closed with Back).
     */
    private fun stepAction(context: Context, stepId: String, kind: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(kind).setData(stepUri(stepId)).putExtra(ReminderReceiver.EXTRA_STEP_ID, stepId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun stepReminder(context: Context, quest: Quest, step: Step, asAlarm: Boolean, silent: Boolean = false) {
        val uri = stepUri(step.id)
        fun action(kind: String) = stepAction(context, step.id, kind)
        val builder = NotificationCompat.Builder(context, if (asAlarm) CH_ALARMS else CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_quest)
            .setContentTitle(step.text)
            .setContentText(quest.title)
            .setCategory(if (asAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context, 30, questId = quest.id, uri = uri))
            .addAction(0, "Done", action(ReminderReceiver.ACTION_DONE))
            .addAction(0, "Snooze 1h", action(ReminderReceiver.ACTION_SNOOZE))
            .addAction(0, "Skip", action(ReminderReceiver.ACTION_SKIP))
            .setDeleteIntent(action(ReminderReceiver.ACTION_SKIP))
            .setAutoCancel(true)
            .setSilent(silent)
        if (asAlarm) {
            builder.setOngoing(true)
            if (canUseFullScreen(context)) {
                val fullScreen = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, AlarmActivity::class.java)
                        .setData(uri)
                        .putExtra(ReminderReceiver.EXTRA_STEP_ID, step.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.setFullScreenIntent(fullScreen, true)
            }
        }
        val notification = builder.build()
        // An alarm keeps sounding until the user acts on it (or the alarm screen closes it).
        if (asAlarm) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        post(context, step.id, ID_STEP, notification)
    }

    /**
     * Back on the full-screen alarm: the ringing stops, and a normal reminder without sound
     * stays in the shade with Done / Snooze / Skip, so the step isn't lost.
     */
    fun quietStep(context: Context, quest: Quest, step: Step) {
        cancelStep(context, step.id)
        stepReminder(context, quest, step, asAlarm = false, silent = true)
    }

    /**
     * [quietStep] when the quest log can't be read: the reminder is rebuilt from the title and
     * text of the notification that was ringing (or a plain one), silent, with its actions.
     */
    fun quietFromShown(context: Context, stepId: String) {
        val shown = runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == ID_STEP && it.tag == stepId }?.notification
        }.getOrNull()
        val title = shown?.extras?.getCharSequence(Notification.EXTRA_TITLE) ?: "A step is due"
        val text = shown?.extras?.getCharSequence(Notification.EXTRA_TEXT)
        cancelStep(context, stepId)
        val builder = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_quest)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context, 30, uri = stepUri(stepId)))
            .addAction(0, "Done", stepAction(context, stepId, ReminderReceiver.ACTION_DONE))
            .addAction(0, "Snooze 1h", stepAction(context, stepId, ReminderReceiver.ACTION_SNOOZE))
            .addAction(0, "Skip", stepAction(context, stepId, ReminderReceiver.ACTION_SKIP))
            .setDeleteIntent(stepAction(context, stepId, ReminderReceiver.ACTION_SKIP))
            .setAutoCancel(true)
            .setSilent(true)
        post(context, stepId, ID_STEP, builder.build())
    }

    /**
     * After Done from a notification: a silent "Done · step" with Undo, which goes away after
     * 5 minutes (B2).
     */
    fun undoConfirmation(context: Context, eventId: String, stepId: String, stepText: String) {
        val undo = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_UNDO)
                .setData(Uri.parse("quest-undo://event/$eventId"))
                .putExtra(ReminderReceiver.EXTRA_EVENT_ID, eventId)
                .putExtra(ReminderReceiver.EXTRA_STEP_ID, stepId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_quest)
            .setContentTitle(context.getString(R.string.undo_notification_title, stepText))
            .setContentText(context.getString(R.string.undo_notification_text))
            .addAction(0, context.getString(R.string.undo), undo)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(UNDO_NOTIFICATION_MS)
            .build()
        post(context, eventId, ID_UNDO, n)
    }

    fun cancelUndo(context: Context, eventId: String) {
        NotificationManagerCompat.from(context).cancel(eventId, ID_UNDO)
    }

    fun cancelStep(context: Context, stepId: String) {
        NotificationManagerCompat.from(context).cancel(stepId, ID_STEP)
    }

    /** Step ids whose reminder notification is currently showing. */
    fun postedStepIds(context: Context): Set<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == ID_STEP }
            .mapNotNull { it.tag }
            .toSet()

    /** Removes every posted step notification for which [keep] is false (step done, replaced, snoozed…). */
    fun cancelStaleSteps(context: Context, keep: (stepId: String) -> Boolean) {
        for (stepId in postedStepIds(context)) if (!keep(stepId)) cancelStep(context, stepId)
    }
}
