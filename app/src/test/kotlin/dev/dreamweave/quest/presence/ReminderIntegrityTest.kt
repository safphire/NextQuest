// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.QuestApp
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reminders stay tied to the step they were made for: distinct intents per step, stale
 * notifications taken down, fired reminders not re-armed. The receiver's work runs
 * synchronously through [ReminderReceiver.handle] so every assertion is deterministic.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderIntegrityTest {
    private val app: QuestApp get() = ApplicationProvider.getApplicationContext()
    private val c get() = app.container
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun deliver(action: String, stepId: String? = null) {
        val intent = Intent(app, ReminderReceiver::class.java).setAction(action)
        if (stepId != null) intent.putExtra(ReminderReceiver.EXTRA_STEP_ID, stepId)
        ReminderReceiver().handle(app, c, intent)
    }

    /** A tracked quest whose open step was due a minute ago; returns (questId, stepId). */
    private suspend fun dueStep(title: String, step: String): Pair<String, String> {
        c.actions.capture(title)
        val q = c.repository.current().quests.values.single { it.title == title }.id
        c.actions.track(q, firstStep = step)
        val stepId = c.repository.current().quests.getValue(q).openStepId!!
        c.actions.schedule(stepId, System.currentTimeMillis() - 60_000, alarm = false)
        return q to stepId
    }

    /** The data Uris of everything currently armed with AlarmManager. */
    private fun armedUris(): List<Uri> =
        shadowOf(alarms).scheduledAlarms.mapNotNull { alarm -> alarm.operation?.let { shadowOf(it).savedIntent.data } }

    @Test
    fun twoSteps_getDistinctNotificationsAndIntents(): Unit = runBlocking {
        val (qa, a) = dueStep("Run", "Shoes on")
        val (qb, b) = dueStep("Read", "Open the book")
        val s = c.repository.current()
        Notifications.stepReminder(app, s.quests.getValue(qa), s.steps.getValue(a), asAlarm = false)
        Notifications.stepReminder(app, s.quests.getValue(qb), s.steps.getValue(b), asAlarm = false)
        assertEquals(setOf(a, b), Notifications.postedStepIds(app))

        val byTag = notifications.activeNotifications.filter { it.id == Notifications.ID_STEP }.associateBy { it.tag }
        val doneA = byTag.getValue(a).notification.actions[0].actionIntent
        val doneB = byTag.getValue(b).notification.actions[0].actionIntent
        assertNotEquals(doneA, doneB)
        assertEquals(Notifications.stepUri(a), shadowOf(doneA).savedIntent.data)
        assertEquals(Notifications.stepUri(b), shadowOf(doneB).savedIntent.data)
        assertEquals(b, shadowOf(doneB).savedIntent.getStringExtra(ReminderReceiver.EXTRA_STEP_ID))

        // Both reminders are armed separately…
        c.presence.refresh()
        assertTrue(armedUris().containsAll(listOf(Notifications.stepUri(a), Notifications.stepUri(b))))
        // …and cancelling one notification leaves the other.
        Notifications.cancelStep(app, a)
        assertEquals(setOf(b), Notifications.postedStepIds(app))
    }

    @Test
    fun staleReminder_isTakenDown_andItsDoneGivesNoXp(): Unit = runBlocking {
        val (q, old) = dueStep("Run", "Shoes on")
        deliver(ReminderReceiver.ACTION_FIRE_STEP, old)
        assertEquals(setOf(old), Notifications.postedStepIds(app))

        // Replaced elsewhere: the next refresh takes the reminder down.
        c.actions.setStep(q, "Run 5k")
        c.presence.refresh()
        assertTrue(Notifications.postedStepIds(app).isEmpty())

        // Done on a stale copy only clears it: a superseded step earns nothing.
        val before = c.repository.current()
        Notifications.stepReminder(app, before.quests.getValue(q), before.steps.getValue(old), asAlarm = false)
        deliver(ReminderReceiver.ACTION_DONE, old)
        val after = c.repository.current()
        assertFalse(after.steps.getValue(old).isDone)
        assertEquals(before.xp, after.xp)
        assertTrue(Notifications.postedStepIds(app).isEmpty())
    }

    @Test
    fun dismissedReminder_doesNotFire(): Unit = runBlocking {
        val (_, step) = dueStep("Run", "Shoes on")
        c.actions.dismissReminder(step)
        deliver(ReminderReceiver.ACTION_FIRE_STEP, step)
        assertTrue(Notifications.postedStepIds(app).isEmpty())
    }

    @Test
    fun firedStepReminder_isNotReArmed(): Unit = runBlocking {
        val (_, step) = dueStep("Run", "Shoes on")
        c.presence.refresh()
        // Missed (due a minute ago): armed to fire right away.
        assertTrue(Notifications.stepUri(step) in armedUris())

        deliver(ReminderReceiver.ACTION_FIRE_STEP, step)
        assertTrue(step in c.scheduler.firedAt())
        c.presence.refresh()
        assertFalse(Notifications.stepUri(step) in armedUris())
        // The notification itself stays until the user acts on it.
        assertEquals(setOf(step), Notifications.postedStepIds(app))
    }

    @Test
    fun withoutExactGrant_alarmFallsBackToInexact(): Unit = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val (_, step) = dueStep("Dentist", "Call")
        c.actions.schedule(step, System.currentTimeMillis() + 3 * 3_600_000, alarm = true)
        c.presence.refresh()
        assertFalse(c.scheduler.canScheduleExact())
        assertNull(alarms.nextAlarmClock) // no setAlarmClock without the grant…
        assertTrue(Notifications.stepUri(step) in armedUris()) // …but still armed, inexactly
    }

    @Test
    fun digest_staysQuietWhenNothingIsTracked(): Unit = runBlocking {
        c.actions.capture("Someday maybe") // in the inbox, not tracked
        deliver(ReminderReceiver.ACTION_FIRE_DIGEST)
        assertTrue(notifications.activeNotifications.none { it.id == Notifications.ID_DIGEST })

        dueStep("Run", "Shoes on")
        deliver(ReminderReceiver.ACTION_FIRE_DIGEST)
        assertTrue(notifications.activeNotifications.any { it.id == Notifications.ID_DIGEST })
    }
}
