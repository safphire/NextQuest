package com.nextquest.core

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotTest {

    private val snapshot = Snapshot(
        exportedAt = 1_753_228_800_000,
        quests = listOf(
            QuestSnapshot(
                id = "q1",
                title = "Diarytale",
                nextStep = "Improve prompts",
                tracked = true,
                createdAt = 1_753_000_000_000,
                touchedAt = 1_753_228_800_000,
            ),
            QuestSnapshot(
                id = "q2",
                parentId = "q1",
                title = "Approve memory design",
                status = QuestStatus.DONE,
                createdAt = 1_753_000_000_000,
                completedAt = 1_753_100_000_000,
                touchedAt = 1_753_100_000_000,
            ),
        ),
        events = listOf(
            EventSnapshot("e1", "q1", EventType.CREATED, at = 1_753_000_000_000),
            EventSnapshot("e2", "q1", EventType.STEP_DONE, payload = "Sketched layer diagram", at = 1_753_100_000_000),
        ),
    )

    @Test
    fun `round trip preserves everything`() {
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `schema version is embedded in the JSON`() {
        assertTrue(""""schemaVersion": 1""" in SnapshotCodec.encode(snapshot))
    }

    @Test
    fun `unknown fields from future minor additions are ignored`() {
        val withExtra = SnapshotCodec.encode(snapshot).replaceFirst(
            """"schemaVersion": 1,""",
            """"schemaVersion": 1, "someFutureField": {"x": 1},""",
        )
        assertEquals(snapshot, SnapshotCodec.decode(withExtra))
    }

    @Test
    fun `snapshots from a newer schema are refused, not misread`() {
        val future = SnapshotCodec.encode(snapshot)
            .replaceFirst(""""schemaVersion": 1""", """"schemaVersion": 99""")
        val e = assertFailsWith<SnapshotVersionException> { SnapshotCodec.decode(future) }
        assertEquals(99, e.fileVersion)
    }

    @Test
    fun `malformed json fails loudly`() {
        assertFailsWith<SerializationException> { SnapshotCodec.decode("""{"quests": "nope"}""") }
    }
}
