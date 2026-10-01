// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.core.ProposalSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI

/** An authenticated MCP caller: whose quests, which connector. */
data class Grant(val userId: String, val clientId: String, val clientName: String)

data class OAuthClient(
    val clientId: String,
    val userId: String,
    val name: String,
    val redirectUris: List<String>,
    val authMethod: String,
    val secretHash: String?,
    /** When the owner first approved it; until then its redirect URI is unverified (D-507). */
    val approvedAt: Long? = null,
) {
    val approved: Boolean get() = approvedAt != null
}

/** A validated /oauth/authorize request, carried through the consent page. */
data class AuthRequest(
    val client: OAuthClient,
    val redirectUri: String,
    val state: String?,
    val codeChallenge: String,
    val scope: String,
    val resource: String?,
)

class OAuthError(val error: String, val description: String, val status: Int = 400) : Exception(description)

/**
 * OAuth 2.1 authorization server for the MCP endpoint, backed by the admin password:
 * metadata (RFC 8414, RFC 9728), dynamic client registration (RFC 7591), authorization
 * code with mandatory PKCE S256, rotating refresh tokens, revocation (RFC 7009).
 * Claude and ChatGPT custom connectors use exactly this flow.
 */
class OAuth(private val db: Db, private val config: Config, private val clock: () -> Long) {
    val scope = "quests"

    fun protectedResourceMetadata(): JsonObject = buildJsonObject {
        put("resource", config.mcpUrl)
        putJsonArray("authorization_servers") { add(JsonPrimitive(config.publicUrl)) }
        putJsonArray("bearer_methods_supported") { add(JsonPrimitive("header")) }
        putJsonArray("scopes_supported") { add(JsonPrimitive(scope)) }
        put("resource_name", config.appName)
    }

    fun authorizationServerMetadata(): JsonObject = buildJsonObject {
        put("issuer", config.publicUrl)
        put("authorization_endpoint", "${config.publicUrl}/oauth/authorize")
        put("token_endpoint", "${config.publicUrl}/oauth/token")
        put("registration_endpoint", "${config.publicUrl}/oauth/register")
        put("revocation_endpoint", "${config.publicUrl}/oauth/revoke")
        putJsonArray("response_types_supported") { add(JsonPrimitive("code")) }
        putJsonArray("grant_types_supported") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
        putJsonArray("code_challenge_methods_supported") { add(JsonPrimitive("S256")) }
        putJsonArray("token_endpoint_auth_methods_supported") {
            add(JsonPrimitive("none")); add(JsonPrimitive("client_secret_post")); add(JsonPrimitive("client_secret_basic"))
        }
        putJsonArray("scopes_supported") { add(JsonPrimitive(scope)) }
    }

    // ---- dynamic client registration ---------------------------------------------------

    fun register(body: JsonObject): JsonObject {
        val uris = (body["redirect_uris"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw OAuthError("invalid_redirect_uri", "redirect_uris is required")
        if (uris.isEmpty() || uris.size > 10) throw OAuthError("invalid_redirect_uri", "between 1 and 10 redirect_uris are required")
        uris.forEach { if (!acceptableRedirect(it)) throw OAuthError("invalid_redirect_uri", "redirect URIs must be https (or http on localhost): $it") }
        val method = body.string("token_endpoint_auth_method") ?: "none"
        if (method !in setOf("none", "client_secret_post", "client_secret_basic")) {
            throw OAuthError("invalid_client_metadata", "unsupported token_endpoint_auth_method $method")
        }
        val name = verifiedName(clientName(body.string("client_name")), uris)
        val now = clock()
        purgeUnused(now)
        // Only approved clients count: open registration alone can't fill the cap.
        if ((db.one("SELECT COUNT(*) FROM oauth_clients WHERE user_id = ? AND approved_at IS NOT NULL", config.userId) { it.getInt(1) } ?: 0) >= MAX_CLIENTS) {
            throw OAuthError("invalid_client_metadata", "too many registered clients; remove unused ones on the admin page")
        }
        val clientId = "qc_" + Secrets.token(16)
        val secret = if (method == "none") null else Secrets.token()
        db.update(
            "INSERT INTO oauth_clients (client_id, user_id, client_name, redirect_uris, secret_hash, auth_method, created_at) VALUES (?,?,?,?,?,?,?)",
            clientId, config.userId, name, JsonArray(uris.map(::JsonPrimitive)).toString(), secret?.let(Secrets::sha256), method, now,
        )
        return buildJsonObject {
            put("client_id", clientId)
            if (secret != null) {
                put("client_secret", secret)
                put("client_secret_expires_at", 0)
            }
            put("client_id_issued_at", now / 1000)
            put("client_name", name)
            putJsonArray("redirect_uris") { uris.forEach { add(JsonPrimitive(it)) } }
            put("token_endpoint_auth_method", method)
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
        }
    }

    private fun acceptableRedirect(uri: String): Boolean = try {
        val u = URI(uri)
        u.fragment == null && when (u.scheme) {
            "https" -> u.host != null
            "http" -> u.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** A short display name for the connector (it also names the MCP actor, `mcp:<name>`). */
    fun clientName(raw: String?): String =
        raw?.filter { it.isLetterOrDigit() || it in " ._-()" }?.trim()?.take(40)?.ifEmpty { null } ?: "AI client"

    /**
     * The client name is self-asserted, but it becomes the consent title, the `mcp:<name>`
     * actor and the proposal's source chip. A name that reads as Claude or ChatGPT (per
     * [ProposalSource.fromClientName]) is kept only when every redirect URI is on that
     * vendor's hosts (or loopback, where the code never leaves the owner's machine);
     * otherwise it is replaced by one naming the redirect host (D-507).
     */
    fun verifiedName(name: String, redirectUris: List<String>): String {
        val vendorHosts = VENDOR_HOSTS[ProposalSource.fromClientName(name)] ?: return name
        val hosts = redirectUris.map { URI(it).host.lowercase().trim('[', ']') }
        val foreign = hosts.firstOrNull { h -> h !in LOOPBACK && vendorHosts.none { h == it || h.endsWith(".$it") } } ?: return name
        return listOf("Unverified client (${foreign.take(18)})", "Unverified client")
            .map(::clientName)
            .first { ProposalSource.fromClientName(it) == ProposalSource.OTHER }
    }

    /**
     * Registration housekeeping: never-approved clients expire after a day, and approved
     * ones with no live token for a day (disconnected, or their grant expired) are removed
     * too, so the cap only ever counts connectors that work.
     */
    private fun purgeUnused(now: Long) {
        db.tx {
            val stale = query(
                """
                SELECT client_id FROM oauth_clients c WHERE c.user_id = ? AND
                  ((c.approved_at IS NULL AND c.created_at < ?) OR
                   (c.approved_at IS NOT NULL AND c.approved_at < ? AND NOT EXISTS (
                     SELECT 1 FROM oauth_tokens t WHERE t.client_id = c.client_id AND t.revoked_at IS NULL AND t.expires_at >= ?)))
                """.trimIndent(),
                config.userId, now - UNAPPROVED_TTL_MS, now - UNAPPROVED_TTL_MS, now,
            ) { it.getString(1) }
            stale.forEach { deleteClient(config.userId, it) }
        }
    }

    private fun Db.deleteClient(userId: String, clientId: String) {
        update("DELETE FROM oauth_tokens WHERE user_id = ? AND client_id = ?", userId, clientId)
        update("DELETE FROM oauth_codes WHERE user_id = ? AND client_id = ?", userId, clientId)
        update("DELETE FROM oauth_clients WHERE user_id = ? AND client_id = ?", userId, clientId)
    }

    fun client(clientId: String?): OAuthClient? = clientId?.let {
        db.one("SELECT * FROM oauth_clients WHERE client_id = ?", it) { rs ->
            OAuthClient(
                rs.getString("client_id"), rs.getString("user_id"), rs.getString("client_name"),
                kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("redirect_uris")).let { j -> (j as JsonArray).map { e -> (e as JsonPrimitive).content } },
                rs.getString("auth_method"), rs.getString("secret_hash"),
                (rs.getObject("approved_at") as? Number)?.toLong(),
            )
        }
    }

    // ---- authorization -------------------------------------------------------------------

    /**
     * Validates an authorization request. Errors about the client or redirect URI must not
     * redirect (they are shown on the page); everything else redirects with an error.
     */
    fun parseAuthorize(p: Map<String, String?>): AuthRequest {
        val client = client(p["client_id"]) ?: throw OAuthError("invalid_client", "Unknown client. Add the connector again.")
        val redirect = p["redirect_uri"] ?: client.redirectUris.singleOrNull()
            ?: throw OAuthError(INVALID_REDIRECT, "redirect_uri is required")
        if (redirect !in client.redirectUris) throw OAuthError(INVALID_REDIRECT, "redirect_uri does not match the registered ones")
        if (p["response_type"] != "code") throw OAuthError("unsupported_response_type", "only response_type=code is supported")
        val challenge = p["code_challenge"]?.takeIf { it.length in 43..128 }
            ?: throw OAuthError("invalid_request", "PKCE code_challenge is required")
        if ((p["code_challenge_method"] ?: "plain") != "S256") throw OAuthError("invalid_request", "code_challenge_method must be S256")
        val resource = p["resource"]?.trimEnd('/')
        if (resource != null && resource != config.mcpUrl && resource != config.publicUrl) {
            throw OAuthError("invalid_target", "unknown resource $resource")
        }
        // The server's only protected resource is /mcp: tokens are bound to it (RFC 8707).
        return AuthRequest(client, redirect, p["state"], challenge, scope, resource?.let { config.mcpUrl })
    }

    /** Issues a code after the owner approved; this also marks the client approved. */
    fun issueCode(userId: String, req: AuthRequest): String {
        val code = Secrets.token()
        val now = clock()
        db.tx {
            update(
                "INSERT INTO oauth_codes (code_hash, user_id, client_id, redirect_uri, code_challenge, scope, resource, expires_at) VALUES (?,?,?,?,?,?,?,?)",
                Secrets.sha256(code), userId, req.client.clientId, req.redirectUri, req.codeChallenge, req.scope, req.resource, now + CODE_TTL_MS,
            )
            update("UPDATE oauth_clients SET approved_at = ? WHERE client_id = ? AND approved_at IS NULL", now, req.client.clientId)
        }
        return code
    }

    // ---- tokens --------------------------------------------------------------------------

    private fun authenticateClient(p: Map<String, String?>, basic: Pair<String, String>?): OAuthClient {
        val clientId = basic?.first ?: p["client_id"] ?: throw OAuthError("invalid_client", "client_id is required", 401)
        val client = client(clientId) ?: throw OAuthError("invalid_client", "unknown client", 401)
        if (client.authMethod != "none") {
            val secret = basic?.second ?: p["client_secret"] ?: throw OAuthError("invalid_client", "client authentication required", 401)
            if (!Secrets.constantTimeEquals(Secrets.sha256(secret), client.secretHash ?: "")) throw OAuthError("invalid_client", "bad client credentials", 401)
        }
        return client
    }

    fun token(p: Map<String, String?>, basic: Pair<String, String>?): JsonObject {
        val client = authenticateClient(p, basic)
        return when (p["grant_type"]) {
            "authorization_code" -> exchangeCode(client, p)
            "refresh_token" -> refresh(client, p)
            else -> throw OAuthError("unsupported_grant_type", "grant_type must be authorization_code or refresh_token")
        }
    }

    private data class CodeRow(
        val userId: String, val clientId: String, val redirect: String, val challenge: String, val resource: String?, val expires: Long, val used: Boolean,
    )

    private fun exchangeCode(client: OAuthClient, p: Map<String, String?>): JsonObject {
        val code = p["code"] ?: throw OAuthError("invalid_request", "code is required")
        val verifier = p["code_verifier"] ?: throw OAuthError("invalid_request", "code_verifier is required")
        val hash = Secrets.sha256(code)
        val now = clock()
        val row = db.one("SELECT * FROM oauth_codes WHERE code_hash = ?", hash) { rs ->
            CodeRow(
                rs.getString("user_id"), rs.getString("client_id"), rs.getString("redirect_uri"), rs.getString("code_challenge"),
                rs.getString("resource"), rs.getLong("expires_at"), rs.getObject("used_at") != null,
            )
        } ?: throw OAuthError("invalid_grant", "unknown authorization code")
        if (row.used) replayed(row, now)
        if (row.expires < now) throw OAuthError("invalid_grant", "authorization code expired")
        if (row.clientId != client.clientId) throw OAuthError("invalid_grant", "code was issued to another client")
        if (p["redirect_uri"] != null && p["redirect_uri"] != row.redirect) throw OAuthError("invalid_grant", "redirect_uri mismatch")
        if (!Secrets.constantTimeEquals(Secrets.pkceChallenge(verifier), row.challenge)) throw OAuthError("invalid_grant", "PKCE verification failed")
        val tokens = db.tx {
            // Null when a concurrent exchange won the race: that is a replay too.
            if (update("UPDATE oauth_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL", now, hash) != 1) {
                null
            } else {
                issueTokens(row.userId, client.clientId, family = Secrets.token(8), resource = row.resource)
            }
        }
        return tokens ?: replayed(row, now)
    }

    /**
     * Code replay (RFC 6749 §4.1.2): revoke every token of that client — tokens aren't
     * linked to the code they came from, and the client is suspect either way — then
     * refuse. Runs outside any transaction, so the revocation sticks.
     */
    private fun replayed(row: CodeRow, now: Long): Nothing {
        db.update("UPDATE oauth_tokens SET revoked_at = ? WHERE user_id = ? AND client_id = ? AND revoked_at IS NULL", now, row.userId, row.clientId)
        throw OAuthError("invalid_grant", "authorization code already used")
    }

    private data class RefreshRow(val userId: String, val clientId: String, val family: String, val resource: String?, val expires: Long, val revoked: Boolean)

    private fun refresh(client: OAuthClient, p: Map<String, String?>): JsonObject {
        val refresh = p["refresh_token"] ?: throw OAuthError("invalid_request", "refresh_token is required")
        val hash = Secrets.sha256(refresh)
        val now = clock()
        val row = db.one("SELECT * FROM oauth_tokens WHERE token_hash = ? AND kind = 'refresh'", hash) { rs ->
            RefreshRow(
                rs.getString("user_id"), rs.getString("client_id"), rs.getString("family"), rs.getString("resource"),
                rs.getLong("expires_at"), rs.getObject("revoked_at") != null,
            )
        } ?: throw OAuthError("invalid_grant", "unknown refresh token")
        if (row.clientId != client.clientId) throw OAuthError("invalid_grant", "token was issued to another client")
        if (row.revoked) {
            // A rotated-out refresh token came back: assume it leaked, revoke the whole family.
            db.update("UPDATE oauth_tokens SET revoked_at = ? WHERE family = ? AND revoked_at IS NULL", now, row.family)
            throw OAuthError("invalid_grant", "refresh token was already used")
        }
        if (row.expires < now) throw OAuthError("invalid_grant", "refresh token expired")
        return db.tx {
            if (update("UPDATE oauth_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", now, hash) != 1) {
                throw OAuthError("invalid_grant", "refresh token was already used")
            }
            issueTokens(row.userId, row.clientId, row.family, row.resource)
        }
    }

    /** Access and refresh token; both carry the code's audience ([resource]) through every rotation. */
    private fun Db.issueTokens(userId: String, clientId: String, family: String, resource: String?): JsonObject {
        val access = Secrets.token()
        val refresh = Secrets.token()
        val now = clock()
        update(
            "INSERT INTO oauth_tokens (token_hash, user_id, client_id, kind, family, expires_at, created_at, resource) VALUES (?,?,?,?,?,?,?,?)",
            Secrets.sha256(access), userId, clientId, "access", family, now + config.accessTokenTtlMs, now, resource,
        )
        update(
            "INSERT INTO oauth_tokens (token_hash, user_id, client_id, kind, family, expires_at, created_at, resource) VALUES (?,?,?,?,?,?,?,?)",
            Secrets.sha256(refresh), userId, clientId, "refresh", family, now + config.refreshTokenTtlMs, now, resource,
        )
        return buildJsonObject {
            put("access_token", access)
            put("token_type", "Bearer")
            put("expires_in", config.accessTokenTtlMs / 1000)
            put("refresh_token", refresh)
            put("scope", scope)
        }
    }

    fun revoke(token: String) {
        db.update("UPDATE oauth_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", clock(), Secrets.sha256(token))
    }

    /**
     * Validates a Bearer access token for /mcp. A token bound to an audience must be bound
     * to this endpoint (it isn't, e.g., after PUBLIC_URL changed); unbound tokens come from
     * clients that sent no `resource`.
     */
    fun authenticate(accessToken: String): Grant? {
        val row = db.one(
            "SELECT user_id, client_id, resource FROM oauth_tokens WHERE token_hash = ? AND kind = 'access' AND revoked_at IS NULL AND expires_at >= ?",
            Secrets.sha256(accessToken), clock(),
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) } ?: return null
        if (row.third != null && row.third != config.mcpUrl) return null
        val client = client(row.second) ?: return null
        return Grant(row.first, client.clientId, client.name)
    }

    data class Connection(val clientId: String, val name: String, val connectedAt: Long, val lastTokenAt: Long?)

    /** Connectors with live tokens, for the admin page. */
    fun connections(userId: String): List<Connection> = db.query(
        """
        SELECT c.client_id, c.client_name, c.created_at, MAX(t.created_at) AS last
        FROM oauth_clients c JOIN oauth_tokens t ON t.client_id = c.client_id
        WHERE t.user_id = ? AND t.revoked_at IS NULL AND t.expires_at >= ?
        GROUP BY c.client_id ORDER BY last DESC
        """.trimIndent(),
        userId, clock(),
    ) { Connection(it.getString(1), it.getString(2), it.getLong(3), it.getLong(4)) }

    /** Removes the connector: its tokens, codes and registration. It has to be added again. */
    fun disconnect(userId: String, clientId: String) {
        db.tx { deleteClient(userId, clientId) }
    }

    companion object {
        const val CODE_TTL_MS = 10 * 60 * 1000L
        const val MAX_CLIENTS = 50
        const val UNAPPROVED_TTL_MS = 24 * 60 * 60 * 1000L

        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")

        /** Hosts a connector must redirect to before it may carry a vendor's name (D-507). */
        private val VENDOR_HOSTS = mapOf(
            ProposalSource.CLAUDE to listOf("claude.ai", "claude.com", "anthropic.com"),
            ProposalSource.CHATGPT to listOf("chatgpt.com", "openai.com"),
        )

        /** Internal code: the redirect URI can't be trusted, so the error is shown, not redirected. */
        const val INVALID_REDIRECT = "invalid_redirect_uri"
    }
}
