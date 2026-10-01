// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.NextStep
import dev.dreamweave.quest.core.ProposalSource
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.QuestStatusSet
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The review findings on the server: limits, headers, OAuth hardening, JSON-RPC robustness. */
class SecurityTest {
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

    private fun Kit.consentForm(clientId: String, verifier: String, redirect: String = CLAUDE_REDIRECT, extra: io.ktor.http.ParametersBuilder.() -> Unit): Parameters =
        parameters {
            append("response_type", "code"); append("client_id", clientId); append("redirect_uri", redirect); append("state", "s")
            append("code_challenge", Secrets.pkceChallenge(verifier)); append("code_challenge_method", "S256")
            append("resource", services.config.mcpUrl)
            extra()
        }

    private suspend fun Kit.approvedCode(clientId: String, verifier: String): String {
        val r = http.submitForm("/oauth/authorize", consentForm(clientId, verifier) { append("decision", "approve"); append("password", PASSWORD) })
        return java.net.URLDecoder.decode(Regex("code=([^&]+)").find(r.headers[HttpHeaders.Location]!!)!!.groupValues[1], "UTF-8")
    }

    private suspend fun Kit.token(vararg p: Pair<String, String>): HttpResponse =
        http.submitForm("/oauth/token", parameters { p.forEach { (k, v) -> append(k, v) } })

    private fun streamed(size: Int) = object : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.Application.Json
        override suspend fun writeTo(channel: ByteWriteChannel) {
            val chunk = "x".repeat(8 * 1024)
            repeat(size / chunk.length + 1) { channel.writeStringUtf8(chunk) }
        }
    }

    // ---- request bodies ------------------------------------------------------------------

    @Test
    fun oversizedBodiesAreRefusedWith413() = serverTest {
        val token = connect().str("access_token")
        // Declared length over the cap: refused before reading, as JSON for API routes.
        val mcp = mcpRaw(token, " ".repeat(BodyLimits.MCP + 1))
        assertEquals(HttpStatusCode.PayloadTooLarge, mcp.status)
        assertContains(mcp.bodyAsText(), "\"too_large\"")
        val phone = pairDevice()
        val events = http.post("/v1/events") {
            bearerAuth(phone.token); contentType(ContentType.Application.Json); setBody(" ".repeat(BodyLimits.EVENTS + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, events.status)
        val register = http.post("/oauth/register") { contentType(ContentType.Application.Json); setBody(" ".repeat(BodyLimits.FORM + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, register.status)
        // Browser forms get a page.
        val login = http.submitForm("/login", parameters { append("password", "x".repeat(BodyLimits.FORM)) })
        assertEquals(HttpStatusCode.PayloadTooLarge, login.status)
        assertContains(login.bodyAsText(), "<h1>")
        // No Content-Length (chunked): the read itself is capped.
        val chunked = http.post("/mcp") { bearerAuth(token); setBody(streamed(BodyLimits.MCP + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        // Within the caps everything still works.
        assertEquals(HttpStatusCode.OK, mcpRaw(token, ping).status)
    }

    // ---- headers ---------------------------------------------------------------------------

    @Test
    fun securityHeadersOnConsentAndDashboard() = serverTest {
        val clientId = register()
        val consent = http.get(
            "/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=$CLAUDE_REDIRECT" +
                "&code_challenge=${Secrets.pkceChallenge("v".repeat(50))}&code_challenge_method=S256",
        )
        val cookie = login()
        val dashboard = http.get("/") { header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie") }
        for (r in listOf(consent, dashboard, http.get("/health"))) {
            assertEquals("DENY", r.headers["X-Frame-Options"])
            assertContains(r.headers["Content-Security-Policy"]!!, "frame-ancestors 'none'")
            assertFalse("form-action" in r.headers["Content-Security-Policy"]!!) // the consent redirect must not be blocked
            assertEquals("nosniff", r.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", r.headers["Referrer-Policy"])
        }
        // The consent page names where the code goes.
        assertContains(consent.bodyAsText(), "<strong><code>claude.ai</code></strong>")
    }

    @Test
    fun logoutCookieMatchesTheLoginCookieAndNextIsLocal() = serverTest {
        val cookie = login()
        val out = http.submitForm("/logout", parameters { }) { header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie") }
        val set = out.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith(Sessions.COOKIE) }
        assertContains(set, "Secure"); assertContains(set, "HttpOnly"); assertContains(set, "SameSite=Lax")
        assertEquals("/quests", localPath("/quests"))
        for (bad in listOf("//evil.example", "/\\evil.example", "/\t/evil.example", "https://evil.example", "/ x")) assertNull(localPath(bad), bad)
        val r = http.submitForm("/login", parameters { append("password", PASSWORD); append("next", "/\\evil.example") })
        assertEquals("/", r.headers[HttpHeaders.Location])
    }

    // ---- OAuth -----------------------------------------------------------------------------

    @Test
    fun clientCapCountsOnlyApprovedClients_andUnusedOnesArePurged() = serverTest {
        val oauth = services.oauth
        val body = { name: String -> ToolJson.parseToJsonElement("""{"redirect_uris":["$CLAUDE_REDIRECT"],"client_name":"$name"}""").jsonObject }
        connect("Claude") // approved, with a live refresh token
        val live = services.oauth.connections(services.config.userId).single().clientId
        // Open registration alone never fills the cap.
        val ids = List(OAuth.MAX_CLIENTS + 5) { oauth.register(body("Claude $it")).str("client_id") }
        // Approved clients do.
        ids.take(OAuth.MAX_CLIENTS - 1).forEach { id ->
            oauth.issueCode(services.config.userId, oauth.parseAuthorize(mapOf("client_id" to id, "response_type" to "code", "code_challenge" to "a".repeat(43), "code_challenge_method" to "S256")))
        }
        val full = runCatching { oauth.register(body("One more")) }.exceptionOrNull()
        assertTrue(full is OAuthError && "too many" in full.description, full.toString())
        // A day later: never-approved clients and approved ones without a live token are gone.
        clock.now += OAuth.UNAPPROVED_TTL_MS + 1
        oauth.register(body("Fresh"))
        assertEquals(2, services.db.one("SELECT COUNT(*) FROM oauth_clients") { it.getInt(1) }) // the live connector + Fresh
        assertNull(oauth.client(ids.last()))
        assertTrue(oauth.client(live)!!.approved)
    }

    @Test
    fun unapprovedRegistrationsAreCapped_oldestEvictedAfterTheGrace() = serverTest {
        val oauth = services.oauth
        fun body(name: String) = ToolJson.parseToJsonElement("""{"redirect_uris":["$CLAUDE_REDIRECT"],"client_name":"$name"}""").jsonObject
        fun unapproved() = services.db.one("SELECT COUNT(*) FROM oauth_clients WHERE approved_at IS NULL") { it.getInt(1) }
        connect("Claude") // approved: never evicted
        val ids = List(OAuth.MAX_UNAPPROVED) { clock.now += 1; oauth.register(body("Flood $it")).str("client_id") }
        // All within the grace period: the newcomer waits rather than evicting one being approved.
        val full = runCatching { oauth.register(body("Late")) }.exceptionOrNull()
        assertTrue(full is OAuthError && full.error == "temporarily_unavailable" && full.status == 429, full.toString())
        val http429 = http.post("/oauth/register") { contentType(ContentType.Application.Json); setBody(body("Late").toString()) }
        assertEquals(HttpStatusCode.TooManyRequests, http429.status)
        assertContains(http429.bodyAsText(), "temporarily_unavailable")
        // Once the grace has passed, the oldest makes room; the count stays at the cap.
        clock.now += OAuth.UNAPPROVED_GRACE_MS
        val fresh = oauth.register(body("Fresh")).str("client_id")
        assertEquals(OAuth.MAX_UNAPPROVED, unapproved())
        assertNull(oauth.client(ids.first()))
        assertTrue(oauth.client(ids[1]) != null && oauth.client(fresh) != null)
        assertEquals(1, services.oauth.connections(services.config.userId).size)
    }

    @Test
    fun redirectUrisAreLimitedInLength() = serverTest {
        fun body(uri: String) = ToolJson.parseToJsonElement("""{"redirect_uris":["$uri"],"client_name":"X"}""").jsonObject
        val base = "https://claude.ai/"
        services.oauth.register(body(base + "a".repeat(OAuth.MAX_REDIRECT_URI - base.length)))
        val long = runCatching { services.oauth.register(body(base + "a".repeat(OAuth.MAX_REDIRECT_URI - base.length + 1))) }.exceptionOrNull()
        assertTrue(long is OAuthError && long.error == "invalid_redirect_uri", long.toString())
    }

    @Test
    fun reauthorizingClientSurvivesHousekeeping() = serverTest {
        val verifier = "r".repeat(50)
        val a = register("Claude")
        token("grant_type" to "authorization_code", "code" to approvedCode(a, verifier), "client_id" to a, "code_verifier" to verifier)
        // Its grant lapses, and a day passes: it is purgeable...
        services.db.update("UPDATE oauth_tokens SET revoked_at = ?", clock.now)
        clock.now += OAuth.UNAPPROVED_TTL_MS + 1
        // ...until the owner approves it again; a registration in between doesn't remove it.
        val code = approvedCode(a, verifier)
        register("Someone else")
        assertEquals(HttpStatusCode.OK, token("grant_type" to "authorization_code", "code" to code, "client_id" to a, "code_verifier" to verifier).status)
    }

    @Test
    fun tokensAreBoundEvenWithoutResource_andOldUnboundOnesStillWork() = serverTest {
        val verifier = "u".repeat(50)
        val a = register("Claude")
        val noResource = http.submitForm(
            "/oauth/authorize",
            parameters {
                append("response_type", "code"); append("client_id", a); append("redirect_uri", CLAUDE_REDIRECT); append("state", "s")
                append("code_challenge", Secrets.pkceChallenge(verifier)); append("code_challenge_method", "S256")
                append("decision", "approve"); append("password", PASSWORD)
            },
        )
        val code = java.net.URLDecoder.decode(Regex("code=([^&]+)").find(noResource.headers[HttpHeaders.Location]!!)!!.groupValues[1], "UTF-8")
        val t = ToolJson.parseToJsonElement(token("grant_type" to "authorization_code", "code" to code, "client_id" to a, "code_verifier" to verifier).bodyAsText()).jsonObject
        assertEquals(setOf(services.config.mcpUrl), services.db.query("SELECT resource FROM oauth_tokens") { it.getString(1) }.toSet())
        // Tokens issued before binding was mandatory are still accepted, and bound on rotation.
        services.db.update("UPDATE oauth_tokens SET resource = NULL")
        assertEquals(HttpStatusCode.OK, mcpRaw(t.str("access_token"), ping).status)
        assertEquals(HttpStatusCode.OK, token("grant_type" to "refresh_token", "refresh_token" to t.str("refresh_token"), "client_id" to a).status)
        assertEquals(2, services.db.one("SELECT COUNT(*) FROM oauth_tokens WHERE resource = ?", services.config.mcpUrl) { it.getInt(1) })
    }

    @Test
    fun disconnectDeletesTheClient() = serverTest {
        val token = connect("Claude").str("access_token")
        val clientId = services.oauth.connections(services.config.userId).single().clientId
        val cookie = login()
        val token2 = csrf(cookie)
        http.submitForm("/connections/revoke", parameters { append("csrf", token2); append("id", clientId) }) {
            header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie")
        }
        assertNull(services.oauth.client(clientId))
        assertEquals(0, services.db.one("SELECT COUNT(*) FROM oauth_tokens WHERE client_id = ?", clientId) { it.getInt(1) })
        assertEquals(HttpStatusCode.Unauthorized, mcpRaw(token, ping).status)
    }

    @Test
    fun unapprovedClientsNeverRedirect() = serverTest {
        val evil = register("Helper", "https://evil.example/cb")
        val bad = http.get("/oauth/authorize?response_type=token&client_id=$evil&redirect_uri=https://evil.example/cb")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertNull(bad.headers[HttpHeaders.Location])
        val deny = http.submitForm("/oauth/authorize", consentForm(evil, "v".repeat(50), "https://evil.example/cb") { append("decision", "deny") })
        assertNull(deny.headers[HttpHeaders.Location])
    }

    @Test
    fun vendorNamesNeedVendorRedirects() = serverTest {
        suspend fun nameOf(name: String, redirect: String): String {
            val r = http.post("/oauth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"redirect_uris":["$redirect"],"client_name":"$name"}""")
            }
            return ToolJson.parseToJsonElement(r.bodyAsText()).jsonObject.str("client_name")
        }
        assertEquals("Claude", nameOf("Claude", CLAUDE_REDIRECT))
        assertEquals("ChatGPT", nameOf("ChatGPT", "https://chatgpt.com/connector_platform_oauth_redirect"))
        assertEquals("Claude Code", nameOf("Claude Code", "http://localhost:33418/callback"))
        assertEquals("Cursor", nameOf("Cursor", "https://evil.example/cb"))
        val spoof = nameOf("Claude", "https://evil.example/cb")
        assertEquals("Unverified client (evil.example)", spoof)
        assertEquals(ProposalSource.OTHER, ProposalSource.fromClientName(spoof))
        // A host that itself contains a vendor word isn't used either.
        val sneaky = nameOf("OpenAI", "https://openai.evil.example/cb")
        assertEquals(ProposalSource.OTHER, ProposalSource.fromClientName(sneaky))
        assertEquals("Unverified client", sneaky)
        // Only ASCII survives: look-alikes and invisible characters can't dodge the check.
        val cyrillic = nameOf("\u0421laude", "https://evil.example/cb") // "Сlaude"
        assertEquals("laude", cyrillic)
        assertEquals("Unverified client (evil.example)", nameOf("Clau\u200Bde", "https://evil.example/cb"))
        assertEquals("AI client", services.oauth.clientName("\uFF23\uFF4C\uFF41\uFF55\uFF44\uFF45")) // fullwidth
        // One foreign redirect is enough to lose the name.
        assertEquals(ProposalSource.OTHER, ProposalSource.fromClientName(services.oauth.verifiedName("Claude", listOf(CLAUDE_REDIRECT, "https://evil.example/cb"))))
    }

    @Test
    fun consentWithASessionButAWrongCsrfNeedsThePassword() = serverTest {
        val clientId = register()
        val cookie = login()
        val r = http.submitForm("/oauth/authorize", consentForm(clientId, "v".repeat(50)) { append("decision", "approve"); append("csrf", "forged") }) {
            header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertNull(r.headers[HttpHeaders.Location])
        // With the right token the session is enough.
        val right = csrf(cookie)
        val ok = http.submitForm("/oauth/authorize", consentForm(clientId, "v".repeat(50)) { append("decision", "approve"); append("csrf", right) }) {
            header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie")
        }
        assertContains(ok.headers[HttpHeaders.Location]!!, "code=")
    }

    @Test
    fun refreshTokensAndCodesAreBoundToTheirClient_andCodesExpire() = serverTest {
        val verifier = "w".repeat(50)
        val a = register("Claude")
        val b = register("Claude")
        val tokens = ToolJson.parseToJsonElement(
            token("grant_type" to "authorization_code", "code" to approvedCode(a, verifier), "client_id" to a, "code_verifier" to verifier).bodyAsText(),
        ).jsonObject
        val stolen = token("grant_type" to "refresh_token", "refresh_token" to tokens.str("refresh_token"), "client_id" to b)
        assertEquals(HttpStatusCode.BadRequest, stolen.status)
        assertContains(stolen.bodyAsText(), "another client")
        // The rightful client can still refresh.
        assertEquals(HttpStatusCode.OK, token("grant_type" to "refresh_token", "refresh_token" to tokens.str("refresh_token"), "client_id" to a).status)

        val late = approvedCode(a, verifier)
        clock.now += OAuth.CODE_TTL_MS + 1
        val expired = token("grant_type" to "authorization_code", "code" to late, "client_id" to a, "code_verifier" to verifier)
        assertEquals(HttpStatusCode.BadRequest, expired.status)
        assertContains(expired.bodyAsText(), "expired")
    }

    @Test
    fun tokensAreBoundToTheMcpResource() = serverTest {
        val t = connect()
        assertEquals(services.config.mcpUrl, services.db.one("SELECT resource FROM oauth_tokens WHERE kind = 'refresh'") { it.getString(1) })
        // A token minted for another audience (e.g. before PUBLIC_URL changed) is refused.
        services.db.update("UPDATE oauth_tokens SET resource = 'https://old.example/mcp'")
        assertEquals(HttpStatusCode.Unauthorized, mcpRaw(t.str("access_token"), ping).status)
    }

    // ---- rate limits -------------------------------------------------------------------------

    @Test
    fun clientAddressHonoursTrustProxyFromTheRight() {
        assertEquals("10.0.0.1", clientAddress("10.0.0.1", listOf("6.6.6.6"), 0))
        assertEquals("1.2.3.4", clientAddress("10.0.0.1", listOf("6.6.6.6, 1.2.3.4"), 1))
        assertEquals("6.6.6.6", clientAddress("10.0.0.1", listOf("6.6.6.6", "1.2.3.4"), 2))
        assertEquals("10.0.0.1", clientAddress("10.0.0.1", listOf("1.2.3.4"), 2))
        assertEquals("10.0.0.1", clientAddress("10.0.0.1", emptyList(), 1))
    }

    @Test
    fun forwardedForIsIgnoredWithoutTrustProxy() = serverTest {
        repeat(10) { i ->
            val r = http.submitForm("/login", parameters { append("password", "wrong") }) { header(HttpHeaders.XForwardedFor, "9.9.9.$i") }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        val r = http.submitForm("/login", parameters { append("password", PASSWORD) }) { header(HttpHeaders.XForwardedFor, "8.8.8.8") }
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
    }

    @Test
    fun trustedProxyKeysOnTheForwardedClient() = serverTest(configure = { it.copy(trustProxy = 1) }) {
        suspend fun attempt(xff: String, pw: String = "wrong") =
            http.submitForm("/login", parameters { append("password", pw) }) { header(HttpHeaders.XForwardedFor, xff) }.status
        repeat(10) { assertEquals(HttpStatusCode.Unauthorized, attempt("1.1.1.1")) }
        assertEquals(HttpStatusCode.TooManyRequests, attempt("1.1.1.1"))
        // A forged entry on the left doesn't change the key.
        assertEquals(HttpStatusCode.TooManyRequests, attempt("2.2.2.2, 1.1.1.1"))
        assertEquals(HttpStatusCode.Found, attempt("2.2.2.2", PASSWORD))
    }

    @Test
    fun failedPasswordsHaveAGlobalCeiling_butASignedInOwnerCarriesOn() = serverTest(configure = { it.copy(trustProxy = 1) }) {
        val cookie = login()
        val csrfToken = csrf(cookie)
        repeat(FAILED_PASSWORDS_PER_HOUR) { i ->
            val r = http.submitForm("/login", parameters { append("password", "wrong") }) { header(HttpHeaders.XForwardedFor, "10.1.${i / 200}.${i % 200}") }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        val blocked = http.submitForm("/login", parameters { append("password", PASSWORD) }) { header(HttpHeaders.XForwardedFor, "10.9.9.9") }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertContains(blocked.bodyAsText(), "restart the server") // the owner's way out
        // The owner's session still approves connectors.
        val clientId = register()
        val ok = http.submitForm("/oauth/authorize", consentForm(clientId, "v".repeat(50)) { append("decision", "approve"); append("csrf", csrfToken) }) {
            header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie")
        }
        assertContains(ok.headers[HttpHeaders.Location]!!, "code=")
        clock.now += 3_600_001
        assertEquals(HttpStatusCode.Found, http.submitForm("/login", parameters { append("password", PASSWORD) }) { header(HttpHeaders.XForwardedFor, "10.9.9.9") }.status)
    }

    @Test
    fun forwardedForCountsOnlyFromAProxyPeer() {
        // A peer on the internet can't pick its key, even with TRUST_PROXY set...
        for (pub in listOf("203.0.113.5", "8.8.8.8", "172.32.0.1", "2001:db8::1", "::ffff:8.8.8.8", "evil.example")) {
            assertEquals(pub, clientAddress(pub, listOf("1.2.3.4"), 1), pub)
        }
        // ...a proxy on loopback, a private or Docker network, or link-local can.
        for (proxy in listOf("127.0.0.1", "::1", "10.0.0.1", "172.17.0.1", "192.168.1.10", "169.254.1.1", "fd00::1", "fe80::1%eth0", "[::1]", "localhost")) {
            assertEquals("1.2.3.4", clientAddress(proxy, listOf("1.2.3.4"), 1), proxy)
        }
    }

    @Test
    fun ipv6ClientsAreKeyedByTheirSlash64() {
        assertEquals("2001:db8:1:2::/64", rateLimitKey("2001:db8:1:2:3:4:5:6"))
        assertEquals(rateLimitKey("2001:db8:1:2:3:4:5:6"), rateLimitKey("[2001:db8:1:2:ffff::1]"))
        assertTrue(rateLimitKey("2001:db8:1:2::1") != rateLimitKey("2001:db8:1:3::1"))
        assertEquals("1.2.3.4", rateLimitKey("1.2.3.4"))
        assertEquals(64, rateLimitKey("x".repeat(500)).length)
    }

    @Test
    fun loginLimitCoversTheWholeSlash64() = serverTest(configure = { it.copy(trustProxy = 1) }) {
        repeat(10) { i ->
            val r = http.submitForm("/login", parameters { append("password", "wrong") }) { header(HttpHeaders.XForwardedFor, "2001:db8:5:6::$i") }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        val same = http.submitForm("/login", parameters { append("password", PASSWORD) }) { header(HttpHeaders.XForwardedFor, "2001:db8:5:6:ffff::1") }
        assertEquals(HttpStatusCode.TooManyRequests, same.status)
        val other = http.submitForm("/login", parameters { append("password", PASSWORD) }) { header(HttpHeaders.XForwardedFor, "2001:db8:5:7::1") }
        assertEquals(HttpStatusCode.Found, other.status)
    }

    @Test
    fun concurrentWrongPasswordsCantOvershootTheCeiling() = serverTest(configure = { it.copy(trustProxy = 1) }) {
        val statuses = kotlinx.coroutines.coroutineScope {
            List(FAILED_PASSWORDS_PER_HOUR + 40) { i ->
                async {
                    http.submitForm("/login", parameters { append("password", "wrong") }) { header(HttpHeaders.XForwardedFor, "10.2.${i / 200}.${i % 200}") }.status
                }
            }.awaitAll()
        }
        assertEquals(FAILED_PASSWORDS_PER_HOUR, statuses.count { it == HttpStatusCode.Unauthorized })
        assertEquals(40, statuses.count { it == HttpStatusCode.TooManyRequests })
    }

    @Test
    fun refundGivesBackTheNewestHit() {
        val limiter = RateLimiter { 0L }
        assertNull(limiter.hit("k", 1, 1_000))
        assertTrue(limiter.hit("k", 1, 1_000) != null)
        limiter.refund("k")
        assertNull(limiter.hit("k", 1, 1_000))
        limiter.refund("unknown") // no-op
    }

    @Test
    fun rateLimiterForgetsExpiredKeys() {
        var now = 0L
        val limiter = RateLimiter { now }
        repeat(5_000) { limiter.hit("k$it", 10, 1_000) }
        assertTrue(limiter.size() <= 5_000)
        now += RateLimiter.SWEEP_INTERVAL_MS
        limiter.hit("fresh", 10, 1_000)
        assertTrue(limiter.size() < RateLimiter.SWEEP_MIN, "size ${limiter.size()}")
        // Limits still hold.
        repeat(3) { assertNull(limiter.hit("x", 3, 1_000)) }
        assertTrue(limiter.hit("x", 3, 1_000) != null)
    }

    // ---- MCP ---------------------------------------------------------------------------------

    @Test
    fun jsonRpcErrorsAreAnsweredPerMessage() = serverTest {
        val token = connect().str("access_token")
        suspend fun raw(body: String) = ToolJson.parseToJsonElement(mcpRaw(token, body).bodyAsText())
        fun code(e: kotlinx.serialization.json.JsonElement) = e.jsonObject["error"]!!.jsonObject.str("code").toInt()

        val empty = mcpRaw(token, "[]")
        assertEquals(-32600, code(ToolJson.parseToJsonElement(empty.bodyAsText())))
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val huge = mcpRaw(token, (1..McpServer.MAX_BATCH + 1).joinToString(",", "[", "]") { ping })
        assertEquals(-32600, code(ToolJson.parseToJsonElement(huge.bodyAsText())))
        assertEquals(-32600, code(raw("""{"id":1,"method":"ping"}"""))) // no jsonrpc
        assertEquals(-32600, code(raw("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")))
        assertEquals(-32600, code(raw("""{"jsonrpc":"2.0","id":1}"""))) // id but no method
        assertEquals(-32600, code(raw("""{"jsonrpc":"2.0","id":{"x":1},"method":"ping"}""")))
        assertEquals(-32602, code(raw("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":[1]}""")))
        assertEquals(-32602, code(raw("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":{"x":1}}}""")))
        assertEquals(-32602, code(raw("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_today","arguments":[1]}}""")))
        assertEquals(-32602, code(raw("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nope"}}""")))
        // initialize with a non-string version falls back instead of failing.
        val init = raw("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":{"v":1}}}""")
        assertEquals("2025-06-18", init.jsonObject["result"]!!.jsonObject.str("protocolVersion"))
        // One bad message in a batch doesn't fail the rest.
        val batch = raw(
            """[{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":[]}},{"jsonrpc":"2.0","id":2,"method":"ping"},"junk"]""",
        ).jsonArray
        assertEquals(3, batch.size)
        assertEquals(-32602, code(batch[0]))
        assertTrue("result" in batch[1].jsonObject)
        assertEquals(-32600, code(batch[2]))
    }

    @Test
    fun everyMcpRequestIsLogged_andTheLogIsPruned() = serverTest {
        val token = connect().str("access_token")
        rpc(token, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") })
        rpc(token, "ping")
        rpc(token, "tools/list")
        tool(token, "get_stats")
        val methods = services.mcp.recentCalls(services.config.userId).map { it.method }.toSet()
        assertEquals(setOf("initialize", "ping", "tools/list", "tools/call"), methods)
        repeat((McpServer.KEEP_CALLS + 100) / McpServer.MAX_BATCH + 1) { batch ->
            val many = JsonArray(List(McpServer.MAX_BATCH) { buildJsonObject { put("jsonrpc", "2.0"); put("id", batch * 1000 + it); put("method", "ping") } })
            assertEquals(HttpStatusCode.OK, mcpRaw(token, many.toString()).status)
        }
        val count = services.mcp.callCount(services.config.userId)
        assertTrue(count <= McpServer.KEEP_CALLS + McpServer.PRUNE_EVERY, "count $count")
    }

    @Test
    fun everyMcpMethodIsRateLimited() = serverTest(configure = { it.copy(mcpCallsPerMinute = 3) }) {
        val token = connect().str("access_token")
        rpc(token, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") })
        rpc(token, "tools/list")
        assertTrue("result" in rpc(token, "ping"))
        for (method in listOf("ping", "tools/list", "initialize", "nope/unknown")) {
            val r = rpc(token, method)
            assertEquals(McpServer.RATE_LIMITED, r["error"]!!.jsonObject.str("code").toInt(), method)
            assertContains(r["error"]!!.jsonObject.str("message"), "Too many calls")
        }
        // tools/call stays a tool error, which clients hand to the model.
        val (err, msg) = tool(token, "get_stats")
        assertTrue(err)
        assertContains(msg.jsonPrimitive.content, "Too many calls")
        // Notifications cost nothing and are still accepted.
        assertEquals(HttpStatusCode.Accepted, mcpRaw(token, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        val limited = services.mcp.recentCalls(services.config.userId).filter { it.detail == "rate limited" }
        assertEquals(setOf("ping", "tools/list", "initialize", "nope/unknown", "tools/call"), limited.map { it.method }.toSet())
        clock.now += 60_001
        assertTrue("result" in rpc(token, "ping"))
    }

    @Test
    fun internalErrorsAreLogged() = serverTest {
        val token = connect().str("access_token")
        services.db.update("DROP TABLE events") // the next fold fails
        val r = rpc(token, "tools/call", buildJsonObject { put("name", "get_stats") })
        assertEquals(McpServer.INTERNAL_ERROR, r["error"]!!.jsonObject.str("code").toInt())
        val call = services.mcp.recentCalls(services.config.userId).first()
        assertEquals("tools/call", call.method)
        assertEquals("get_stats", call.tool)
        assertFalse(call.ok)
        assertEquals("internal error", call.detail)
    }

    @Test
    fun stepMustBelongToTheQuest() = serverTest {
        val phone = pairDevice()
        val (q1, q2) = Ids.newId(clock.now) to Ids.newId(clock.now + 1)
        val s2 = Ids.newId(clock.now + 2)
        phone.push(
            Events.questCreated(QuestCreated(q1, "One")),
            Events.questStatusSet(QuestStatusSet(q1, QuestStatus.TRACKED, NextStep(Ids.newId(clock.now + 3), q1, "a"))),
            Events.questCreated(QuestCreated(q2, "Two")),
            Events.questStatusSet(QuestStatusSet(q2, QuestStatus.TRACKED, NextStep(s2, q2, "b"))),
        )
        val token = connect().str("access_token")
        val (err, msg) = tool(token, "propose_complete_step", args { put("quest_id", q1); put("step_id", s2); put("reason", "r") })
        assertTrue(err)
        assertContains(msg.jsonPrimitive.content, "belongs to quest $q2")
        assertTrue(tool(token, "propose_schedule_step", args { put("quest_id", q1); put("step_id", "nope"); put("due_at", null as String?); put("reason", "r") }).first)
        assertFalse(tool(token, "propose_complete_step", args { put("quest_id", q2); put("step_id", s2); put("reason", "r") }).first)
    }

    // ---- sync ----------------------------------------------------------------------------------

    @Test
    fun eventsOfDeadOrUnknownDevicesCanBePushed_butNotTheServers() = serverTest {
        val phone = pairDevice("Phone")
        val old = pairDevice("Old phone")
        val cookie = login()
        val csrfToken = csrf(cookie)
        http.submitForm("/devices/revoke", parameters { append("csrf", csrfToken); append("id", old.deviceId) }) {
            header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie")
        }
        val revoked = old.factory.create("quest.created", Events.questCreated(QuestCreated(Ids.newId(clock.now), "From the old phone")).payload)
        val stranger = dev.dreamweave.quest.core.EventFactory(Ids.newId(clock.now), clock = { clock.now })
            .create("quest.created", Events.questCreated(QuestCreated(Ids.newId(clock.now), "From a snapshot")).payload)
        val r = phone.pushEvents(listOf(revoked, stranger))
        assertEquals(2, r.accepted, r.rejected.toString())
        // The server's own device id stays reserved.
        val token = connect().str("access_token")
        assertFalse(tool(token, "propose_add_quest", args { put("title", "T"); put("reason", "r") }).first)
        val serverDevice = services.db.one("SELECT value FROM meta WHERE key = 'server_device_id'") { it.getString(1) }!!
        val forged = Event(Ids.newId(clock.now), serverDevice, 999, clock.now, "user", "quest.created", revoked.payload)
        assertEquals(0, phone.pushEvents(listOf(forged)).accepted)
    }
}
