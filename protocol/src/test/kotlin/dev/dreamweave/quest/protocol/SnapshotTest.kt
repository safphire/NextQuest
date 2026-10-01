// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol

import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.QuestCreated
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SnapshotTest {
    private val device = Ids.newId(1_700_000_000_000)
    private val f = EventFactory(device, clock = { 1_700_000_000_000 })
    private fun quest(title: String): Event = Events.questCreated(QuestCreated(Ids.newId(), title)).let { f.create(it.type, it.payload) }

    @Test
    fun roundTrip() {
        val snap = Snapshot(exportedAt = 1, deviceId = device, events = listOf(quest("A"), quest("B")))
        val read = SnapshotCodec.decode(SnapshotCodec.encode(snap))
        assertIs<SnapshotRead.Ok>(read)
        assertEquals(snap, read.snapshot)
        assertEquals(emptyList(), read.rejected)
    }

    @Test
    fun rejectsForeignNewerAndDamagedFiles_andDropsBadEnvelopes() {
        assertContains((SnapshotCodec.decode("not json") as SnapshotRead.Invalid).reason, "not JSON")
        assertContains((SnapshotCodec.decode("""{"format":"other"}""") as SnapshotRead.Invalid).reason, "isn't a snapshot")
        assertContains((SnapshotCodec.decode("""{"format":"${Snapshot.FORMAT}"}""") as SnapshotRead.Invalid).reason, "no schema version")
        assertContains((SnapshotCodec.decode("""{"format":"${Snapshot.FORMAT}","schemaVersion":99}""") as SnapshotRead.Invalid).reason, "newer version")
        assertContains((SnapshotCodec.decode("""{"format":"${Snapshot.FORMAT}","schemaVersion":1,"events":[{}]}""") as SnapshotRead.Invalid).reason, "damaged")
        val bad = Event("nope", device, 1, 1, "user", "quest.created", JsonObject(emptyMap()))
        val read = SnapshotCodec.decode(SnapshotCodec.encode(Snapshot(exportedAt = 1, deviceId = device, events = listOf(quest("ok"), bad))))
        assertIs<SnapshotRead.Ok>(read)
        assertEquals(1, read.snapshot.events.size)
        assertContains(read.rejected.single(), "not a UUIDv7")
    }
}
