// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.dreamweave.quest.MainActivity
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.data.AppSettings
import kotlinx.datetime.TimeZone

/**
 * Arms the [ReminderPlan] with AlarmManager. Called after every state or settings
 * change, on boot, time/zone change and app update — so it is idempotent: it cancels
 * what is no longer planned and (re)arms everything else.
 */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** Exact alarms need a user grant on Android 12+ (13+ for new installs). */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun firedAt(): Map<String, Long> =
        prefs.all.filterKeys { it.startsWith("fired:") }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix("fired:") to it } }.toMap()

    fun markFired(stepId: String, at: Long) {
        prefs.edit().putLong("fired:$stepId", at).apply()
    }

    @Synchronized
    fun reschedule(state: QuestState, settings: AppSettings, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.currentSystemDefault()) {
        val plan = ReminderPlan.compute(state, settings, now, zone, firedAt())
        val keys = plan.map { it.key }.toSet()
        // Cancel what is no longer planned.
        val previous = prefs.getStringSet(KEY_ARMED, emptySet()).orEmpty()
        for (old in previous - keys) cancel(old)
        // Forget fired markers of steps that no longer exist or are done.
        val stale = prefs.all.keys.filter { it.startsWith("fired:") && state.steps[it.removePrefix("fired:")]?.isDone != false }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        for (p in plan) arm(p)
        prefs.edit().putStringSet(KEY_ARMED, keys).apply()
    }

    private fun intentFor(key: String): Intent {
        val (action, stepId) = when {
            key == "digest" -> ReminderReceiver.ACTION_FIRE_DIGEST to null
            key == "guard" -> ReminderReceiver.ACTION_FIRE_GUARD to null
            else -> ReminderReceiver.ACTION_FIRE_STEP to key.removePrefix("step:")
        }
        return Intent(context, ReminderReceiver::class.java).setAction(action).apply {
            if (stepId != null) putExtra(ReminderReceiver.EXTRA_STEP_ID, stepId)
        }
    }

    private fun requestCode(key: String) = key.hashCode() and 0x7FFFFFFF

    private fun pending(key: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, requestCode(key), intentFor(key), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun cancel(key: String) {
        pending(key, PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    private fun arm(p: Planned) {
        val pi = pending(p.key, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exact = canScheduleExact()
        try {
            when {
                p is Planned.StepReminder && p.alarm && exact -> {
                    // Shows the alarm icon in the status bar and survives Doze.
                    val show = PendingIntent.getActivity(
                        context, requestCode(p.key),
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    alarms.setAlarmClock(AlarmManager.AlarmClockInfo(p.at, show), pi)
                }
                exact -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
                // Fallback without the exact-alarm grant: may arrive a few minutes late (explained in Settings).
                else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
        }
    }

    companion object {
        private const val KEY_ARMED = "armed"
    }
}
