// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol.schema

import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.protocol.HistoryDto
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PairResponse
import dev.dreamweave.quest.protocol.ProposalCreatedDto
import dev.dreamweave.quest.protocol.ProposalListDto
import dev.dreamweave.quest.protocol.PullResponse
import dev.dreamweave.quest.protocol.PushRequest
import dev.dreamweave.quest.protocol.PushResponse
import dev.dreamweave.quest.protocol.QuestDto
import dev.dreamweave.quest.protocol.QuestListDto
import dev.dreamweave.quest.protocol.Snapshot
import dev.dreamweave.quest.protocol.StatsDto
import dev.dreamweave.quest.protocol.TodayDto
import dev.dreamweave.quest.protocol.mcp.McpToolSpecs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Every published schema, by file name. */
object Schemas {
    private val types: List<Pair<String, KSerializer<*>>> = listOf(
        "event" to Event.serializer(),
        "snapshot" to Snapshot.serializer(),
        "pair-request" to PairRequest.serializer(),
        "pair-response" to PairResponse.serializer(),
        "push-request" to PushRequest.serializer(),
        "push-response" to PushResponse.serializer(),
        "pull-response" to PullResponse.serializer(),
        "mcp-quest" to QuestDto.serializer(),
        "mcp-quest-list" to QuestListDto.serializer(),
        "mcp-today" to TodayDto.serializer(),
        "mcp-history" to HistoryDto.serializer(),
        "mcp-stats" to StatsDto.serializer(),
        "mcp-proposal-list" to ProposalListDto.serializer(),
        "mcp-proposal-created" to ProposalCreatedDto.serializer(),
    )

    fun all(): Map<String, JsonObject> =
        types.associate { (name, s) -> "$name.schema.json" to JsonSchema.of(s.descriptor, title = name) } +
            McpToolSpecs.all.associate { t -> "mcp-tool-${t.name}.input.schema.json" to JsonObject(mapOf("title" to kotlinx.serialization.json.JsonPrimitive(t.name)) + t.inputSchema) }

    private val pretty = Json { prettyPrint = true }

    fun render(schema: JsonObject): String = pretty.encodeToString(JsonObject.serializer(), schema) + "\n"
}

fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "schema")
    dir.mkdirs()
    val wanted = Schemas.all()
    dir.listFiles { f -> f.name.endsWith(".schema.json") && f.name !in wanted }?.forEach { it.delete() }
    for ((name, schema) in wanted) File(dir, name).writeText(Schemas.render(schema))
    println("Wrote ${wanted.size} schemas to ${dir.absolutePath}")
}
