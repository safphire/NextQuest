// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.core.Actors
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.data.db.QuestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: QuestDatabase
    private var now = 1_790_000_000_000L

    private val deviceA = Ids.newId(1_700_000_000_000)

    private fun repo() = QuestRepository(db.events(), deviceA, { TimeZone.UTC }, { now }, Dispatchers.Unconfined)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), QuestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun captureTrackDoneNext_isPersistedAndReloads() = runTest {
        val repo = repo()
        val actions = QuestActions(repo) { now }
        assertIs<CommitResult.Ok>(actions.capture("Write a song"))
        val q = repo.current().quests.values.single().id
        // Tracking without a first step is refused, nothing is stored.
        val refused = actions.track(q)
        assertIs<CommitResult.Rejected>(refused)
        assertEquals(1, db.events().count())

        assertIs<CommitResult.Ok>(actions.track(q, firstStep = "Hum the melody"))
        val step = repo.current().effectiveStep(q)!!.id
        now += 60_000
        assertIs<CommitResult.Rejected>(actions.completeStep(step, nextStepText = null, finishOwner = false))
        val done = actions.completeStep(step, nextStepText = "Record a demo", finishOwner = false)
        assertIs<CommitResult.Ok>(done)
        assertEquals(15, done.change.after.xp - done.change.before.xp)

        // A fresh repository over the same table folds to the same state and continues the sequence.
        val reloaded = repo()
        val state = reloaded.load()
        assertEquals("Record a demo", state.effectiveStep(q)!!.text)
        assertEquals(15, state.xp)
        assertIs<CommitResult.Ok>(QuestActions(reloaded) { now }.capture("Another"))
        val seqs = reloaded.allEvents().filter { it.deviceId == deviceA }.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.size, seqs.toSet().size)
    }

    @Test
    fun notificationDone_flagsTheQuest() = runTest {
        val repo = repo()
        val actions = QuestActions(repo) { now }
        actions.capture("Run")
        val q = repo.current().quests.values.single().id
        actions.track(q, "Shoes on")
        actions.completeStepFromNotification(repo.current().effectiveStep(q)!!.id)
        assertTrue(repo.current().needsNextStep(q))
        actions.setStep(q, "Run 1 km")
        assertTrue(!repo.current().needsNextStep(q))
    }

    @Test
    fun ingest_isIdempotent_andRefoldsOutOfOrderEvents() = runTest {
        val repo = repo()
        repo.load()
        val remote = EventFactory(Ids.newId(1_700_000_000_001), clock = { now - 3_600_000 })
        val createdP = Events.questCreated(QuestCreated(Ids.newId(now), "From tablet"))
        val created = remote.create(createdP.type, createdP.payload)
        QuestActions(repo) { now }.capture("Local")
        // The remote event is older than the local one → refold, both present.
        val r1 = repo.ingest(listOf(created))
        assertEquals(1, r1.added)
        assertEquals(2, repo.current().quests.size)
        assertEquals(0, repo.ingest(listOf(created)).added)
        // A rogue AI event is stored but skipped by the fold.
        val rogueP = Events.questCreated(QuestCreated(Ids.newId(now), "Sneaky"))
        val rogue = remote.create(rogueP.type, rogueP.payload, Actors.mcp("x"))
        val r2 = repo.ingest(listOf(rogue))
        assertEquals(1, r2.added)
        assertEquals(1, r2.skipped.size)
        assertEquals(2, repo.current().quests.size)
        assertEquals(QuestStatus.INBOX, repo.current().quests.values.first().status)
        // Local events written after seeing a remote one sort after it…
        val soon = remote.create("quest.created", Events.questCreated(QuestCreated(Ids.newId(now), "Soon")).payload)
        repo.ingest(listOf(soon.copy(ts = now + 60_000)))
        QuestActions(repo) { now }.capture("After soon")
        assertTrue(repo.allEvents().last { it.deviceId == deviceA }.ts > now + 60_000)
        // …even when the peer's clock is far ahead (causality wins; streaks use local stamps).
        val far = remote.create("quest.created", Events.questCreated(QuestCreated(Ids.newId(now), "Far")).payload)
        repo.ingest(listOf(far.copy(ts = now + 10_000_000)))
        QuestActions(repo) { now }.capture("After far")
        val mine = repo.allEvents().filter { it.deviceId == deviceA }.maxBy { it.seq }
        assertTrue(mine.ts > now + 10_000_000)
    }

    @Test
    fun refoldBeforeFirstLoad_thenCommit_works() = runTest {
        // A time-zone broadcast can refold before anything else touched the repository.
        val repo = repo()
        repo.refold()
        assertIs<CommitResult.Ok>(QuestActions(repo) { now }.capture("Still writable"))
        assertEquals(1, repo.current().quests.size)
    }

    @Test
    fun trackingAQuestWithAFocusedStepless_subquest_putsTheStepOnTheSubquest() = runTest {
        val repo = repo()
        val actions = QuestActions(repo) { now }
        actions.capture("Renovate")
        val parent = repo.current().quests.values.single().id
        assertIs<CommitResult.Ok>(actions.addSubquest(parent, "Kitchen"))
        val kitchen = repo.current().quests.values.single { it.title == "Kitchen" }.id
        assertIs<CommitResult.Ok>(actions.focus(parent, kitchen))
        assertIs<CommitResult.Ok>(actions.track(parent, firstStep = "Measure the walls"))
        val state = repo.current()
        assertEquals(QuestStatus.TRACKED, state.quests[parent]!!.status)
        val step = state.effectiveStep(parent)!!
        assertEquals("Measure the walls", step.text)
        assertEquals(kitchen, step.questId)
    }
}
