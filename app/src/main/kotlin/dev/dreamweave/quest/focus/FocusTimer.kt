// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.FocusSession
import dev.dreamweave.quest.data.CommitResult
import dev.dreamweave.quest.data.QuestActions
import dev.dreamweave.quest.util.Calm
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The focus timer (v1.1, B3). One block at a time, kept on disk so it survives the app
 * going to the background and process death. A foreground service shows it in the shade;
 * an exact alarm marks its end even in Doze. When the block ends, its effort XP is written
 * at once (`focus.block_completed`), whatever the answer to "Did you finish the step?".
 * Stopping early writes nothing (D-1113).
 */
class FocusTimer(
    private val context: Context,
    private val actions: QuestActions,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _session = MutableStateFlow(read())

    /** The running, paused or just-ended block; null when there is none. */
    val session: StateFlow<FocusSession?> = _session.asStateFlow()

    private val _endXp = MutableStateFlow<Int?>(prefs.getInt(KEY_END_XP, -1).takeIf { it >= 0 })

    /** Effort XP the last ended block earned (0 when today's allowance was used up). */
    val endXp: StateFlow<Int?> = _endXp.asStateFlow()

    private fun read(): FocusSession? = prefs.getString(KEY, null)?.let {
        runCatching { CoreJson.decodeFromString(FocusSession.serializer(), it) }.getOrNull()
    }

    @Synchronized
    private fun write(s: FocusSession?) {
        prefs.edit().apply { if (s == null) remove(KEY) else putString(KEY, CoreJson.encodeToString(FocusSession.serializer(), s)) }.apply()
        _session.value = s
    }

    private fun setEndXp(xp: Int?) {
        prefs.edit().apply { if (xp == null) remove(KEY_END_XP) else putInt(KEY_END_XP, xp) }.apply()
        _endXp.value = xp
    }

    /** Starts a block on a step. False when another block is still running or paused. */
    fun start(questId: String, stepId: String, stepText: String, minutes: Int): Boolean {
        val current = _session.value
        if (current != null && !current.ended) return false
        val s = FocusSession.start(questId, stepId, stepText, minutes, clock())
        setEndXp(null)
        write(s)
        arm(s)
        FocusNotifications.cancelEnded(context)
        FocusService.start(context)
        return true
    }

    fun pause() {
        val s = _session.value ?: return
        write(s.pause(clock()))
        disarm()
    }

    fun resume() {
        val s = _session.value ?: return
        if (!s.paused) return
        val r = s.resume(clock())
        write(r)
        arm(r)
        FocusService.start(context)
    }

    /** Stops early: nothing is credited, nothing negative is said. */
    fun stop() {
        write(null)
        setEndXp(null)
        disarm()
        FocusNotifications.cancelEnded(context)
    }

    /**
     * Ends the block if its time has come (called by the end alarm, the service and the app
     * when it opens). Writes the effort XP once, then chimes. Returns true when it ended now.
     */
    suspend fun checkEnd(): Boolean = withContext(NonCancellable) { endIfDue() }

    private suspend fun endIfDue(): Boolean = mutex.withLock {
        val s = _session.value ?: return@withLock false
        if (!s.isOver(clock())) return@withLock false
        val ended = s.end()
        write(ended)
        disarm()
        val r = Calm.attempt(TAG, "crediting a focus block") { actions.focusBlockDone(s.questId, s.stepId, s.minutes) }
        val xp = (r as? CommitResult.Ok)?.let { (it.change.after.xp - it.change.before.xp).toInt() } ?: 0
        setEndXp(xp)
        runCatching { FocusNotifications.ended(context, ended) }
        true
    }

    /** "Done" or "Not yet": the block is over and answered. */
    fun answered() {
        write(null)
        setEndXp(null)
        FocusNotifications.cancelEnded(context)
    }

    /** "Keep going": another block of the same length on the same step. */
    fun keepGoing(): Boolean {
        val s = _session.value ?: return false
        write(null)
        return start(s.questId, s.stepId, s.stepText, s.minutes)
    }

    /** After a restart: re-arm a running block, or end it if its time passed meanwhile. */
    suspend fun restore() {
        val s = _session.value ?: return
        if (s.isOver(clock())) checkEnd() else if (s.endsAt != null) arm(s)
    }

    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private fun endIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, FocusReceiver::class.java).setAction(FocusReceiver.ACTION_END),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(s: FocusSession) {
        val at = s.endsAt ?: return
        val pi = endIntent()
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun disarm() {
        alarms.cancel(endIntent())
    }

    companion object {
        private const val KEY = "session"
        private const val KEY_END_XP = "end_xp"
        internal const val TAG = "QuestFocus"
    }
}
