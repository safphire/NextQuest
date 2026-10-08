// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Rhythm
import dev.dreamweave.quest.core.RhythmKind
import dev.dreamweave.quest.core.Xp
import dev.dreamweave.quest.focus.FocusTimer
import dev.dreamweave.quest.stopBackgroundWork
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UndoAndFocusRobolectricTest {
    private val app: QuestApp get() = ApplicationProvider.getApplicationContext()
    private val c get() = app.container
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun stopAppWork() = app.stopBackgroundWork()

    private suspend fun deliver(action: String, stepId: String? = null, eventId: String? = null) {
        val intent = Intent(app, ReminderReceiver::class.java).setAction(action)
        if (stepId != null) intent.putExtra(ReminderReceiver.EXTRA_STEP_ID, stepId)
        if (eventId != null) intent.putExtra(ReminderReceiver.EXTRA_EVENT_ID, eventId)
        ReminderReceiver().handle(app, c, intent)
    }

    private suspend fun tracked(title: String, step: String, rhythm: Rhythm? = null): Pair<String, String> {
        c.actions.capture(title, rhythm)
        val q = c.repository.current().quests.values.single { it.title == title }.id
        c.actions.track(q, firstStep = step)
        return q to c.repository.current().effectiveStep(q)!!.id
    }

    @Test
    fun notificationDone_postsASilentUndo_thatPutsEverythingBack(): Unit = runBlocking {
        val (q, step) = tracked("Run", "Shoes on")
        val before = c.repository.current()
        deliver(ReminderReceiver.ACTION_DONE, step)
        assertEquals(Xp.STEP.toLong(), c.repository.current().xp)
        val confirmation = notifications.activeNotifications.singleOrNull { it.id == Notifications.ID_UNDO }
        assertNotNull(confirmation, "a Done · Undo confirmation is posted")
        assertEquals(Notifications.UNDO_NOTIFICATION_MS, confirmation.notification.timeoutAfter)
        assertEquals("Undo", confirmation.notification.actions.single().title.toString())

        deliver(ReminderReceiver.ACTION_UNDO, eventId = confirmation.tag)
        val after = c.repository.current()
        assertEquals(before.xp, after.xp)
        assertEquals(before.counters, after.counters)
        assertEquals(step, after.effectiveStep(q)!!.id)
        assertTrue(notifications.activeNotifications.none { it.id == Notifications.ID_UNDO })
    }

    @Test
    fun practiceDone_fromANotification_neverLeavesItWithoutASession_andSkipSkipsOneDay(): Unit = runBlocking {
        val (q, step) = tracked("Sing", "Scales", Rhythm(RhythmKind.DAILY, reminderMinute = 7 * 60))
        deliver(ReminderReceiver.ACTION_SKIP, step)
        assertFalse(c.repository.current().steps.getValue(step).reminderDismissed, "Skip on a practice skips today only")
        deliver(ReminderReceiver.ACTION_DONE, step)
        val s = c.repository.current()
        assertFalse(s.needsNextStep(q))
        assertEquals("Scales", s.effectiveStep(q)!!.text)
    }

    @Test
    fun focusTimer_startPauseStop_creditsNothingEarly_andAnEndedBlockOnce(): Unit = runBlocking {
        val (q, step) = tracked("Write", "Draft intro")
        var now = 1_000_000L
        val timer = FocusTimer(app, c.actions) { now }
        assertTrue(timer.start(q, step, "Draft intro", 25))
        assertFalse(timer.start(q, step, "Draft intro", 10), "one block at a time")
        now += 5 * 60_000
        timer.pause()
        assertTrue(timer.session.value!!.paused)
        assertFalse(timer.checkEnd())
        timer.resume()
        timer.stop()
        assertNull(timer.session.value)
        assertEquals(0, c.repository.current().xp, "stopping early credits nothing")

        assertTrue(timer.start(q, step, "Draft intro", 10))
        now += 10 * 60_000
        assertTrue(timer.checkEnd())
        assertFalse(timer.checkEnd(), "ends once")
        assertEquals(Xp.FOCUS_BLOCK.toLong(), c.repository.current().xp)
        assertEquals(Xp.FOCUS_BLOCK, timer.endXp.value)
        assertTrue(timer.session.value!!.ended)
        // Keep going: another block of the same length.
        assertTrue(timer.keepGoing())
        assertEquals(10, timer.session.value!!.minutes)
        assertFalse(timer.session.value!!.ended)
        timer.stop()
        // A fresh timer reads the saved block back (process death).
        assertTrue(timer.start(q, step, "Draft intro", 50))
        val restored = FocusTimer(app, c.actions) { now }
        assertEquals(50, restored.session.value!!.minutes)
        restored.stop()
    }
}
