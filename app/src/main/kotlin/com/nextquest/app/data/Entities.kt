package com.nextquest.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nextquest.core.EventType
import com.nextquest.core.QuestStatus

/** Room row per SPEC.md §2. Status/type columns store core enum names as text. */
@Entity(
    tableName = "quests",
    indices = [Index("parentId"), Index("tracked"), Index("touchedAt")],
)
data class QuestEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val title: String,
    val nextStep: String? = null,
    val notes: String = "",
    val status: String = QuestStatus.OPEN.name,
    val tracked: Boolean = false,
    val focusedChild: String? = null,
    val orderIndex: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null,
    val touchedAt: Long,
) {
    val isDone: Boolean get() = status == QuestStatus.DONE.name
}

@Entity(
    tableName = "event_log",
    indices = [Index("questId"), Index("at")],
)
data class EventLogEntity(
    @PrimaryKey val id: String,
    val questId: String,
    val type: String,
    val payload: String? = null,
    val at: Long,
) {
    /** Null for types written by a future app version — XP treats them as 0. */
    val eventType: EventType? get() = EventType.entries.firstOrNull { it.name == type }
}
