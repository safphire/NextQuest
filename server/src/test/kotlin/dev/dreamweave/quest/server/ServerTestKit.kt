// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.PendingEvent
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PairResponse
import dev.dreamweave.quest.protocol.PullResponse
import dev.dreamweave.quest.protocol.PushRequest
import dev.dreamweave.quest.protocol.PushResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.http.setCookie
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files

const val PASSWORD = "correct horse battery staple"
const val CLAUDE_REDIRECT = "https://claude.ai/api/mcp/auth_callback"

/** A running test server plus helpers that speak its protocols. */
class Kit(val t: ApplicationTestBuilder, val services: Services, val clock: Clock) {
    val http: HttpClient = t.createClient {
        install(ContentNegotiation) { json(ToolJson) }
        followRedirects = false
    }

    class Clock(var now: Long)

    // ---- admin + pairing ----------------------------------------------------------------

    suspend fun login(): String {
        val r = http.submitForm("/login", parameters { append("password", PASSWORD) })
        return r.setCookie().first { it.name == Sessions.COOKIE }.value
    }

    suspend fun csrf(cookie: String): String {
        val html = http.get("/") { header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie") }.bodyAsText()
        return Regex("name=\"csrf\" value=\"([^\"]+)\"").find(html)!!.groupValues[1]
    }

    suspend fun pairingCode(): String {
        val cookie = login()
        val token = csrf(cookie)
        val html = http.submitForm("/pair", parameters { append("csrf", token) }) {
            header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie")
        }.bodyAsText()
        return Regex("class=\"code\">([A-Z0-9-]+)<").find(html)!!.groupValues[1]
    }

    inner class DeviceClient(val deviceId: String, val token: String) {
        val factory = EventFactory(deviceId, clock = { clock.now })
        var cursor = 0L

        suspend fun push(vararg p: PendingEvent): PushResponse = pushEvents(p.map { factory.create(it.type, it.payload) })

        suspend fun pushEvents(events: List<Event>): PushResponse =
            http.post("/v1/events") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(PushRequest(events))
            }.body()

        suspend fun pull(): List<Event> {
            val out = mutableListOf<Event>()
            while (true) {
                val r: PullResponse = http.get("/v1/events?after=$cursor&limit=50") { bearerAuth(token) }.body()
                out += r.events.map { it.event }
                r.events.forEach { factory.observe(it.event.ts) }
                cursor = r.cursor
                if (!r.hasMore) break
            }
            return out
        }
    }

    suspend fun pairDevice(name: String = "Pixel"): DeviceClient {
        val deviceId = Ids.newId(clock.now)
        val res: PairResponse = http.post("/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest(pairingCode(), deviceId, name))
        }.body()
        return DeviceClient(deviceId, res.token)
    }

    // ---- OAuth + MCP --------------------------------------------------------------------

    suspend fun register(name: String = "Claude", redirect: String = CLAUDE_REDIRECT): String {
        val r = http.post("/oauth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"redirect_uris":["$redirect"],"client_name":"$name","token_endpoint_auth_method":"none"}""")
        }
        return ToolJson.parseToJsonElement(r.bodyAsText()).jsonObject["client_id"]!!.jsonPrimitive.content
    }

    /** Full authorization-code + PKCE flow; returns the token response. */
    suspend fun connect(name: String = "Claude", verifier: String = "v".repeat(50)): JsonObject {
        val clientId = register(name)
        val challenge = Secrets.pkceChallenge(verifier)
        val approve = http.submitForm(
            "/oauth/authorize",
            parameters {
                append("response_type", "code"); append("client_id", clientId); append("redirect_uri", CLAUDE_REDIRECT)
                append("state", "xyz"); append("code_challenge", challenge); append("code_challenge_method", "S256")
                append("resource", services.config.mcpUrl); append("decision", "approve"); append("password", PASSWORD)
            },
        )
        val location = approve.headers[HttpHeaders.Location]!!
        val code = Regex("code=([^&]+)").find(location)!!.groupValues[1]
        val token = http.submitForm(
            "/oauth/token",
            parameters {
                append("grant_type", "authorization_code"); append("code", java.net.URLDecoder.decode(code, "UTF-8"))
                append("redirect_uri", CLAUDE_REDIRECT); append("client_id", clientId); append("code_verifier", verifier)
            },
        )
        return ToolJson.parseToJsonElement(token.bodyAsText()).jsonObject
    }

    suspend fun mcpRaw(token: String?, body: String, origin: String? = null): HttpResponse = http.post("/mcp") {
        if (token != null) bearerAuth(token)
        if (origin != null) header(HttpHeaders.Origin, origin)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private var rpcId = 0

    suspend fun rpc(token: String, method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ++rpcId); put("method", method); put("params", params)
        }
        return ToolJson.parseToJsonElement(mcpRaw(token, body.toString()).bodyAsText()).jsonObject
    }

    /** Calls a tool; returns (isError, structured content or text). */
    suspend fun tool(token: String, name: String, args: JsonObject = JsonObject(emptyMap())): Pair<Boolean, JsonElement> {
        val r = rpc(token, "tools/call", buildJsonObject { put("name", name); put("arguments", args) })
        val result = r["result"]!!.jsonObject
        val isError = result["isError"]!!.jsonPrimitive.content.toBoolean()
        return isError to (result["structuredContent"] ?: result["content"]!!.jsonArray[0].jsonObject["text"]!!)
    }
}

fun serverTest(
    configure: (Config) -> Config = { it },
    block: suspend Kit.() -> Unit,
) = testApplication {
    val dir = Files.createTempDirectory("quest-server").toFile()
    val clock = Kit.Clock(1_790_000_000_000)
    val config = configure(
        Config(
            publicUrl = "https://quests.example", adminPassword = PASSWORD, dataDir = dir, timeZone = TimeZone.UTC,
            mcpCallsPerMinute = 1_000, proposalsPerHour = 1_000,
        ),
    )
    var services: Services? = null
    application { services = questModule(config) { clock.now } }
    startApplication()
    Kit(this, services!!, clock).block()
}

fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)
