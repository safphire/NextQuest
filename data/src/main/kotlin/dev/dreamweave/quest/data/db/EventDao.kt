// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EventDao {
    /** Idempotent by event id: an event already in the log is ignored (returns -1 for it). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>): List<Long>

    @Query("SELECT * FROM events ORDER BY ts, device_id, seq, id")
    suspend fun all(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("SELECT MAX(seq) FROM events WHERE device_id = :deviceId")
    suspend fun maxSeq(deviceId: String): Long?

    @Query("SELECT MAX(ts) FROM events")
    suspend fun maxTs(): Long?

    @Query("SELECT id FROM events WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    /**
     * User events the sync server doesn't have yet, keyset-paged by id (UUIDv7, so roughly
     * oldest first). Includes other devices' events that arrived by snapshot import; AI
     * proposals are never pushed (only the server writes them).
     */
    @Query("SELECT * FROM events WHERE pushed = 0 AND actor = :actor AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun unpushed(actor: String, afterId: String, limit: Int): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE pushed = 0 AND actor = :actor")
    suspend fun unpushedCount(actor: String): Int

    /** At most 500 ids per call (SQLite's bound-variable limit). */
    @Query("UPDATE events SET pushed = 1 WHERE id IN (:ids)")
    suspend fun markPushed(ids: List<String>)

    /** After pairing with a (different) server, the whole log is offered to it again. */
    @Query("UPDATE events SET pushed = 0")
    suspend fun markAllUnpushed()
}
