// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.Event
import kotlinx.serialization.json.JsonObject

/** One row per event of the append-only log. Rows are never updated or deleted. */
@Entity(
    tableName = "events",
    indices = [Index(value = ["ts", "device_id", "seq"]), Index(value = ["device_id", "seq"])],
)
data class EventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val seq: Long,
    val ts: Long,
    val actor: String,
    val type: String,
    /** The payload as a JSON object string. */
    val payload: String,
) {
    fun toEvent(): Event = Event(id, deviceId, seq, ts, actor, type, CoreJson.decodeFromString(JsonObject.serializer(), payload))

    companion object {
        fun from(e: Event) = EventEntity(e.id, e.deviceId, e.seq, e.ts, e.actor, e.type, CoreJson.encodeToString(JsonObject.serializer(), e.payload))
    }
}
