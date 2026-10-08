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

    private fun invalid(json: String): String {
        val read = SnapshotCodec.decode(json)
        assertIs<SnapshotRead.Invalid>(read, "expected $json to be refused")
        return read.reason
    }

    @Test
    fun neverThrows_onOddlyTypedHeaderFieldsOrGarbage() {
        val f = Snapshot.FORMAT
        val header = """"exportedAt":1,"deviceId":"$device","events":[]"""
        assertContains(invalid("""{"format":{"a":1}}"""), "isn't a snapshot")
        assertContains(invalid("""{"format":["$f"]}"""), "isn't a snapshot")
        assertContains(invalid("""{"format":null}"""), "isn't a snapshot")
        assertContains(invalid("""["$f"]"""), "isn't a snapshot")
        assertContains(invalid(""""$f""""), "isn't a snapshot")
        assertContains(invalid("""{"format":"$f","schemaVersion":[1]}"""), "no schema version")
        assertContains(invalid("""{"format":"$f","schemaVersion":{"v":1}}"""), "no schema version")
        assertContains(invalid("""{"format":"$f","schemaVersion":"1"}"""), "no schema version")
        assertContains(invalid("""{"format":"$f","schemaVersion":1.5}"""), "no schema version")
        assertContains(invalid("""{"format":"$f","schemaVersion":0,$header}"""), "invalid schema version")
        assertContains(invalid("""{"format":"$f","schemaVersion":-3,$header}"""), "invalid schema version")
        assertContains(invalid("""{"format":"$f","schemaVersion":1,"exportedAt":{},"deviceId":"$device","events":[]}"""), "export time")
        assertContains(invalid("""{"format":"$f","schemaVersion":1,"exportedAt":0,"deviceId":"$device","events":[]}"""), "export time")
        assertContains(invalid("""{"format":"$f","schemaVersion":1,"exportedAt":1,"deviceId":"nope","events":[]}"""), "device id")
        assertContains(invalid("""{"format":"$f","schemaVersion":1,"exportedAt":1,"deviceId":[1],"events":[]}"""), "device id")
        assertContains(invalid("""{"format":"$f","schemaVersion":1,$header,"events":{"a":1}}""".replace(""""events":[],""", "")), "damaged")
        assertContains(invalid("""{"format":"$f","schemaVersion":1,"exportedAt":1,"deviceId":"$device","events":[1,"x",null]}"""), "damaged")
        assertContains(invalid("""{"format":"$f","schemaVersion":1,"exportedAt":1,"deviceId":"$device","events":[{"id":{"x":1}}]}"""), "damaged")
        for (garbage in listOf("", " ", "{", "}", "null", "42", "\u0000", "{\"format\":", "[".repeat(5000), "{\"a\":".repeat(5000))) {
            assertIs<SnapshotRead.Invalid>(SnapshotCodec.decode(garbage))
        }
        // A well-formed empty snapshot is fine.
        assertIs<SnapshotRead.Ok>(SnapshotCodec.decode("""{"format":"$f","schemaVersion":1,$header}"""))
    }
}
