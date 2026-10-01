// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
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

    const val ID_DIGEST = 1
    const val ID_GUARD = 2

    fun stepNotificationId(stepId: String) = 1_000 + (stepId.hashCode() and 0x0FFFFFFF)

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

    private fun openApp(context: Context, requestCode: Int, questId: String? = null, action: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (questId != null) putExtra(MainActivity.EXTRA_QUEST_ID, questId)
            if (action != null) this.action = action
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call: nothing to do.
        }
    }

    fun digest(context: Context, cards: List<TodayCard>, streak: Int) {
        val title = if (cards.isEmpty()) "Nothing tracked today" else "Today: ${cards.size} quest${if (cards.size == 1) "" else "s"}"
        val lines = cards.map { c -> "${c.quest.title} — ${c.step?.text ?: "needs a next step"}" }
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

    /** A due step with Done / Snooze 1h / Skip. As an alarm it also gets a full-screen intent. */
    fun stepReminder(context: Context, quest: Quest, step: Step, asAlarm: Boolean) {
        val id = stepNotificationId(step.id)
        fun action(kind: String, rc: Int) = PendingIntent.getBroadcast(
            context, id * 4 + rc,
            Intent(context, ReminderReceiver::class.java).setAction(kind).putExtra(ReminderReceiver.EXTRA_STEP_ID, step.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, if (asAlarm) CH_ALARMS else CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_quest)
            .setContentTitle(step.text)
            .setContentText(quest.title)
            .setCategory(if (asAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context, id, questId = quest.id))
            .addAction(0, "Done", action(ReminderReceiver.ACTION_DONE, 1))
            .addAction(0, "Snooze 1h", action(ReminderReceiver.ACTION_SNOOZE, 2))
            .addAction(0, "Skip", action(ReminderReceiver.ACTION_SKIP, 3))
            .setDeleteIntent(action(ReminderReceiver.ACTION_SKIP, 3))
            .setAutoCancel(true)
        if (asAlarm) {
            builder.setOngoing(true)
            if (canUseFullScreen(context)) {
                val fullScreen = PendingIntent.getActivity(
                    context, id,
                    Intent(context, AlarmActivity::class.java)
                        .putExtra(ReminderReceiver.EXTRA_STEP_ID, step.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.setFullScreenIntent(fullScreen, true)
            }
        }
        post(context, id, builder)
    }

    fun cancelStep(context: Context, stepId: String) {
        NotificationManagerCompat.from(context).cancel(stepNotificationId(stepId))
    }
}
