// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.focus

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.dreamweave.quest.QuestApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a running focus block in the shade (time left, Pause, Stop) while the app is in the
 * background. The countdown itself is a chronometer in the notification; the block's end is
 * an exact alarm ([FocusReceiver]), so neither needs this process to stay awake.
 */
class FocusService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as QuestApp).container
        val s = c.focus.session.value
        if (s == null || s.ended) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, FocusNotifications.ID_RUNNING, FocusNotifications.running(this, s),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // Not allowed to run in the foreground right now: the end alarm still fires.
            Log.w(FocusTimer.TAG, "focus notification not shown", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = c.appScope.launch {
                c.focus.session.collectLatest { cur ->
                    if (cur == null || cur.ended) {
                        ServiceCompat.stopForeground(this@FocusService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    FocusNotifications.updateRunning(this@FocusService, cur)
                    val end = cur.endsAt ?: return@collectLatest // paused: wait for the next change
                    delay((end - System.currentTimeMillis()).coerceAtLeast(0))
                    // Outside collectLatest: ending changes the session, which would cancel this block.
                    c.appScope.launch { c.focus.checkEnd() }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    companion object {
        /** Starts (or refreshes) the service. Quietly does nothing when Android refuses. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, FocusService::class.java)) }
                .onFailure { Log.w(FocusTimer.TAG, "focus service not started", it) }
        }
    }
}

/** The end alarm and the notification's buttons. */
class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as QuestApp).container
        val pending: PendingResult? = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_END -> c.focus.checkEnd()
                    ACTION_PAUSE -> c.focus.pause()
                    ACTION_RESUME -> c.focus.resume()
                    ACTION_STOP -> c.focus.stop()
                    ACTION_KEEP_GOING -> c.focus.keepGoing()
                    ACTION_NOT_YET -> c.focus.answered()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(FocusTimer.TAG, "focus action ${intent.action} didn't complete", e)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_END = "dev.dreamweave.quest.focus.END"
        const val ACTION_PAUSE = "dev.dreamweave.quest.focus.PAUSE"
        const val ACTION_RESUME = "dev.dreamweave.quest.focus.RESUME"
        const val ACTION_STOP = "dev.dreamweave.quest.focus.STOP"
        const val ACTION_KEEP_GOING = "dev.dreamweave.quest.focus.KEEP_GOING"
        const val ACTION_NOT_YET = "dev.dreamweave.quest.focus.NOT_YET"
    }
}
