// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.focus

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.dreamweave.quest.MainActivity
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.FocusSession
import dev.dreamweave.quest.presence.Notifications

/** The focus timer's two notifications: the running block (silent) and its end (a gentle chime). */
object FocusNotifications {
    const val ID_RUNNING = 40
    const val ID_ENDED = 41

    private fun action(context: Context, kind: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, FocusReceiver::class.java).setAction(kind),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun open(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun running(context: Context, s: FocusSession): Notification {
        val now = System.currentTimeMillis()
        val b = NotificationCompat.Builder(context, Notifications.CH_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_quest)
            .setContentTitle(context.getString(R.string.focus_notification_running, s.stepText))
            .setContentIntent(open(context, MainActivity.ACTION_OPEN_FOCUS, 50))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (s.paused) {
            b.setContentText(context.getString(R.string.focus_paused, FocusSession.format(s.leftMs(now))))
                .addAction(0, context.getString(R.string.focus_resume), action(context, FocusReceiver.ACTION_RESUME, 51))
        } else {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(s.endsAt ?: now).setShowWhen(true)
                .setContentText(context.getString(R.string.focus_minutes, s.minutes))
                .addAction(0, context.getString(R.string.focus_pause), action(context, FocusReceiver.ACTION_PAUSE, 52))
        }
        b.addAction(0, context.getString(R.string.focus_stop), action(context, FocusReceiver.ACTION_STOP, 53))
        return b.build()
    }

    fun updateRunning(context: Context, s: FocusSession) {
        if (!Notifications.canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(ID_RUNNING, running(context, s))
        } catch (e: SecurityException) {
            // Permission revoked meanwhile: the timer runs on regardless.
        }
    }

    /** The block ran to its end: a chime, and "Did you finish the step?" with the three answers. */
    fun ended(context: Context, s: FocusSession) {
        NotificationManagerCompat.from(context).cancel(ID_RUNNING)
        if (!Notifications.canPost(context)) return
        val n = NotificationCompat.Builder(context, Notifications.CH_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_quest)
            .setContentTitle(context.getString(R.string.focus_notification_ended, s.stepText))
            .setContentText(context.getString(R.string.focus_end_question))
            .setContentIntent(open(context, MainActivity.ACTION_OPEN_FOCUS, 54))
            .addAction(0, context.getString(R.string.focus_end_done), open(context, MainActivity.ACTION_FOCUS_DONE, 55))
            .addAction(0, context.getString(R.string.focus_end_keep_going), action(context, FocusReceiver.ACTION_KEEP_GOING, 56))
            .addAction(0, context.getString(R.string.focus_end_not_yet), action(context, FocusReceiver.ACTION_NOT_YET, 57))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_ENDED, n)
        } catch (e: SecurityException) {
            // Nothing to do.
        }
    }

    fun cancelEnded(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_ENDED)
    }
}
