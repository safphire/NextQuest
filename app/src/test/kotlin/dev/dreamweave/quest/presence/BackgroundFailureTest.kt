// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.NextStep
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.QuestStatusSet
import dev.dreamweave.quest.data.QuestActions
import dev.dreamweave.quest.data.QuestRepository
import dev.dreamweave.quest.data.db.EventDao
import dev.dreamweave.quest.data.db.EventEntity
import dev.dreamweave.quest.ui.settings.SyncStatus
import dev.dreamweave.quest.util.ActionOutcome
import dev.dreamweave.quest.util.Calm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The alarm screen, the widget's Done dialog and Settings' sync status run outside the
 * main screen's error handling. Injected storage failures must come back as calm messages,
 * never as a crash, and must leave the in-memory state untouched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackgroundFailureTest {
    private val now = 1_790_000_000_000L
    private val device = Ids.newId(now)

    /** A log that reads fine but can't be written to (disk full) and whose sync queue can't be counted. */
    private class BrokenDao(private val seed: List<EventEntity>) : EventDao {
        override suspend fun insertAll(events: List<EventEntity>): List<Long> = throw SQLiteFullException("database or disk is full")
        override suspend fun all(): List<EventEntity> = seed
        override suspend fun count(): Int = seed.size
        override suspend fun maxSeq(deviceId: String): Long? = seed.filter { it.deviceId == deviceId }.maxOfOrNull { it.seq }
        override suspend fun maxTs(): Long? = seed.maxOfOrNull { it.ts }
        override suspend fun existingIds(ids: List<String>): List<String> = seed.map { it.id }.filter { it in ids }
        override suspend fun unpushed(actor: String, afterId: String, limit: Int): List<EventEntity> = throw SQLiteFullException("broken")
        override suspend fun unpushedCount(actor: String): Int = throw SQLiteFullException("broken")
        override suspend fun markPushed(ids: List<String>) = throw SQLiteFullException("broken")
        override suspend fun markAllUnpushed() = throw SQLiteFullException("broken")
    }

    /** A tracked quest with one open step, already in the (read-only) log. */
    private fun seeded(): Triple<QuestActions, QuestRepository, String> {
        val f = EventFactory(device, clock = { now })
        val quest = Ids.newId(now)
        val step = Ids.newId(now)
        val events = f.createAll(
            listOf(
                Events.questCreated(QuestCreated(quest, "Run")),
                Events.questStatusSet(QuestStatusSet(quest, QuestStatus.TRACKED, NextStep(step, quest, "Shoes on"))),
            ),
        )
        val repo = QuestRepository(BrokenDao(events.map { EventEntity.from(it) }), device, { TimeZone.UTC }, { now + 60_000 }, Dispatchers.Unconfined)
        return Triple(QuestActions(repo) { now + 60_000 }, repo, step)
    }

    @Test
    fun alarmSnoozeDismissAndDone_failQuietly() = runBlocking {
        val (actions, repo, step) = seeded()
        val before = repo.current()

        val snooze = Calm.commit("test", "alarm Snooze") { actions.snooze(step, now + 3_600_000) }
        val dismiss = Calm.commit("test", "alarm Dismiss") { actions.dismissReminder(step) }
        val done = Calm.commit("test", "alarm Done") { actions.completeStep(step, "Run 1 km", finishOwner = false) }
        for (r in listOf(snooze, dismiss, done)) {
            assertIs<ActionOutcome.Failed>(r)
            assertEquals(Calm.MESSAGE, r.message)
        }
        // Nothing half-applied: memory still matches the (unchanged) log.
        val after = repo.current()
        assertEquals(before.xp, after.xp)
        assertNull(after.steps.getValue(step).doneAt)
        assertNull(after.steps.getValue(step).snoozedUntil)
    }

    @Test
    fun widgetDone_failsQuietly() = runBlocking {
        val (actions, repo, step) = seeded()
        val r = Calm.commit("test", "widget Done") { actions.completeStepFromWidget(step, "Stretch", finishOwner = false) }
        assertIs<ActionOutcome.Failed>(r)
        assertTrue(repo.current().steps.getValue(step).doneAt == null)
    }

    @Test
    fun refusalsStayRefusals_notFailures() = runBlocking {
        val (actions, _, _) = seeded()
        val r = Calm.commit("test", "alarm Done") { actions.completeStep(Ids.newId(now), null, finishOwner = false) }
        assertIs<ActionOutcome.Refused>(r)
    }

    @Test
    fun syncQueueCount_failsQuietly() = runBlocking {
        val (_, repo, _) = seeded()
        assertNull(SyncStatus.pendingCount(repo))
    }

    @Test
    fun aLoadFailure_isCaughtToo() = runBlocking {
        val broken = object : EventDao by BrokenDao(emptyList()) {
            override suspend fun all(): List<EventEntity> = throw SQLiteFullException("unreadable")
        }
        val repo = QuestRepository(broken, device, { TimeZone.UTC }, { now }, Dispatchers.Unconfined)
        assertNull(Calm.attempt("test", "loading the log") { repo.load() })
    }

    @Test
    fun lanAddresses_onlyInDebugBuilds() {
        assertTrue(SyncStatus.addressAllowed("https://quests.example.com", allowCleartext = false))
        assertTrue(!SyncStatus.addressAllowed("http://192.168.1.20:8080", allowCleartext = false))
        assertTrue(SyncStatus.addressAllowed("http://192.168.1.20:8080", allowCleartext = true))
        assertTrue(!SyncStatus.addressAllowed("ftp://example.com", allowCleartext = true))
        assertTrue(SyncStatus.modeLine(true).contains("http://"))
        assertTrue(SyncStatus.modeLine(false).contains("https://"))
    }
}
