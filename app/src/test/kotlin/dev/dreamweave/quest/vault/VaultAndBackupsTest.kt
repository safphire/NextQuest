// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.vault

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.protocol.Snapshot
import dev.dreamweave.quest.protocol.SnapshotCodec
import dev.dreamweave.quest.protocol.SnapshotRead
import dev.dreamweave.quest.stopBackgroundWork
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultAndBackupsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: QuestApp get() = ApplicationProvider.getApplicationContext()

    @After
    fun stopAppWork() = app.stopBackgroundWork()

    private val c get() = app.container

    private suspend fun mirror(root: File) = VaultWriter(app).mirror(DocumentFile.fromFile(root), c.repository.current(), c.repository.zone)

    private val otherPhone = EventFactory(Ids.newId(1_700_000_000_000), clock = { 1_700_000_000_000 })

    private fun questEvent(factory: EventFactory, title: String): Event =
        Events.questCreated(QuestCreated(Ids.newId(1_700_000_000_000), title)).let { factory.create(it.type, it.payload) }

    private fun snapshotOf(events: List<Event>, deviceId: String = otherPhone.deviceId): String =
        SnapshotCodec.encode(Snapshot(exportedAt = 1, deviceId = deviceId, events = events))

    private fun eventCount(file: File): Int = (SnapshotCodec.decode(file.readText()) as SnapshotRead.Ok).snapshot.events.size

    @Test
    fun mirrorWritesOneNotePerQuest_replacesRenamedNotes_leavesUserFilesAlone() {
        runBlocking {
            c.actions.capture("Learn piano")
            val q = c.repository.current().quests.values.single().id
            c.actions.track(q, firstStep = "Find a teacher")
            c.actions.addSubquest(q, "Scales")
            val root = tmp.newFolder("vault")
            File(root, "Quests").mkdirs()
            File(root, "Quests/My own note.md").writeText("# mine\n")

            val result = mirror(root)
            assertEquals(2, result.written)
            val note = File(root, "Quests/Learn piano.md").readText()
            assertContains(note, "status: tracked")
            assertContains(note, "next-step:: Find a teacher")
            assertContains(note, "- [ ] [[Scales]]")
            assertTrue(File(root, "Quests/Scales.md").exists())

            // Unchanged state → nothing rewritten. Renamed quest → old note replaced.
            assertEquals(0, mirror(root).written)
            c.actions.edit(q, title = "Play piano")
            val r2 = mirror(root)
            assertEquals(1, r2.removed)
            assertTrue(File(root, "Quests/Play piano.md").exists())
            assertFalse(File(root, "Quests/Learn piano.md").exists())
            assertEquals("# mine\n", File(root, "Quests/My own note.md").readText())
        }
    }

    @Test
    fun aUserFileWithAQuestsName_isNeverOverwritten_andLinksFollowTheSuffixedName() {
        runBlocking {
            c.actions.capture("Learn piano")
            val q = c.repository.current().quests.values.single().id
            c.actions.track(q, firstStep = "Find a teacher")
            c.actions.addSubquest(q, "Scales")
            val root = tmp.newFolder("vault-own")
            File(root, "Quests").mkdirs()
            File(root, "Quests/Learn piano.md").writeText("# my own piano notes\n")

            val result = mirror(root)
            assertEquals("# my own piano notes\n", File(root, "Quests/Learn piano.md").readText())
            val suffixed = "Learn piano (${q.takeLast(6)})"
            assertContains(File(root, "Quests/$suffixed.md").readText(), "id: $q")
            assertContains(File(root, "Quests/Scales.md").readText(), "parent: \"[[$suffixed]]\"")
            assertEquals(2, result.written)
            // Stable: the next run changes nothing and still leaves the user's file alone.
            assertEquals(0, mirror(root).written)
            assertEquals("# my own piano notes\n", File(root, "Quests/Learn piano.md").readText())
        }
    }

    @Test
    fun copiesAndEditedNotesWithAnAppId_areNeverDeleted() {
        runBlocking {
            c.actions.capture("Learn piano")
            val q = c.repository.current().quests.values.single().id
            val root = tmp.newFolder("vault-copy")
            mirror(root)
            val original = File(root, "Quests/Learn piano.md").readText()
            // The user duplicates the note to annotate it: same id front matter, other name.
            File(root, "Quests/Learn piano copy.md").writeText(original)

            c.actions.edit(q, title = "Play piano")
            assertEquals(1, mirror(root).removed) // only the app's own, untouched note
            assertFalse(File(root, "Quests/Learn piano.md").exists())
            assertEquals(original, File(root, "Quests/Learn piano copy.md").readText())

            // An app note the user edited isn't the app's to delete any more.
            val edited = File(root, "Quests/Play piano.md").readText() + "\nMy annotation\n"
            File(root, "Quests/Play piano.md").writeText(edited)
            c.actions.edit(q, title = "Play guitar")
            assertEquals(0, mirror(root).removed)
            assertEquals(edited, File(root, "Quests/Play piano.md").readText())
            assertTrue(File(root, "Quests/Play guitar.md").exists())
        }
    }

    @Test
    fun dailySnapshotsRotate() {
        val root = tmp.newFolder("vault2")
        val writer = VaultWriter(app)
        for (day in 1..10) writer.writeSnapshot(DocumentFile.fromFile(root), "{}", "2026-03-%02d".format(day), "120000")
        val names = File(root, "Quests/.backups").list()!!.sorted()
        assertEquals(VaultWriter.KEEP, names.size)
        assertEquals("snapshot-2026-03-03.json", names.first())
        assertEquals("snapshot-2026-03-10.json", names.last())
    }

    @Test
    fun aSameDaySnapshotIsReplacedOnlyByOneThatHoldsAllItsEvents() {
        val root = tmp.newFolder("vault3")
        val dir = DocumentFile.fromFile(root)
        val writer = VaultWriter(app)
        val a = questEvent(otherPhone, "A")
        val b = questEvent(otherPhone, "B")
        val plain = File(root, "Quests/.backups/snapshot-2026-03-01.json")

        assertEquals(plain.name, writer.writeSnapshot(dir, snapshotOf(listOf(a, b)), "2026-03-01", "080000"))
        // A fresh install re-picking the vault: its smaller log must not replace the good copy.
        assertEquals("snapshot-2026-03-01-120000.json", writer.writeSnapshot(dir, snapshotOf(listOf(a)), "2026-03-01", "120000"))
        assertEquals(2, eventCount(plain))
        assertEquals(1, eventCount(File(root, "Quests/.backups/snapshot-2026-03-01-120000.json")))
        // A superset does replace it.
        val c3 = questEvent(otherPhone, "C")
        assertEquals(plain.name, writer.writeSnapshot(dir, snapshotOf(listOf(a, b, c3)), "2026-03-01", "130000"))
        assertEquals(3, eventCount(plain))
        val names = File(root, "Quests/.backups").list()!!.toSet()
        assertEquals(setOf(plain.name, "snapshot-2026-03-01-120000.json"), names, "no temp files left behind")
    }

    @Test
    fun pruningKeepsTheSnapshotWithTheMostEvents() {
        val root = tmp.newFolder("vault4")
        val dir = DocumentFile.fromFile(root)
        val writer = VaultWriter(app)
        val events = (1..3).map { questEvent(otherPhone, "Q$it") }
        writer.writeSnapshot(dir, snapshotOf(events), "2026-03-01", "120000")
        for (day in 2..12) writer.writeSnapshot(dir, snapshotOf(events.take(1)), "2026-03-%02d".format(day), "120000")
        val names = File(root, "Quests/.backups").list()!!.sorted()
        assertEquals(VaultWriter.KEEP + 1, names.size)
        assertEquals("snapshot-2026-03-01.json", names.first())
        assertEquals("snapshot-2026-03-12.json", names.last())
    }

    @Test
    fun exportThenImport_mergesWithoutDuplicates_andRefusesForeignFiles() {
        runBlocking {
            c.actions.capture("Backed up")
            val json = c.backups.export()
            assertContains(json, "\"format\": \"dreamweave-quest.snapshot\"")

            // Importing what we already have adds nothing.
            val again = c.backups.import(json)
            assertEquals(0, again.added)
            assertTrue(again.alreadyHad > 0)

            // A snapshot from "another phone".
            val merged = c.backups.import(snapshotOf(listOf(questEvent(otherPhone, "From the old phone"))))
            assertEquals(1, merged.added)
            val titles = c.repository.current().quests.values.map { it.title }.toSet()
            assertEquals(setOf("Backed up", "From the old phone"), titles)
            assertTrue(c.repository.current().quests.values.all { it.status == QuestStatus.INBOX })

            val bad = c.backups.import("{\"hello\": 1}")
            assertNotNull(bad.error)
            assertNotNull(c.backups.import("""{"format":{"a":1}}""").error)
            assertNotNull(c.backups.import("""{"format":"dreamweave-quest.snapshot","schemaVersion":[1]}""").error)
        }
    }

    @Test
    fun importDropsUnknownEventsClaimingToBeFromThisPhone() {
        runBlocking {
            c.actions.capture("Mine")
            val mine = c.repository.allEvents()
            // Same device id as this install, a sequence number the local factory hasn't reached.
            val forged = EventFactory(c.repository.deviceId, lastSeq = 1_000, clock = { 1_700_000_000_000 })
            val report = c.backups.import(snapshotOf(mine + questEvent(forged, "Not really mine"), deviceId = c.repository.deviceId))
            assertEquals(0, report.added)
            assertEquals(mine.size, report.alreadyHad)
            assertContains(report.rejected.single(), "this phone")
            assertEquals(mine.map { it.id }.toSet(), c.repository.allEvents().map { it.id }.toSet())
            assertEquals(setOf("Mine"), c.repository.current().quests.values.map { it.title }.toSet())

            // Local commits keep working.
            c.actions.capture("Still works")
            assertEquals(setOf("Mine", "Still works"), c.repository.current().quests.values.map { it.title }.toSet())
        }
    }

    @Test
    fun importReadsWithoutCrashingOnEveryKindOfBadFile() {
        runBlocking {
            for (text in listOf("", "[]", "null", "{\"format\":[]}", "[".repeat(10_000))) {
                assertNotNull(c.backups.import(text).error, "expected \"${text.take(20)}\" to be refused")
            }
        }
    }
}
