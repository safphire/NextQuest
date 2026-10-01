// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.core.Actors
import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.Envelope
import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Fold
import dev.dreamweave.quest.core.Folder
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.PendingEvent
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.protocol.PullResponse
import dev.dreamweave.quest.protocol.PushResponse
import dev.dreamweave.quest.protocol.RejectedEvent
import dev.dreamweave.quest.protocol.ServerEvent
import dev.dreamweave.quest.protocol.SyncApi
import kotlinx.serialization.json.JsonObject
import java.sql.ResultSet

/**
 * The sync hub: an append-only, per-user event table with a server-assigned cursor,
 * plus the folded state of each user's log (for the MCP read tools and for validating
 * proposals). The server never edits or deletes events.
 */
class EventStore(private val db: Db, private val config: Config, private val clock: () -> Long) {
    private val folders = HashMap<String, Folder>()

    private fun row(rs: ResultSet) = ServerEvent(
        rs.getLong("cursor"),
        Event(
            rs.getString("id"), rs.getString("device_id"), rs.getLong("seq"), rs.getLong("ts"),
            rs.getString("actor"), rs.getString("type"),
            CoreJson.decodeFromString(JsonObject.serializer(), rs.getString("payload")),
        ),
    )

    @Synchronized
    fun state(userId: String): QuestState = folder(userId).snapshot()

    private fun folder(userId: String): Folder = folders.getOrPut(userId) {
        Fold.folder(db.query("SELECT * FROM events WHERE user_id = ?", userId, map = ::row).map { it.event }, config.timeZone)
    }

    fun latestCursor(userId: String): Long = db.one("SELECT MAX(cursor) FROM events WHERE user_id = ?", userId) { it.getLong(1) } ?: 0

    /**
     * Stores events pushed by a device. Devices may only push `user` events (AI proposals
     * are written by the server itself), only under their own device id (so no device can
     * occupy another's sequence numbers), and never events dated more than a day ahead.
     */
    @Synchronized
    fun push(userId: String, deviceId: String, events: List<Event>): PushResponse {
        val now = clock()
        val rejected = mutableListOf<RejectedEvent>()
        val valid = events.filter { e ->
            val reason = Envelope.problem(e)
                ?: if (!Actors.isUser(e.actor)) "devices can only push user events" else null
                    ?: if (e.deviceId != deviceId) "devices can only push their own events" else null
                    ?: if (Envelope.tooFarAhead(e, now)) "dated more than a day in the future (check the device clock)" else null
            if (reason != null) rejected += RejectedEvent(e.id, reason)
            reason == null
        }
        val inserted = insert(userId, valid, now)
        return PushResponse(accepted = inserted.size, duplicates = valid.size - inserted.size, rejected = rejected, cursor = latestCursor(userId))
    }

    private fun insert(userId: String, events: List<Event>, now: Long): List<Event> {
        val inserted = db.tx {
            events.filter { e ->
                update(
                    "INSERT OR IGNORE INTO events (user_id, id, device_id, seq, ts, actor, type, payload, received_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    userId, e.id, e.deviceId, e.seq, e.ts, e.actor, e.type, CoreJson.encodeToString(JsonObject.serializer(), e.payload), now,
                ) == 1
            }
        }
        val f = folders[userId]
        if (f != null && inserted.isNotEmpty()) {
            if (inserted.any { f.isOutOfOrder(it) }) {
                folders.remove(userId) // refold lazily from the table
            } else {
                inserted.sortedWith(Event.ORDER).forEach { f.apply(it) }
            }
        }
        return inserted
    }

    fun pull(userId: String, after: Long, limit: Int = SyncApi.MAX_PULL): PullResponse {
        val n = limit.coerceIn(1, SyncApi.MAX_PULL)
        val rows = db.query("SELECT * FROM events WHERE user_id = ? AND cursor > ? ORDER BY cursor LIMIT ?", userId, after, n + 1, map = ::row)
        val page = rows.take(n)
        return PullResponse(page, page.lastOrNull()?.cursor ?: after, rows.size > n)
    }

    /** The server's own device id (UUIDv7), used for the events it writes on behalf of AI clients. */
    private fun serverDevice(userId: String): String =
        db.one("SELECT value FROM meta WHERE user_id = ? AND key = 'server_device_id'", userId) { it.getString(1) }
            ?: Ids.newId(clock()).also { db.update("INSERT INTO meta (user_id, key, value) VALUES (?, 'server_device_id', ?)", userId, it) }

    /**
     * Writes events created by the server (proposals from AI clients). They are stamped
     * after everything already in the log, and validated by the fold before storing.
     */
    @Synchronized
    fun appendServerEvents(userId: String, pending: List<PendingEvent>, actor: String): List<Event> {
        val device = serverDevice(userId)
        val lastSeq = db.one("SELECT MAX(seq) FROM events WHERE user_id = ? AND device_id = ?", userId, device) { it.getLong(1) } ?: 0
        val lastTs = db.one("SELECT MAX(ts) FROM events WHERE user_id = ?", userId) { it.getLong(1) } ?: 0
        val factory = EventFactory(device, lastSeq, lastTs, clock)
        val events = factory.createAll(pending, actor)
        val trial = folder(userId).copy()
        for (e in events) {
            val outcome = trial.apply(e)
            if (outcome is dev.dreamweave.quest.core.Outcome.Skipped) throw IllegalArgumentException(outcome.reason)
        }
        insert(userId, events, clock())
        return events
    }
}
