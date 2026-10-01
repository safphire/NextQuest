// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol

import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.Envelope
import dev.dreamweave.quest.core.Event
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A full backup: the complete event log. It is a public contract — any future version
 * must keep reading every older [schemaVersion]. Importing merges (never wipes): events
 * already present are skipped, everything else is folded like synced events.
 */
@Serializable
data class Snapshot(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val deviceId: String,
    val events: List<Event>,
) {
    companion object {
        const val FORMAT = "dreamweave-quest.snapshot"
        const val SCHEMA_VERSION = 1
        const val MIME = "application/json"
    }
}

sealed interface SnapshotRead {
    /** [rejected] lists envelope problems of events that were dropped before import. */
    data class Ok(val snapshot: Snapshot, val rejected: List<String>) : SnapshotRead
    data class Invalid(val reason: String) : SnapshotRead
}

object SnapshotCodec {
    // The header fields are defaults in the class but must always be written to the file.
    private val pretty = Json(from = CoreJson) {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(snapshot: Snapshot): String = pretty.encodeToString(Snapshot.serializer(), snapshot)

    /** Parses and validates a snapshot file. Never throws. */
    fun decode(text: String): SnapshotRead {
        val root = try {
            CoreJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return SnapshotRead.Invalid("This file isn't a snapshot (not JSON).")
        }
        val format = root["format"]?.jsonPrimitive?.contentOrNull
        if (format != Snapshot.FORMAT) return SnapshotRead.Invalid("This file isn't a snapshot from this app.")
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return SnapshotRead.Invalid("The snapshot has no schema version.")
        if (version > Snapshot.SCHEMA_VERSION) {
            return SnapshotRead.Invalid("This snapshot comes from a newer version of the app. Update the app, then import it.")
        }
        val snapshot = try {
            CoreJson.decodeFromJsonElement(Snapshot.serializer(), root)
        } catch (e: Exception) {
            return SnapshotRead.Invalid("The snapshot is damaged: ${e.message?.take(120)}")
        }
        val rejected = mutableListOf<String>()
        val good = snapshot.events.filter { e ->
            val problem = Envelope.problem(e)
            if (problem != null) rejected += "${e.id.take(13)}: $problem"
            problem == null
        }
        return SnapshotRead.Ok(snapshot.copy(events = good), rejected)
    }
}

