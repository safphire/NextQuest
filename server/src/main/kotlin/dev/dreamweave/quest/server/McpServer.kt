// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.protocol.mcp.McpToolSpecs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * MCP over Streamable HTTP, implemented directly (docs/DECISIONS.md D-501): JSON-RPC 2.0
 * messages POSTed to /mcp, answered with `application/json`. The server is stateless —
 * no sessions, no server-initiated messages — which the spec allows; every request
 * carries its OAuth access token.
 */
class McpServer(
    private val tools: McpTools,
    private val config: Config,
    private val db: Db,
    private val limiter: RateLimiter,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger("mcp")

    /** The HTTP-level outcome of one POST. */
    data class Reply(val status: Int, val body: JsonElement?)

    fun handle(grant: Grant, body: String): Reply {
        val parsed = try {
            ToolJson.parseToJsonElement(body)
        } catch (e: Exception) {
            return Reply(400, error(JsonNull, PARSE_ERROR, "Parse error"))
        }
        return when (parsed) {
            is JsonArray -> {
                val replies = parsed.mapNotNull { message(grant, it) }
                if (replies.isEmpty()) Reply(202, null) else Reply(200, JsonArray(replies))
            }
            else -> message(grant, parsed)?.let { Reply(200, it) } ?: Reply(202, null)
        }
    }

    /** Handles one JSON-RPC message; null for notifications and responses (nothing to send back). */
    private fun message(grant: Grant, m: JsonElement): JsonElement? {
        val obj = m as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Invalid request")
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        if (method == null) return null // a response or garbage from the client: nothing to answer
        if (id == null || id is JsonNull) return null // notification (notifications/initialized, cancelled, …)
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, toolsList())
            "tools/call" -> result(id, toolsCall(grant, params))
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method").also { record(grant, method, null, false, "method not found") }
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.first()
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", config.appName)
                put("version", VERSION)
            }
            put(
                "instructions",
                "This is the user's personal quest log. Each tracked quest shows exactly one next step and there is a hard cap on " +
                    "tracked quests. You can read everything. You cannot change anything directly: every propose_* tool creates a " +
                    "proposal that the user accepts, edits or rejects in the app. Prefer small, concrete next steps.",
            )
        }
    }

    private fun toolsList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            for (t in McpToolSpecs.all) {
                add(
                    buildJsonObject {
                        put("name", t.name)
                        put("title", t.title)
                        put("description", t.description)
                        put("inputSchema", t.inputSchema)
                        putJsonObject("annotations") {
                            put("title", t.title)
                            put("readOnlyHint", t.readOnly)
                            put("destructiveHint", false)
                            put("idempotentHint", t.readOnly)
                            put("openWorldHint", false)
                        }
                    },
                )
            }
        }
    }

    private fun toolsCall(grant: Grant, params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: return toolError("tools/call needs a tool name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        limiter.hit("mcp:${grant.clientId}", config.mcpCallsPerMinute, 60_000)?.let { wait ->
            record(grant, "tools/call", name, false, "rate limited")
            return toolError("Too many calls. Try again in $wait seconds.")
        }
        if (name.startsWith("propose_")) {
            limiter.hit("proposals:${grant.clientId}", config.proposalsPerHour, 3_600_000)?.let { wait ->
                record(grant, "tools/call", name, false, "proposal limit")
                return toolError("Proposal limit reached for this hour (${config.proposalsPerHour}). Try again in ${wait / 60 + 1} minutes.")
            }
        }
        return when (val r = tools.call(grant, name, args)) {
            is ToolResult.Ok -> {
                record(grant, "tools/call", name, true, null)
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", ToolJson.encodeToString(JsonElement.serializer(), r.json)) })
                    }
                    if (r.json is JsonObject) put("structuredContent", r.json)
                    put("isError", false)
                }
            }
            is ToolResult.Error -> {
                record(grant, "tools/call", name, false, r.message)
                toolError(r.message)
            }
        }
    }

    private fun toolError(message: String) = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", message) }) }
        put("isError", true)
    }

    /** Every tool call is logged with the connector's name (admin page and server log). */
    private fun record(grant: Grant, method: String, tool: String?, ok: Boolean, detail: String?) {
        log.info("mcp client=\"{}\" method={} tool={} ok={}{}", grant.clientName, method, tool ?: "-", ok, detail?.let { " detail=\"${it.take(200)}\"" } ?: "")
        db.update(
            "INSERT INTO mcp_calls (user_id, at, client_id, client_name, method, tool, ok, detail) VALUES (?,?,?,?,?,?,?,?)",
            grant.userId, clock(), grant.clientId, grant.clientName, method, tool, ok, detail?.take(500),
        )
    }

    data class Call(val at: Long, val client: String, val tool: String?, val ok: Boolean, val detail: String?)

    fun recentCalls(userId: String, limit: Int = 25): List<Call> = db.query(
        "SELECT at, client_name, tool, ok, detail FROM mcp_calls WHERE user_id = ? ORDER BY id DESC LIMIT ?", userId, limit,
    ) { Call(it.getLong(1), it.getString(2), it.getString(3), it.getInt(4) == 1, it.getString(5)) }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    companion object {
        const val VERSION = "1.0.0"
        /** Newest first; the first is offered when the client asks for an unknown one. */
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
    }
}
