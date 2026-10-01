// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.vault

import dev.dreamweave.quest.core.Envelope
import dev.dreamweave.quest.data.QuestRepository
import dev.dreamweave.quest.protocol.Snapshot
import dev.dreamweave.quest.protocol.SnapshotCodec
import dev.dreamweave.quest.protocol.SnapshotRead

/** What an import did, for the report dialog. */
data class ImportReport(val added: Int, val alreadyHad: Int, val skipped: List<String>, val rejected: List<String>, val error: String? = null)

/** Snapshot export and import. Import validates the file and merges — it never wipes. */
class Backups(private val repo: QuestRepository, private val clock: () -> Long = { System.currentTimeMillis() }) {
    suspend fun snapshot(): Snapshot = Snapshot(exportedAt = clock(), deviceId = repo.deviceId, events = repo.allEvents())

    suspend fun export(): String = SnapshotCodec.encode(snapshot())

    suspend fun import(text: String): ImportReport = when (val read = SnapshotCodec.decode(text)) {
        is SnapshotRead.Invalid -> ImportReport(0, 0, emptyList(), emptyList(), read.reason)
        is SnapshotRead.Ok -> {
            val now = clock()
            val (future, timely) = read.snapshot.events.partition { Envelope.tooFarAhead(it, now) }
            // Events stamped with this install's device id belong to the local event factory's
            // (deviceId, seq) sequence. One that isn't in the log was never committed here, and
            // ingesting it would take a sequence slot the factory is about to hand out. The device
            // id isn't restored by Android backup, so a genuine restore never hits this.
            val own = timely.filter { it.deviceId == repo.deviceId }
            val known = if (own.isEmpty()) emptySet() else repo.allEvents().mapTo(HashSet()) { it.id }
            val (foreignOwn, events) = timely.partition { it.deviceId == repo.deviceId && it.id !in known }
            val result = repo.ingest(events)
            ImportReport(
                added = result.added,
                alreadyHad = events.size - result.added,
                skipped = result.skipped,
                rejected = read.rejected +
                    future.map { "${it.id.take(13)}: dated in the future (wrong clock?)" } +
                    foreignOwn.map { "${it.id.take(13)}: claims to come from this phone but isn't in its log" },
            )
        }
    }
}
