// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.reset

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.dreamweave.quest.MainActivity
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Breathing
import dev.dreamweave.quest.core.FocusSession
import dev.dreamweave.quest.presence.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A running Breathe session: when it started and how long it lasts. */
data class BreatheRun(val startedAt: Long, val minutes: Int)

/**
 * Breathe (v1.1, C1): guided cyclic sighing for 1, 3 or 5 minutes. The screen shows a slow
 * expanding/contracting shape and a text count; a foreground service with a wake lock plays
 * a haptic cue at every phase, so it works with the screen off. No XP: rewards stay tied to
 * work (D-1116).
 */
class BreatheController(private val context: Context) {
    private val _run = MutableStateFlow<BreatheRun?>(null)
    val run: StateFlow<BreatheRun?> = _run.asStateFlow()

    fun start(minutes: Int) {
        _run.value = BreatheRun(System.currentTimeMillis(), minutes)
        runCatching { ContextCompat.startForegroundService(context, Intent(context, BreatheService::class.java)) }
            .onFailure { Log.w(TAG, "breathe service not started; the screen still guides", it) }
    }

    fun stop() {
        _run.value = null
    }

    /** Called by the service when the session ran to its end. */
    internal fun finished(run: BreatheRun) {
        if (_run.value == run) _run.value = null
    }

    companion object {
        internal const val TAG = "QuestBreathe"
    }
}

class BreatheService : Service() {
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as QuestApp).container
        val run = c.breathe.run.value
        try {
            // Foreground first, always (required after startForegroundService), then stop if it's gone.
            ServiceCompat.startForeground(
                this, ID, notification(run ?: BreatheRun(System.currentTimeMillis(), 1)),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
            if (run == null) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.w(BreatheController.TAG, "breathe notification not shown", e)
            stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        releaseWakeLock()
        // Keeps the CPU awake for the session (and no longer), so the haptics keep time with the screen off.
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "quest:breathe")
            ?.apply { acquire(run.minutes * 60_000L + 10_000L) }
        val loop = c.appScope.launch {
            val haptics = Haptics(this@BreatheService)
            for ((at, phase) in Breathing.phaseStarts(run.minutes)) {
                val wait = run.startedAt + at - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                if (c.breathe.run.value != run) break
                haptics.play(phase)
            }
            val end = run.startedAt + run.minutes * 60_000L - System.currentTimeMillis()
            if (end > 0) delay(end)
            if (c.breathe.run.value == run) {
                haptics.done()
                c.breathe.finished(run)
            }
        }
        job = loop
        // Stopped early (or replaced by a new session): this loop ends at once.
        val watcher = c.appScope.launch {
            c.breathe.run.first { it != run }
            loop.cancel()
        }
        loop.invokeOnCompletion {
            watcher.cancel()
            if (job === loop) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(run: BreatheRun): Notification =
        NotificationCompat.Builder(this, Notifications.CH_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_quest)
            .setContentTitle(getString(R.string.breathe_title))
            .setContentText(getString(R.string.breathe_notification, FocusSession.format(run.minutes * 60_000L)))
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(run.startedAt + run.minutes * 60_000L).setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 60,
                    Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_BREATHE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ID = 42
    }
}

/** One haptic cue per breathing phase: a tap to breathe in, two light taps to top up, a long soft hum out. */
internal class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val amplitude = vibrator?.hasAmplitudeControl() == true

    fun play(phase: Breathing.Phase) {
        val effect = when (phase) {
            Breathing.Phase.INHALE -> VibrationEffect.createOneShot(60, if (amplitude) 140 else VibrationEffect.DEFAULT_AMPLITUDE)
            Breathing.Phase.TOP_UP -> VibrationEffect.createWaveform(longArrayOf(0, 35, 90, 35), if (amplitude) intArrayOf(0, 120, 0, 120) else intArrayOf(0, 255, 0, 255), -1)
            Breathing.Phase.EXHALE -> if (amplitude) VibrationEffect.createOneShot(900, 35) else VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrate(effect)
    }

    fun done() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 120, 80), -1))

    private fun vibrate(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            // Alarm usage: the user started this, and it must keep playing with the screen off.
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
    }
}
