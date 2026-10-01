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
}
