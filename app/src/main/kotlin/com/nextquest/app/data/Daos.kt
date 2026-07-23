package com.nextquest.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestDao {
    @Query(
        "SELECT * FROM quests WHERE parentId IS NULL AND tracked = 1 AND status = 'OPEN' " +
            "ORDER BY orderIndex, createdAt"
    )
    fun observeTrackedRoots(): Flow<List<QuestEntity>>

    @Query("SELECT * FROM quests WHERE parentId IS NULL AND tracked = 0 AND status = 'OPEN'")
    fun observeBoardRoots(): Flow<List<QuestEntity>>

    @Query("SELECT COUNT(*) FROM quests WHERE parentId IS NULL AND tracked = 1 AND status = 'OPEN'")
    suspend fun trackedRootCount(): Int

    @Query("SELECT * FROM quests WHERE id = :id")
    suspend fun byId(id: String): QuestEntity?

    @Insert
    suspend fun insert(quest: QuestEntity)

    @Update
    suspend fun update(quest: QuestEntity)
}

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(event: EventLogEntity)

    @Query("SELECT type FROM event_log")
    fun observeTypes(): Flow<List<String>>

    @Query("SELECT * FROM event_log ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeTail(limit: Int): Flow<List<EventLogEntity>>
}
