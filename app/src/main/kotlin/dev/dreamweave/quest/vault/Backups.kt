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
    suspend fun export(): String =
        SnapshotCodec.encode(Snapshot(exportedAt = clock(), deviceId = repo.deviceId, events = repo.allEvents()))

    suspend fun import(text: String): ImportReport = when (val read = SnapshotCodec.decode(text)) {
        is SnapshotRead.Invalid -> ImportReport(0, 0, emptyList(), emptyList(), read.reason)
        is SnapshotRead.Ok -> {
            val now = clock()
            val (future, events) = read.snapshot.events.partition { Envelope.tooFarAhead(it, now) }
            val result = repo.ingest(events)
            ImportReport(
                added = result.added,
                alreadyHad = events.size - result.added,
                skipped = result.skipped,
                rejected = read.rejected + future.map { "${it.id.take(13)}: dated in the future (wrong clock?)" },
            )
        }
    }
}
