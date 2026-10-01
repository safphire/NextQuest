// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.core.Actors
import dev.dreamweave.quest.core.Decision
import dev.dreamweave.quest.core.EventTypes
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.NextStep
import dev.dreamweave.quest.core.ProposalRules
import dev.dreamweave.quest.core.ProposalStatus
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.QuestStatusSet
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpOAuthTest {
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun discoveryMetadata() = serverTest {
        val pr = ToolJson.parseToJsonElement(http.get("/.well-known/oauth-protected-resource").bodyAsText()).jsonObject
        assertEquals("https://quests.example/mcp", pr.str("resource"))
        val asm = ToolJson.parseToJsonElement(http.get("/.well-known/oauth-authorization-server").bodyAsText()).jsonObject
        assertEquals("https://quests.example/oauth/token", asm.str("token_endpoint"))
        assertEquals(JsonArray(listOf(JsonPrimitive("S256"))), asm["code_challenge_methods_supported"])
        assertNotNull(asm["registration_endpoint"])
    }

    @Test
    fun mcpRequiresATokenAndAdvertisesTheMetadata() = serverTest {
        val r = mcpRaw(null, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertContains(r.headers[HttpHeaders.WWWAuthenticate]!!, "resource_metadata=\"https://quests.example/.well-known/oauth-protected-resource\"")
        val bad = mcpRaw("nope", "{}")
        assertContains(bad.headers[HttpHeaders.WWWAuthenticate]!!, "invalid_token")
        val token = connect().str("access_token")
        assertEquals(HttpStatusCode.Forbidden, mcpRaw(token, "{}", origin = "https://evil.example").status)
        assertEquals(HttpStatusCode.OK, mcpRaw(token, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = "https://claude.ai").status)
        assertEquals(HttpStatusCode.MethodNotAllowed, http.get("/mcp").status)
    }

    @Test
    fun oauth_registrationConsentPkceAndRefreshRotation() = serverTest {
        // Registration rejects unsafe redirects.
        val badReg = http.post("/oauth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"redirect_uris":["http://evil.example/cb"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badReg.status)

        val clientId = register("ChatGPT", "https://chatgpt.com/connector_platform_oauth_redirect")
        val verifier = "x".repeat(60)
        val consent = http.get(
            "/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=https://chatgpt.com/connector_platform_oauth_redirect" +
                "&state=s1&code_challenge=${Secrets.pkceChallenge(verifier)}&code_challenge_method=S256",
        ).bodyAsText()
        assertContains(consent, "Allow ChatGPT?")
        assertContains(consent, "accept, edit or reject")

        // PKCE is mandatory; plain is refused. Until the owner approves the client, its
        // redirect URI is unverified, so the error is shown on the page (D-507).
        val plainUrl = "/oauth/authorize?response_type=code&client_id=$clientId&code_challenge=${"a".repeat(43)}&code_challenge_method=plain"
        assertEquals(HttpStatusCode.BadRequest, http.get(plainUrl).status)
        // Unknown clients see an error page, never a redirect.
        assertEquals(HttpStatusCode.BadRequest, http.get("/oauth/authorize?client_id=nope&redirect_uri=https://x.example").status)

        // Deny redirects with access_denied; a wrong password stays on the page.
        val form = { decision: String, password: String ->
            parameters {
                append("response_type", "code"); append("client_id", clientId)
                append("redirect_uri", "https://chatgpt.com/connector_platform_oauth_redirect"); append("state", "s1")
                append("code_challenge", Secrets.pkceChallenge(verifier)); append("code_challenge_method", "S256")
                append("decision", decision); append("password", password)
            }
        }
        val deniedFirst = http.submitForm("/oauth/authorize", form("deny", ""))
        assertEquals(HttpStatusCode.OK, deniedFirst.status)
        assertContains(deniedFirst.bodyAsText(), "Not connected")
        assertEquals(HttpStatusCode.Unauthorized, http.submitForm("/oauth/authorize", form("approve", "wrong")).status)
        val ok = http.submitForm("/oauth/authorize", form("approve", PASSWORD))
        val location = ok.headers[HttpHeaders.Location]!!
        assertTrue(location.startsWith("https://chatgpt.com/connector_platform_oauth_redirect?code="))
        assertContains(location, "state=s1")
        val code = java.net.URLDecoder.decode(Regex("code=([^&]+)").find(location)!!.groupValues[1], "UTF-8")
        // Approved now: errors and denials go back to the client.
        val plain = http.get(plainUrl)
        assertEquals(HttpStatusCode.Found, plain.status)
        assertContains(plain.headers[HttpHeaders.Location]!!, "error=invalid_request")
        assertContains(http.submitForm("/oauth/authorize", form("deny", "")).headers[HttpHeaders.Location]!!, "error=access_denied")

        suspend fun token(vararg p: Pair<String, String>) =
            http.submitForm("/oauth/token", parameters { p.forEach { (k, v) -> append(k, v) } })

        // Wrong verifier fails; right one succeeds; replaying the code fails and revokes.
        assertEquals(HttpStatusCode.BadRequest, token("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "code_verifier" to "y".repeat(60)).status)
        val t1 = ToolJson.parseToJsonElement(
            token("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "code_verifier" to verifier).bodyAsText(),
        ).jsonObject
        assertEquals("Bearer", t1.str("token_type"))
        val replay = token("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "code_verifier" to verifier)
        assertContains(replay.bodyAsText(), "already used")
        assertEquals(HttpStatusCode.Unauthorized, mcpRaw(t1.str("access_token"), """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)

        // A fresh grant; then refresh rotation and theft detection.
        val fresh = connect("ChatGPT")
        val t2 = ToolJson.parseToJsonElement(
            token("grant_type" to "refresh_token", "refresh_token" to fresh.str("refresh_token"), "client_id" to services.oauth.connections(services.config.userId).first().clientId).bodyAsText(),
        ).jsonObject
        assertEquals(HttpStatusCode.OK, mcpRaw(t2.str("access_token"), """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
        val reuse = token("grant_type" to "refresh_token", "refresh_token" to fresh.str("refresh_token"), "client_id" to services.oauth.connections(services.config.userId).first().clientId)
        assertEquals(HttpStatusCode.BadRequest, reuse.status)
        assertEquals(HttpStatusCode.Unauthorized, mcpRaw(t2.str("access_token"), """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)

        // Access tokens expire.
        val t3 = connect().str("access_token")
        clock.now += services.config.accessTokenTtlMs + 1
        assertEquals(HttpStatusCode.Unauthorized, mcpRaw(t3, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
    }

    @Test
    fun mcp_initializeListAndReadTools() = serverTest {
        val phone = pairDevice()
        val q = Ids.newId(clock.now)
        phone.push(
            Events.questCreated(QuestCreated(q, "Learn to sail")),
            Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED, NextStep(Ids.newId(clock.now), q, "Book a lesson"))),
        )
        val token = connect().str("access_token")
        val init = rpc(token, "initialize", buildJsonObject { put("protocolVersion", "2025-03-26"); put("capabilities", JsonObject(emptyMap())) })
        val result = init["result"]!!.jsonObject
        assertEquals("2025-03-26", result.str("protocolVersion"))
        assertEquals("NextQuest", result["serverInfo"]!!.jsonObject.str("name"))
        assertContains(result.str("instructions"), "cannot change anything directly")
        // Unknown versions get the newest supported.
        assertEquals("2025-06-18", rpc(token, "initialize", buildJsonObject { put("protocolVersion", "1999-01-01") })["result"]!!.jsonObject.str("protocolVersion"))
        // Notifications get 202 and no body; unknown methods a JSON-RPC error; bad JSON a parse error.
        assertEquals(HttpStatusCode.Accepted, mcpRaw(token, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        assertEquals(-32601, rpc(token, "resources/list")["error"]!!.jsonObject.str("code").toInt())
        assertEquals(HttpStatusCode.BadRequest, mcpRaw(token, "{not json").status)

        val tools = rpc(token, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(12, tools.size)
        val add = tools.first { it.jsonObject.str("name") == "propose_add_quest" }.jsonObject
        assertEquals(false, add["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content.toBoolean())

        val (err, today) = tool(token, "get_today")
        assertFalse(err)
        val tracked = today.jsonObject["tracked"]!!.jsonArray
        assertEquals("Book a lesson", tracked[0].jsonObject["next_step"]!!.jsonObject.str("text"))
        assertEquals("1", today.jsonObject.str("slots_used"))
        assertEquals("Learn to sail", tool(token, "get_quest", args { put("quest_id", q) }).second.jsonObject.str("title"))
        assertTrue(tool(token, "get_quest", args { put("quest_id", "nope") }).first)
        assertEquals(1, tool(token, "list_quests", args { put("status", "tracked") }).second.jsonObject["quests"]!!.jsonArray.size)
        assertTrue(tool(token, "list_quests", args { put("status", "weird") }).first)
        assertTrue(tool(token, "get_history", args { put("days", 3) }).second.jsonObject["entries"]!!.jsonArray.isNotEmpty())
        assertEquals("1", tool(token, "get_stats").second.jsonObject.str("level"))
        // Unknown tools are a protocol error (-32602), not a tool result.
        assertEquals(-32602, rpc(token, "tools/call", buildJsonObject { put("name", "no_such_tool") })["error"]!!.jsonObject.str("code").toInt())
    }

    @Test
    fun proposals_neverChangeState_travelToTheApp_andTheUserDecides() = serverTest {
        val phone = pairDevice()
        val q = Ids.newId(clock.now)
        phone.push(
            Events.questCreated(QuestCreated(q, "Write the book")),
            Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED, NextStep(Ids.newId(clock.now), q, "Outline"))),
        )
        val token = connect("Claude").str("access_token")
        val before = services.store.state(services.config.userId)

        val (err, created) = tool(token, "propose_set_next_step", args { put("quest_id", q); put("text", "Write chapter 1 intro"); put("reason", "You said the outline is done.") })
        assertFalse(err, created.toString())
        val proposalId = created.jsonObject.str("proposal_id")
        assertContains(created.jsonObject.str("message"), "Nothing has changed yet")
        // A reason is required (schema) and validated (blank).
        assertTrue(tool(token, "propose_park_quest", args { put("quest_id", q) }).first)
        assertTrue(tool(token, "propose_park_quest", args { put("quest_id", q); put("reason", " ") }).first)

        // Quest state is untouched; only a pending proposal exists.
        val after = services.store.state(services.config.userId)
        assertEquals(before.quests, after.quests)
        assertEquals(before.steps, after.steps)
        assertEquals(ProposalStatus.PENDING, after.proposals.getValue(proposalId).status)

        // It reaches the phone as a proposal.created event from mcp:Claude.
        val pulled = phone.pull()
        val ev = pulled.single { it.type == EventTypes.PROPOSAL_CREATED }
        assertEquals(Actors.mcp("Claude"), ev.actor)

        // The user accepts in the app: user events + proposal.accepted come back.
        val decision = ProposalRules.decide(after, proposalId, Decision.Accept())
        val r = phone.push(*decision.toTypedArray())
        assertEquals(decision.size, r.accepted)
        val final = services.store.state(services.config.userId)
        assertEquals("Write chapter 1 intro", final.effectiveStep(q)!!.text)
        assertEquals(ProposalStatus.ACCEPTED, final.proposals.getValue(proposalId).status)
        assertEquals("accepted", tool(token, "list_proposals", args { put("status", "accepted") }).second.jsonObject["proposals"]!!.jsonArray[0].jsonObject.str("status"))
        assertEquals("Write chapter 1 intro", tool(token, "get_today").second.jsonObject["tracked"]!!.jsonArray[0].jsonObject["next_step"]!!.jsonObject.str("text"))
    }

    @Test
    fun theCapIsEnforcedForAiProposals() = serverTest {
        val phone = pairDevice()
        val ids = List(5) { Ids.newId(clock.now + it) }
        phone.push(
            *ids.flatMap { q ->
                listOf(
                    Events.questCreated(QuestCreated(q, "Q $q")),
                    Events.questStatusSet(QuestStatusSet(q, QuestStatus.TRACKED, NextStep(Ids.newId(clock.now), q, "step"))),
                )
            }.toTypedArray(),
        )
        val token = connect().str("access_token")
        val (err, msg) = tool(token, "propose_add_quest", args { put("title", "Run a marathon"); put("next_step", "Buy shoes"); put("track", true); put("reason", "r") })
        assertTrue(err)
        assertContains(msg.jsonPrimitive.content, "slots are full")
        // Into the inbox is fine; with a pending park, tracking is fine too.
        assertFalse(tool(token, "propose_add_quest", args { put("title", "Run a marathon"); put("reason", "r") }).first)
        assertFalse(tool(token, "propose_park_quest", args { put("quest_id", ids[0]); put("reason", "r") }).first)
        assertFalse(tool(token, "propose_add_quest", args { put("title", "Swim"); put("next_step", "Find a pool"); put("track", true); put("reason", "r") }).first)
        assertTrue(tool(token, "propose_add_quest", args { put("title", "Fly"); put("next_step", "x"); put("track", true); put("reason", "r") }).first)
        assertEquals(5, services.store.state(services.config.userId).tracked.size)
        // Bad dates are explained.
        assertContains(tool(token, "propose_schedule_step", args { put("quest_id", ids[1]); put("due_at", "next tuesday"); put("reason", "r") }).second.jsonPrimitive.content, "ISO-8601")
        assertFalse(tool(token, "propose_schedule_step", args { put("quest_id", ids[1]); put("due_at", "2026-10-02T09:00"); put("alarm", true); put("reason", "r") }).first)
        assertFalse(tool(token, "propose_complete_step", args { put("quest_id", ids[2]); put("reason", "r") }).first)
        assertFalse(tool(token, "propose_add_subquest", args { put("parent_id", ids[3]); put("title", "Part"); put("reason", "r") }).first)
    }

    @Test
    fun rateLimitsAndCallLog() = serverTest(configure = { it.copy(mcpCallsPerMinute = 3, proposalsPerHour = 1) }) {
        val token = connect("Claude").str("access_token")
        repeat(3) { assertFalse(tool(token, "get_stats").first) }
        val (err, msg) = tool(token, "get_stats")
        assertTrue(err)
        assertContains(msg.jsonPrimitive.content, "Too many calls")
        clock.now += 61_000
        assertFalse(tool(token, "propose_add_quest", args { put("title", "A"); put("reason", "r") }).first)
        assertContains(tool(token, "propose_add_quest", args { put("title", "B"); put("reason", "r") }).second.jsonPrimitive.content, "Proposal limit")
        val calls = services.mcp.recentCalls(services.config.userId)
        assertTrue(calls.all { it.client == "Claude" })
        assertTrue(calls.any { !it.ok && it.detail == "rate limited" })
        assertTrue(calls.any { it.ok && it.tool == "propose_add_quest" })
        // The admin page lists the connector and its calls; disconnecting revokes its tokens.
        val cookie = login()
        val page = http.get("/") { header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie") }.bodyAsText()
        assertContains(page, "propose_add_quest")
        assertContains(page, "https://quests.example/mcp")
        val clientId = services.oauth.connections(services.config.userId).single().clientId
        val csrfToken = csrf(cookie)
        http.submitForm("/connections/revoke", parameters { append("csrf", csrfToken); append("id", clientId) }) {
            header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie")
        }
        assertEquals(HttpStatusCode.Unauthorized, mcpRaw(token, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
    }
}
