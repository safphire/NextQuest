// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol

import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.Envelope
import dev.dreamweave.quest.core.Event
import kotlinx.serialization.Serializable
import dev.dreamweave.quest.core.Ids
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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

    /** Parses and validates a snapshot file. Never throws: anything unexpected is [SnapshotRead.Invalid]. */
    fun decode(text: String): SnapshotRead = try {
        decodeChecked(text)
    } catch (e: Exception) {
        SnapshotRead.Invalid("The snapshot is damaged: ${e.message?.take(120)}")
    }

    /** A JSON string's content; null for anything else (numbers, objects, arrays, null). */
    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number; null for strings, objects, arrays and null. */
    private fun JsonElement?.numberOrNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { !it.isString }

    /** Real snapshots nest a few levels deep; the JSON tree reader recurses per level. */
    const val MAX_DEPTH = 64

    /** True when brackets outside strings nest deeper than [MAX_DEPTH] (would overflow the parser's stack). */
    private fun tooDeep(text: String): Boolean {
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\') i++ else if (ch == '"') inString = false
            } else {
                when (ch) {
                    '"' -> inString = true
                    '[', '{' -> if (++depth > MAX_DEPTH) return true
                    ']', '}' -> depth--
                }
            }
            i++
        }
        return false
    }

    private fun decodeChecked(text: String): SnapshotRead {
        if (tooDeep(text)) return SnapshotRead.Invalid("The snapshot is damaged: it is nested too deeply.")
        val parsed = try {
            CoreJson.parseToJsonElement(text)
        } catch (e: Exception) {
            return SnapshotRead.Invalid("This file isn't a snapshot (not JSON).")
        }
        val root = parsed as? JsonObject ?: return SnapshotRead.Invalid("This file isn't a snapshot from this app.")
        if (root["format"].stringOrNull() != Snapshot.FORMAT) return SnapshotRead.Invalid("This file isn't a snapshot from this app.")
        val version = root["schemaVersion"].numberOrNull()?.intOrNull
            ?: return SnapshotRead.Invalid("The snapshot has no schema version.")
        if (version <= 0) return SnapshotRead.Invalid("The snapshot has an invalid schema version.")
        if (version > Snapshot.SCHEMA_VERSION) {
            return SnapshotRead.Invalid("This snapshot comes from a newer version of the app. Update the app, then import it.")
        }
        // Header: always written by the encoder, so a bad one means the file was damaged or hand-made.
        val exportedAt = root["exportedAt"].numberOrNull()?.longOrNull
        if (exportedAt == null || exportedAt <= 0) return SnapshotRead.Invalid("The snapshot is damaged: its export time is missing or invalid.")
        val deviceId = root["deviceId"].stringOrNull()
        if (deviceId == null || !Ids.isValid(deviceId)) return SnapshotRead.Invalid("The snapshot is damaged: its device id is missing or invalid.")
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
