// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.stopBackgroundWork
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Back on the full-screen alarm stops the ringing and leaves a silent reminder with its actions. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlarmBackTest {
    private val app: QuestApp get() = ApplicationProvider.getApplicationContext()
    private val c get() = app.container
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun stopAppWork() = app.stopBackgroundWork()

    @Test
    fun back_silencesTheAlarm_andKeepsAQuietReminder(): Unit = runBlocking {
        c.actions.capture("Dentist")
        val q = c.repository.current().quests.values.single().id
        c.actions.track(q, firstStep = "Call the dentist")
        val stepId = c.repository.current().effectiveStep(q)!!.id
        c.actions.schedule(stepId, System.currentTimeMillis() - 60_000, alarm = true)
        val s = c.repository.current()
        Notifications.stepReminder(app, s.quests.getValue(q), s.steps.getValue(stepId), asAlarm = true)
        val ringing = notifications.activeNotifications.single { it.tag == stepId }.notification
        assertTrue(ringing.flags and Notification.FLAG_INSISTENT != 0)

        val activity = Robolectric.buildActivity(
            AlarmActivity::class.java,
            Intent(app, AlarmActivity::class.java).putExtra(ReminderReceiver.EXTRA_STEP_ID, stepId),
        ).setup().get()
        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(activity.isFinishing)
        val quiet = notifications.activeNotifications.singleOrNull { it.tag == stepId }?.notification
        assertNotNull(quiet, "a reminder stays in the shade")
        assertEquals(0, quiet.flags and Notification.FLAG_INSISTENT, "no longer ringing")
        assertEquals(Notifications.CH_REMINDERS, quiet.channelId)
        assertEquals(listOf("Done", "Snooze 1h", "Skip"), quiet.actions.map { it.title.toString() })
        // The step itself is untouched: still open, not snoozed or dismissed.
        val after = c.repository.current().steps.getValue(stepId)
        assertFalse(after.isDone)
        assertEquals(null, after.snoozedUntil)
        assertFalse(after.reminderDismissed)
    }
}
