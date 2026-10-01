// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.app.AlarmManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Fold
import dev.dreamweave.quest.data.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * On a real device/emulator: a step alarm is armed with the system AlarmManager,
 * disappears when alarms are wiped (as on reboot), and BOOT_COMPLETED re-arms it.
 * A physical reboot is in docs/DEVICE_TEST.md.
 */
@RunWith(AndroidJUnit4::class)
class AlarmRebootTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as QuestApp
    private val alarms get() = app.getSystemService(AlarmManager::class.java)

    @Before
    fun grantExactAlarms() {
        instrumentation.uiAutomation.executeShellCommand("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow").close()
    }

    private fun waitFor(timeoutMs: Long = 10_000, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }

    @Test
    fun stepAlarmIsReArmedAfterReboot() = runBlocking {
        val c = app.container
        val due = (System.currentTimeMillis() / 60_000 + 7 * 24 * 60) * 60_000 // a week ahead, whole minute
        c.actions.capture("Instrumented alarm")
        val q = c.repository.current().quests.values.first { it.title == "Instrumented alarm" }.id
        c.actions.setStep(q, "Ring", dueAt = due, alarm = true)
        c.presence.refresh()
        assertEquals(true, waitFor { alarms.nextAlarmClock?.triggerTime?.let { it <= due } == true })

        // Wipe our alarms the way a reboot does (schedule an empty plan).
        c.scheduler.reschedule(Fold.of(emptyList(), TimeZone.UTC), AppSettings(digestEnabled = false, streakGuardEnabled = false))
        assertNotEquals(due, alarms.nextAlarmClock?.triggerTime)

        SystemEventsReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(true, waitFor { alarms.nextAlarmClock?.triggerTime == due }, "alarm not re-armed after boot")
    }
}
