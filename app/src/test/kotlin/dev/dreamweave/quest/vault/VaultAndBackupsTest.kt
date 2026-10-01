// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.vault

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.QuestStatus
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultAndBackupsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: QuestApp get() = ApplicationProvider.getApplicationContext()
    private val c get() = app.container

    @Test
    fun mirrorWritesOneNotePerQuest_replacesRenamedNotes_leavesUserFilesAlone() = runBlocking {
        c.actions.capture("Learn piano")
        val q = c.repository.current().quests.values.single().id
        c.actions.track(q, firstStep = "Find a teacher")
        c.actions.addSubquest(q, "Scales")
        val root = tmp.newFolder("vault")
        File(root, "Quests").mkdirs()
        File(root, "Quests/My own note.md").writeText("# mine\n")

        val writer = VaultWriter(app)
        val result = writer.mirror(DocumentFile.fromFile(root), c.repository.current(), c.repository.zone)
        assertEquals(2, result.written)
        val note = File(root, "Quests/Learn piano.md").readText()
        assertContains(note, "status: tracked")
        assertContains(note, "next-step:: Find a teacher")
        assertContains(note, "- [ ] [[Scales]]")
        assertTrue(File(root, "Quests/Scales.md").exists())

        // Unchanged state → nothing rewritten. Renamed quest → old note replaced.
        assertEquals(0, writer.mirror(DocumentFile.fromFile(root), c.repository.current(), c.repository.zone).written)
        c.actions.edit(q, title = "Play piano")
        val r2 = writer.mirror(DocumentFile.fromFile(root), c.repository.current(), c.repository.zone)
        assertEquals(1, r2.removed)
        assertTrue(File(root, "Quests/Play piano.md").exists())
        assertTrue(!File(root, "Quests/Learn piano.md").exists())
        assertEquals("# mine\n", File(root, "Quests/My own note.md").readText())
    }

    @Test
    fun dailySnapshotsRotate() {
        val root = tmp.newFolder("vault2")
        val writer = VaultWriter(app)
        for (day in 1..10) writer.writeSnapshot(DocumentFile.fromFile(root), "{}", "2026-03-%02d".format(day))
        val names = File(root, "Quests/.backups").list()!!.sorted()
        assertEquals(VaultWriter.KEEP, names.size)
        assertEquals("snapshot-2026-03-03.json", names.first())
        assertEquals("snapshot-2026-03-10.json", names.last())
    }

    @Test
    fun exportThenImport_mergesWithoutDuplicates_andRefusesForeignFiles() = runBlocking {
        c.actions.capture("Backed up")
        val json = c.backups.export()
        assertContains(json, "\"format\": \"dreamweave-quest.snapshot\"")

        // Importing what we already have adds nothing.
        val again = c.backups.import(json)
        assertEquals(0, again.added)
        assertTrue(again.alreadyHad > 0)

        // A snapshot from "another phone": change ids by exporting from a second app instance is
        // heavy, so craft one with a fresh device and quest.
        val other = dev.dreamweave.quest.core.EventFactory(dev.dreamweave.quest.core.Ids.newId(), clock = { 1_700_000_000_000 })
        val p = dev.dreamweave.quest.core.Events.questCreated(
            dev.dreamweave.quest.core.QuestCreated(dev.dreamweave.quest.core.Ids.newId(1_700_000_000_000), "From the old phone"),
        )
        val snap = dev.dreamweave.quest.protocol.Snapshot(exportedAt = 1, deviceId = other.deviceId, events = listOf(other.create(p.type, p.payload)))
        val merged = c.backups.import(dev.dreamweave.quest.protocol.SnapshotCodec.encode(snap))
        assertEquals(1, merged.added)
        val titles = c.repository.current().quests.values.map { it.title }.toSet()
        assertEquals(setOf("Backed up", "From the old phone"), titles)
        assertTrue(c.repository.current().quests.values.all { it.status == QuestStatus.INBOX })

        val bad = c.backups.import("{\"hello\": 1}")
        assertNotNull(bad.error)
    }
}
