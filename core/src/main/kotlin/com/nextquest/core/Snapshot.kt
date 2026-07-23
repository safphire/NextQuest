package com.nextquest.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Versioned JSON snapshot — a public contract. Quests + EventLog is the
 * complete truth, so one snapshot is a full backup. Never change the shape
 * without bumping [Snapshot.SCHEMA_VERSION] and keeping old versions readable.
 */
@Serializable
data class Snapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Epoch millis, supplied by the caller (core stays clock-free). */
    val exportedAt: Long,
    val quests: List<QuestSnapshot> = emptyList(),
    val events: List<EventSnapshot> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class QuestSnapshot(
    val id: String,
    val parentId: String? = null,
    val title: String,
    val nextStep: String? = null,
    val notes: String = "",
    val status: QuestStatus = QuestStatus.OPEN,
    val tracked: Boolean = false,
    val focusedChild: String? = null,
    val orderIndex: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null,
    val touchedAt: Long,
)

@Serializable
enum class QuestStatus { OPEN, DONE }

@Serializable
data class EventSnapshot(
    val id: String,
    val questId: String,
    val type: EventType,
    val payload: String? = null,
    val at: Long,
)

class SnapshotVersionException(val fileVersion: Int) : Exception(
    "Snapshot schemaVersion $fileVersion is newer than supported version ${Snapshot.SCHEMA_VERSION}"
)

object SnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true // older app versions must open future snapshots' known fields
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(snapshot: Snapshot): String = json.encodeToString(Snapshot.serializer(), snapshot)

    /**
     * @throws SnapshotVersionException when the file was written by a newer schema
     * @throws kotlinx.serialization.SerializationException on malformed input
     */
    fun decode(text: String): Snapshot {
        val version = json.parseToJsonElement(text)
            .jsonObject["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        if (version > Snapshot.SCHEMA_VERSION) throw SnapshotVersionException(version)
        return json.decodeFromString(Snapshot.serializer(), text)
    }
}
