// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.protocol.mcp.McpToolSpecs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

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
                // JSON-RPC 2.0 §6: an empty batch is answered with one error, not an array.
                if (parsed.isEmpty()) return Reply(400, error(JsonNull, INVALID_REQUEST, "Invalid request: empty batch"))
                // Each message is logged (a DB write): keep one request's work bounded.
                if (parsed.size > MAX_BATCH) return Reply(400, error(JsonNull, INVALID_REQUEST, "Invalid request: at most $MAX_BATCH messages per batch"))
                val replies = parsed.mapNotNull { safeMessage(grant, it) }
                if (replies.isEmpty()) Reply(202, null) else Reply(200, JsonArray(replies))
            }
            else -> safeMessage(grant, parsed)?.let { Reply(200, it) } ?: Reply(202, null)
        }
    }

    /** One failing message never fails the batch: it gets -32603 and the rest are answered. */
    private fun safeMessage(grant: Grant, m: JsonElement): JsonElement? = try {
        message(grant, m)
    } catch (e: Exception) {
        log.error("mcp client=\"{}\" internal error", grant.clientName, e)
        val id = (m as? JsonObject)?.get("id")?.takeIf(::validId) ?: JsonNull
        if (id is JsonNull) null else error(id, INTERNAL_ERROR, "Internal error")
    }

    /** Handles one JSON-RPC message; null for notifications and responses (nothing to send back). */
    private fun message(grant: Grant, m: JsonElement): JsonElement? {
        val obj = m as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Invalid request")
        val rawId = obj["id"]
        val id = rawId?.takeIf(::validId) ?: JsonNull
        if (rawId != null && id is JsonNull && rawId !is JsonNull) return error(JsonNull, INVALID_REQUEST, "Invalid request: id must be a string or number")
        if ((obj["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "2.0") {
            return error(id, INVALID_REQUEST, "Invalid request: jsonrpc must be \"2.0\"")
        }
        val methodEl = obj["method"]
        if (methodEl == null) {
            // A response from the client (we never ask, so nothing to do); anything else with an id is malformed.
            return if (id is JsonNull || "result" in obj || "error" in obj) null else error(id, INVALID_REQUEST, "Invalid request: method is required")
        }
        val method = (methodEl as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(100)
            ?: return error(id, INVALID_REQUEST, "Invalid request: method must be a string")
        if (id is JsonNull) return null // notification (notifications/initialized, cancelled, …)
        val params = when (val p = obj["params"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> p
            else -> return error(id, INVALID_PARAMS, "Invalid params: expected an object").also { record(grant, method, null, false, "invalid params") }
        }
        return when (method) {
            "initialize" -> result(id, initialize(params)).also { record(grant, method, null, true, null) }
            "ping" -> result(id, JsonObject(emptyMap())).also { record(grant, method, null, true, null) }
            "tools/list" -> result(id, toolsList()).also { record(grant, method, null, true, null) }
            "tools/call" -> toolsCall(grant, id, params)
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method").also { record(grant, method, null, false, "method not found") }
        }
    }

    private fun validId(id: JsonElement): Boolean = id is JsonPrimitive && id !is JsonNull && (id.isString || id.content.toDoubleOrNull() != null)

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
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

    /** Protocol problems (bad params, unknown tool) are JSON-RPC errors; tool failures are `isError` results. */
    private fun toolsCall(grant: Grant, id: JsonElement, params: JsonObject): JsonObject {
        fun invalid(message: String, tool: String? = null) =
            error(id, INVALID_PARAMS, message).also { record(grant, "tools/call", tool, false, message) }
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return invalid("Invalid params: tools/call needs a tool name")
        if (McpToolSpecs.all.none { it.name == name }) return invalid("Unknown tool: ${name.take(100)}", name.take(100))
        val args = when (val a = params["arguments"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> return invalid("Invalid params: arguments must be an object", name)
        }
        return result(id, callTool(grant, name, args))
    }

    private fun callTool(grant: Grant, name: String, args: JsonObject): JsonObject {
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

    private val inserts = AtomicInteger()

    /**
     * Every request is logged with the connector's name (admin page and server log, D-505);
     * notifications aren't. The table keeps the newest [KEEP_CALLS] rows per user, pruned
     * every [PRUNE_EVERY] inserts.
     */
    private fun record(grant: Grant, method: String, tool: String?, ok: Boolean, detail: String?) {
        val clean = { s: String -> s.filter { !it.isISOControl() } }
        log.info("mcp client=\"{}\" method={} tool={} ok={}{}", grant.clientName, clean(method), tool?.let(clean) ?: "-", ok, detail?.let { " detail=\"${clean(it).take(200)}\"" } ?: "")
        db.update(
            "INSERT INTO mcp_calls (user_id, at, client_id, client_name, method, tool, ok, detail) VALUES (?,?,?,?,?,?,?,?)",
            grant.userId, clock(), grant.clientId, grant.clientName, method, tool, ok, detail?.take(500),
        )
        if (inserts.incrementAndGet() % PRUNE_EVERY == 0) prune(grant.userId)
    }

    fun prune(userId: String) {
        db.update(
            "DELETE FROM mcp_calls WHERE user_id = ? AND id <= (SELECT id FROM mcp_calls WHERE user_id = ? ORDER BY id DESC LIMIT 1 OFFSET ?)",
            userId, userId, KEEP_CALLS,
        )
    }

    data class Call(val at: Long, val client: String, val method: String, val tool: String?, val ok: Boolean, val detail: String?)

    fun recentCalls(userId: String, limit: Int = 25): List<Call> = db.query(
        "SELECT at, client_name, method, tool, ok, detail FROM mcp_calls WHERE user_id = ? ORDER BY id DESC LIMIT ?", userId, limit,
    ) { Call(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1, it.getString(6)) }

    fun callCount(userId: String): Int = db.one("SELECT COUNT(*) FROM mcp_calls WHERE user_id = ?", userId) { it.getInt(1) } ?: 0

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
        const val MAX_BATCH = 50
        /** Newest first; the first is offered when the client asks for an unknown one. */
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val KEEP_CALLS = 1_000
        const val PRUNE_EVERY = 50
    }
}
