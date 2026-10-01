// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.protocol.ApiError
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PushRequest
import dev.dreamweave.quest.protocol.SyncApi
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Base64

/** Everything the routes need; exposed for tests. */
class Services(val config: Config, val db: Db, val clock: () -> Long) {
    val store = EventStore(db, config, clock)
    val devices = Devices(db, config, clock)
    val sessions = Sessions(db, config, clock)
    val oauth = OAuth(db, config, clock)
    val limiter = RateLimiter(clock)
    val tools = McpTools(store, config, clock)
    val mcp = McpServer(tools, config, db, limiter, clock)
}

fun main() {
    val config = Config.fromEnv()
    LoggerFactory.getLogger("server").info("Starting {} server on port {} (public URL {})", config.appName, config.port, config.publicUrl)
    embeddedServer(CIO, port = config.port, host = "0.0.0.0") { questModule(config) }.start(wait = true)
}

fun Application.questModule(config: Config, clock: () -> Long = { System.currentTimeMillis() }): Services {
    val s = Services(config, Db(config.dbFile), clock)
    val log = LoggerFactory.getLogger("server")

    install(ContentNegotiation) { json(ToolJson) }
    install(CallLogging) {
        // Paths only: never log query strings (codes, tokens) or bodies.
        format { call -> "${call.request.local.method.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", "Something went wrong on the server."))
        }
    }

    routing {
        get("/health") { call.respond(buildJsonObject { put("ok", true); put("name", config.appName) }) }

        // ---- OAuth 2.1 for the MCP connector --------------------------------------------
        get("/.well-known/oauth-protected-resource") { call.respond(s.oauth.protectedResourceMetadata()) }
        get("/.well-known/oauth-protected-resource/mcp") { call.respond(s.oauth.protectedResourceMetadata()) }
        get("/.well-known/oauth-authorization-server") { call.respond(s.oauth.authorizationServerMetadata()) }

        post("/oauth/register") {
            if (s.limiter.hit("register:${call.request.origin.remoteHost}", 20, 3_600_000) != null) {
                return@post call.respond(HttpStatusCode.TooManyRequests, oauthError("invalid_client_metadata", "too many registrations, try later"))
            }
            val body = runCatching { ToolJson.parseToJsonElement(call.receiveText()) as JsonObject }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, oauthError("invalid_client_metadata", "expected a JSON object"))
            try {
                call.respond(HttpStatusCode.Created, s.oauth.register(body))
            } catch (e: OAuthError) {
                call.respond(HttpStatusCode.BadRequest, oauthError(e.error, e.description))
            }
        }

        get("/oauth/authorize") {
            val params = call.request.queryParameters.toMap()
            val req = try {
                s.oauth.parseAuthorize(params)
            } catch (e: OAuthError) {
                return@get authorizeError(call, s, params, e)
            }
            val session = s.sessions.get(call.request.cookies[Sessions.COOKIE])
            call.respondText(consentPage(config, req, params, session, error = null), ContentType.Text.Html)
        }

        post("/oauth/authorize") {
            val form = call.receiveParameters().toMap()
            val req = try {
                s.oauth.parseAuthorize(form)
            } catch (e: OAuthError) {
                return@post authorizeError(call, s, form, e)
            }
            if (form["decision"] != "approve") {
                return@post call.respondRedirect(redirectWith(req.redirectUri, "error" to "access_denied", "state" to req.state))
            }
            val session = s.sessions.get(call.request.cookies[Sessions.COOKIE])
            val viaSession = session != null && form["csrf"] == session.csrf
            if (!viaSession) {
                if (s.limiter.hit("login:${call.request.origin.remoteHost}", 10, 600_000) != null) {
                    return@post call.respondText(consentPage(config, req, form, null, "Too many attempts. Wait a few minutes."), ContentType.Text.Html, HttpStatusCode.TooManyRequests)
                }
                if (!s.sessions.checkPassword(form["password"].orEmpty())) {
                    return@post call.respondText(consentPage(config, req, form, null, "Wrong password."), ContentType.Text.Html, HttpStatusCode.Unauthorized)
                }
            }
            val code = s.oauth.issueCode(config.userId, req)
            log.info("Connector authorized: client=\"{}\"", req.client.name)
            call.respondRedirect(redirectWith(req.redirectUri, "code" to code, "state" to req.state))
        }

        post("/oauth/token") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val form = call.receiveParameters().toMap()
            try {
                call.respond(s.oauth.token(form, basicAuth(call)))
            } catch (e: OAuthError) {
                call.respond(HttpStatusCode.fromValue(e.status), oauthError(e.error, e.description))
            }
        }

        post("/oauth/revoke") {
            call.receiveParameters()["token"]?.let(s.oauth::revoke)
            call.respond(HttpStatusCode.OK, buildJsonObject { })
        }

        // ---- sync API --------------------------------------------------------------------
        post(SyncApi.PAIR) {
            if (s.limiter.hit("pair:${call.request.origin.remoteHost}", 10, 600_000) != null) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "Too many attempts. Wait a few minutes."))
            }
            val req = runCatching { call.receive<PairRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Expected code, deviceId and deviceName."))
            val res = s.devices.pair(req)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_code", "That pairing code is wrong or expired. Create a new one on the server page."))
            log.info("Device paired: {}", req.deviceName.take(60))
            call.respond(res)
        }

        post(SyncApi.EVENTS) {
            val device = deviceOf(call, s) ?: return@post unauthorizedDevice(call)
            val req = runCatching { call.receive<PushRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Expected {\"events\": [...]}."))
            if (req.events.size > SyncApi.MAX_PUSH) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too_many", "Push at most ${SyncApi.MAX_PUSH} events at once."))
            }
            call.respond(s.store.push(device.userId, device.deviceId, req.events))
        }

        get(SyncApi.EVENTS) {
            val device = deviceOf(call, s) ?: return@get unauthorizedDevice(call)
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: SyncApi.MAX_PULL
            call.respond(s.store.pull(device.userId, after, limit))
        }

        // ---- MCP -------------------------------------------------------------------------
        post("/mcp") {
            val origin = call.request.headers[HttpHeaders.Origin]
            if (origin != null && !originAllowed(origin, config)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiError("forbidden_origin", "Origin not allowed."))
            }
            val token = bearer(call)
            val grant = token?.let(s.oauth::authenticate)
            if (grant == null) {
                call.response.header(
                    HttpHeaders.WWWAuthenticate,
                    "Bearer resource_metadata=\"${config.publicUrl}/.well-known/oauth-protected-resource\"" +
                        if (token != null) ", error=\"invalid_token\"" else "",
                )
                return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Connect this server as a connector to sign in."))
            }
            val version = call.request.headers["MCP-Protocol-Version"]
            if (version != null && version !in McpServer.SUPPORTED_VERSIONS) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("unsupported_protocol_version", "Supported: ${McpServer.SUPPORTED_VERSIONS}"))
            }
            val reply = s.mcp.handle(grant, call.receiveText())
            if (reply.body == null) {
                call.respond(HttpStatusCode.Accepted, "")
            } else {
                call.respondText(ToolJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), reply.body), ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
            }
        }
        get("/mcp") {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respond(HttpStatusCode.MethodNotAllowed, ApiError("method_not_allowed", "This server does not open SSE streams; POST JSON-RPC messages."))
        }
        delete("/mcp") {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respond(HttpStatusCode.MethodNotAllowed, ApiError("method_not_allowed", "This server is stateless; there is no session to end."))
        }

        // ---- admin pages -----------------------------------------------------------------
        get("/") {
            val session = s.sessions.get(call.request.cookies[Sessions.COOKIE])
                ?: return@get call.respondText(Web.login(config), ContentType.Text.Html)
            call.respondText(dashboard(s, session), ContentType.Text.Html)
        }

        post("/login") {
            val form = call.receiveParameters()
            if (s.limiter.hit("login:${call.request.origin.remoteHost}", 10, 600_000) != null) {
                return@post call.respondText(Web.login(config, "Too many attempts. Wait a few minutes."), ContentType.Text.Html, HttpStatusCode.TooManyRequests)
            }
            val token = s.sessions.login(form["password"].orEmpty())
                ?: return@post call.respondText(Web.login(config, "Wrong password."), ContentType.Text.Html, HttpStatusCode.Unauthorized)
            call.response.cookies.append(
                Cookie(
                    Sessions.COOKIE, token, maxAge = (Sessions.TTL_MS / 1000).toInt(), path = "/",
                    secure = config.secureCookies, httpOnly = true, extensions = mapOf("SameSite" to "Lax"),
                ),
            )
            val next = form["next"]?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
            call.respondRedirect(next)
        }

        post("/logout") {
            s.sessions.logout(call.request.cookies[Sessions.COOKIE])
            call.response.cookies.append(Cookie(Sessions.COOKIE, "", maxAge = 0, path = "/"))
            call.respondRedirect("/")
        }

        post("/pair") {
            val session = sessionWithCsrf(call, s) ?: return@post call.respondRedirect("/")
            val (code, expires) = s.devices.newPairingCode(session.userId)
            call.respondText(Web.pairing(config, code, expires), ContentType.Text.Html)
        }

        post("/devices/revoke") {
            val form = call.receiveParameters()
            val session = s.sessions.get(call.request.cookies[Sessions.COOKIE])
            if (session != null && form["csrf"] == session.csrf) form["id"]?.let { s.devices.revoke(session.userId, it) }
            call.respondRedirect("/")
        }

        post("/connections/revoke") {
            val form = call.receiveParameters()
            val session = s.sessions.get(call.request.cookies[Sessions.COOKIE])
            if (session != null && form["csrf"] == session.csrf) form["id"]?.let { s.oauth.disconnect(session.userId, it) }
            call.respondRedirect("/")
        }
    }
    return s
}

private fun Parameters.toMap(): Map<String, String?> = names().associateWith { this[it] }

private fun oauthError(error: String, description: String): JsonObject = buildJsonObject {
    put("error", error)
    put("error_description", description)
}

private fun redirectWith(uri: String, vararg params: Pair<String, String?>): String =
    URLBuilder(uri).apply { params.forEach { (k, v) -> if (v != null) parameters.append(k, v) } }.buildString()

/** Client and redirect problems are shown on the page (never redirect to an unverified URI). */
private suspend fun authorizeError(call: ApplicationCall, s: Services, p: Map<String, String?>, e: OAuthError) {
    val client = s.oauth.client(p["client_id"])
    val redirect = p["redirect_uri"] ?: client?.redirectUris?.singleOrNull()
    if (client != null && redirect != null && redirect in client.redirectUris && e.error !in setOf("invalid_client", OAuth.INVALID_REDIRECT)) {
        call.respondRedirect(redirectWith(redirect, "error" to e.error, "error_description" to e.description, "state" to p["state"]))
    } else {
        call.respondText(
            Web.page(s.config, "Can't connect", "<h1>Can't connect</h1><div class=\"card\"><p>${Web.esc(e.description)}</p></div>"),
            ContentType.Text.Html, HttpStatusCode.BadRequest,
        )
    }
}

private fun basicAuth(call: ApplicationCall): Pair<String, String>? {
    val h = call.request.headers[HttpHeaders.Authorization] ?: return null
    if (!h.startsWith("Basic ", ignoreCase = true)) return null
    val decoded = runCatching { String(Base64.getDecoder().decode(h.substring(6).trim())) }.getOrNull() ?: return null
    val i = decoded.indexOf(':').takeIf { it >= 0 } ?: return null
    return java.net.URLDecoder.decode(decoded.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(decoded.substring(i + 1), "UTF-8")
}

private fun bearer(call: ApplicationCall): String? =
    call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.ifEmpty { null }

private fun deviceOf(call: ApplicationCall, s: Services): Device? = bearer(call)?.let(s.devices::authenticate)

private suspend fun unauthorizedDevice(call: ApplicationCall) =
    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "This device isn't paired (or was removed). Pair it again from the server page."))

private suspend fun sessionWithCsrf(call: ApplicationCall, s: Services): Sessions.Session? {
    val form = call.receiveParameters()
    val session = s.sessions.get(call.request.cookies[Sessions.COOKIE]) ?: return null
    return session.takeIf { form["csrf"] == it.csrf }
}

private fun originAllowed(origin: String, config: Config): Boolean {
    val o = origin.trimEnd('/')
    val own = runCatching { URI(config.publicUrl).let { "${it.scheme}://${it.authority}" } }.getOrNull()
    return o == own || o in config.allowedOrigins
}

private fun consentPage(config: Config, req: AuthRequest, p: Map<String, String?>, session: Sessions.Session?, error: String?): String {
    val hidden = listOf("response_type", "client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "scope", "resource")
        .mapNotNull { k -> p[k]?.let { "<input type=\"hidden\" name=\"$k\" value=\"${Web.esc(it)}\">" } }
        .joinToString("")
    val auth = if (session != null) {
        "<input type=\"hidden\" name=\"csrf\" value=\"${Web.esc(session.csrf)}\">"
    } else {
        "<label for=\"pw\">Admin password</label><input id=\"pw\" name=\"password\" type=\"password\" autocomplete=\"current-password\" autofocus>"
    }
    return Web.page(
        config, "Connect ${req.client.name}",
        """<div class="meta">Connect an AI assistant</div><h1>Allow ${Web.esc(req.client.name)}?</h1>
<div class="card"><p><strong>${Web.esc(req.client.name)}</strong> wants to read your quests, steps and history, and to <strong>suggest</strong> changes.</p>
<p class="muted">It can never change anything itself: every suggestion arrives in the app as a proposal that you accept, edit or reject.</p>
<p class="muted">After approving you return to <code>${Web.esc(URI(req.redirectUri).host)}</code>.</p></div>
<form method="post" action="/oauth/authorize" class="card">$hidden$auth
${error?.let { "<p class=\"err\">${Web.esc(it)}</p>" } ?: ""}
<div class="row"><button class="primary" type="submit" name="decision" value="approve">Approve</button>
<button type="submit" name="decision" value="deny" formnovalidate>Deny</button></div></form>""",
    )
}

private fun dashboard(s: Services, session: Sessions.Session): String {
    val c = s.config
    val csrf = "<input type=\"hidden\" name=\"csrf\" value=\"${Web.esc(session.csrf)}\">"
    val devices = s.devices.list(session.userId)
    val connections = s.oauth.connections(session.userId)
    val calls = s.mcp.recentCalls(session.userId)
    val state = s.store.state(session.userId)
    val deviceRows = devices.joinToString("") { d ->
        "<tr><td>${Web.esc(d.name)}</td><td class=\"mono\">${Web.time(d.lastSeenAt, c)}</td><td><form class=\"inline\" method=\"post\" action=\"/devices/revoke\">$csrf" +
            "<input type=\"hidden\" name=\"id\" value=\"${Web.esc(d.deviceId)}\"><button type=\"submit\">Remove</button></form></td></tr>"
    }
    val connectionRows = connections.joinToString("") { k ->
        "<tr><td>${Web.esc(k.name)}</td><td class=\"mono\">${Web.time(k.lastTokenAt, c)}</td><td><form class=\"inline\" method=\"post\" action=\"/connections/revoke\">$csrf" +
            "<input type=\"hidden\" name=\"id\" value=\"${Web.esc(k.clientId)}\"><button type=\"submit\">Disconnect</button></form></td></tr>"
    }
    val callRows = calls.joinToString("") { k ->
        "<tr><td class=\"mono\">${Web.time(k.at, c)}</td><td>${Web.esc(k.client)}</td><td class=\"mono\">${Web.esc(k.tool ?: "-")}</td>" +
            "<td class=\"${if (k.ok) "ok" else "err"}\">${if (k.ok) "ok" else Web.esc(k.detail ?: "error")}</td></tr>"
    }
    return Web.page(
        c, "Server",
        """<div class="row" style="justify-content:space-between"><div class="meta">${Web.esc(c.appName)} server</div>
<form class="inline" method="post" action="/logout"><button type="submit">Sign out</button></form></div>
<h1>Your server</h1>
<div class="card"><div class="meta">Status</div><p>${state.quests.size} quests · ${state.tracked.size}/${state.slotCap} tracked · ${state.pendingProposals.size} proposals waiting · ${state.appliedCount} events</p></div>
<div class="card"><h2>Phones</h2><p class="muted">Pair the app to sync your quests and receive proposals.</p>
<table>${if (deviceRows.isEmpty()) "<tr><td class=\"muted\">No devices yet.</td></tr>" else "<tr><th>Device</th><th>Last sync</th><th></th></tr>$deviceRows"}</table>
<form method="post" action="/pair">$csrf<p><button class="primary" type="submit">Pair a device</button></p></form></div>
<div class="card"><h2>AI assistants</h2><p class="muted">In Claude (Settings → Connectors → Add custom connector) or ChatGPT (Settings → Connectors → Create), use this URL:</p>
<p><code>${Web.esc(c.mcpUrl)}</code></p><p class="muted">You'll be asked to approve here. Assistants can read and propose; you approve every change in the app.</p>
<table>${if (connectionRows.isEmpty()) "<tr><td class=\"muted\">None connected.</td></tr>" else "<tr><th>Connector</th><th>Last sign-in</th><th></th></tr>$connectionRows"}</table></div>
<div class="card"><h2>Recent assistant calls</h2><table>${if (callRows.isEmpty()) "<tr><td class=\"muted\">No calls yet.</td></tr>" else "<tr><th>When</th><th>Client</th><th>Tool</th><th>Result</th></tr>$callRows"}</table></div>""",
    )
}
