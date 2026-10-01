// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.MainActivity
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.ui.ExternalRequest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PresenceRobolectricTest {
    private val app: QuestApp get() = ApplicationProvider.getApplicationContext()
    private val c get() = app.container
    private val alarms get() = app.getSystemService(AlarmManager::class.java)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun waitFor(timeoutMs: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (check()) return
            Thread.sleep(20)
        }
        assertTrue(check(), "condition not met in ${timeoutMs}ms")
    }

    @Test
    fun alarmsAreReArmedAfterReboot() = runBlocking {
        val due = System.currentTimeMillis() + 3 * 3_600_000
        c.actions.capture("Dentist")
        val q = c.repository.current().quests.values.single().id
        c.actions.setStep(q, "Call the dentist", dueAt = due, alarm = true)
        c.presence.refresh()
        assertEquals(due, alarms.nextAlarmClock?.triggerTime)

        // A reboot drops every alarm…
        ShadowAlarmManager.reset()
        assertEquals(null, alarms.nextAlarmClock)
        // …and BOOT_COMPLETED re-arms them, including the digest and streak guard.
        SystemEventsReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        waitFor { alarms.nextAlarmClock?.triggerTime == due }
        assertTrue(shadowOf(alarms).scheduledAlarms.size >= 3)
    }

    @Test
    fun notificationActions_done_snooze_skip() = runBlocking {
        c.actions.capture("Run")
        val q = c.repository.current().quests.values.single().id
        c.actions.track(q, firstStep = "Shoes on")
        val step = c.repository.current().effectiveStep(q)!!.id

        ReminderReceiver().onReceive(app, Intent(app, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SNOOZE).putExtra(ReminderReceiver.EXTRA_STEP_ID, step))
        waitFor { runBlocking { c.repository.current().steps.getValue(step).snoozedUntil != null } }

        ReminderReceiver().onReceive(app, Intent(app, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SKIP).putExtra(ReminderReceiver.EXTRA_STEP_ID, step))
        waitFor { runBlocking { c.repository.current().steps.getValue(step).reminderDismissed } }

        // Done works without opening the app and flags the quest "needs next step".
        ReminderReceiver().onReceive(app, Intent(app, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DONE).putExtra(ReminderReceiver.EXTRA_STEP_ID, step))
        waitFor { runBlocking { c.repository.current().steps.getValue(step).isDone } }
        val state = c.repository.current()
        assertTrue(state.needsNextStep(q))
        assertEquals(15, state.xp)
    }

    @Test
    fun shareSheet_opensCaptureWithTheSharedText() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Article")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/read-me")
        val req = MainActivity.parse(intent)
        assertIs<ExternalRequest.Capture>(req)
        assertEquals("Article — https://example.com/read-me", req.text)
        assertIs<ExternalRequest.Capture>(MainActivity.parse(Intent(MainActivity.ACTION_CAPTURE)))
        val pair = MainActivity.parse(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("dwquest://pair?server=https%3A%2F%2Fq.example&code=ABCD-1234")))
        assertIs<ExternalRequest.Pair>(pair)
        assertEquals("https://q.example", pair.serverUrl)
        assertNotNull(MainActivity.parse(Intent().putExtra(MainActivity.EXTRA_QUEST_ID, "x")))
    }

    @Suppress("unused")
    private val ctx: Context get() = app
}
