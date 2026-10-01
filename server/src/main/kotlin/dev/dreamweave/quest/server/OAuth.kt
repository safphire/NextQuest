// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI

/** An authenticated MCP caller: whose quests, which connector. */
data class Grant(val userId: String, val clientId: String, val clientName: String)

data class OAuthClient(val clientId: String, val userId: String, val name: String, val redirectUris: List<String>, val authMethod: String, val secretHash: String?)

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
        val method = body["token_endpoint_auth_method"]?.jsonPrimitive?.contentOrNull ?: "none"
        if (method !in setOf("none", "client_secret_post", "client_secret_basic")) {
            throw OAuthError("invalid_client_metadata", "unsupported token_endpoint_auth_method $method")
        }
        val name = clientName(body["client_name"]?.jsonPrimitive?.contentOrNull)
        if ((db.one("SELECT COUNT(*) FROM oauth_clients WHERE user_id = ?", config.userId) { it.getInt(1) } ?: 0) >= MAX_CLIENTS) {
            throw OAuthError("invalid_client_metadata", "too many registered clients; remove unused ones on the admin page")
        }
        val clientId = "qc_" + Secrets.token(16)
        val secret = if (method == "none") null else Secrets.token()
        val now = clock()
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

    /** A short display name for the connector (it also names the MCP actor, `mcp:<name>`). */
    fun clientName(raw: String?): String =
        raw?.filter { it.isLetterOrDigit() || it in " ._-()" }?.trim()?.take(40)?.ifEmpty { null } ?: "AI client"

    fun client(clientId: String?): OAuthClient? = clientId?.let {
        db.one("SELECT * FROM oauth_clients WHERE client_id = ?", it) { rs ->
            OAuthClient(
                rs.getString("client_id"), rs.getString("user_id"), rs.getString("client_name"),
                kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("redirect_uris")).let { j -> (j as JsonArray).map { e -> (e as JsonPrimitive).content } },
                rs.getString("auth_method"), rs.getString("secret_hash"),
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
        return AuthRequest(client, redirect, p["state"], challenge, scope, resource)
    }

    fun issueCode(userId: String, req: AuthRequest): String {
        val code = Secrets.token()
        db.update(
            "INSERT INTO oauth_codes (code_hash, user_id, client_id, redirect_uri, code_challenge, scope, resource, expires_at) VALUES (?,?,?,?,?,?,?,?)",
            Secrets.sha256(code), userId, req.client.clientId, req.redirectUri, req.codeChallenge, req.scope, req.resource, clock() + CODE_TTL_MS,
        )
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

    private data class CodeRow(val userId: String, val clientId: String, val redirect: String, val challenge: String, val expires: Long, val used: Boolean)

    private fun exchangeCode(client: OAuthClient, p: Map<String, String?>): JsonObject {
        val code = p["code"] ?: throw OAuthError("invalid_request", "code is required")
        val verifier = p["code_verifier"] ?: throw OAuthError("invalid_request", "code_verifier is required")
        val hash = Secrets.sha256(code)
        val now = clock()
        val row = db.one("SELECT * FROM oauth_codes WHERE code_hash = ?", hash) { rs ->
            CodeRow(
                rs.getString("user_id"), rs.getString("client_id"), rs.getString("redirect_uri"), rs.getString("code_challenge"),
                rs.getLong("expires_at"), rs.getObject("used_at") != null,
            )
        } ?: throw OAuthError("invalid_grant", "unknown authorization code")
        if (row.used) {
            // Code replay: revoke what was issued from it (RFC 6749 §4.1.2), then refuse.
            db.update("UPDATE oauth_tokens SET revoked_at = ? WHERE user_id = ? AND client_id = ? AND revoked_at IS NULL", now, row.userId, row.clientId)
            throw OAuthError("invalid_grant", "authorization code already used")
        }
        if (row.expires < now) throw OAuthError("invalid_grant", "authorization code expired")
        if (row.clientId != client.clientId) throw OAuthError("invalid_grant", "code was issued to another client")
        if (p["redirect_uri"] != null && p["redirect_uri"] != row.redirect) throw OAuthError("invalid_grant", "redirect_uri mismatch")
        if (!Secrets.constantTimeEquals(Secrets.pkceChallenge(verifier), row.challenge)) throw OAuthError("invalid_grant", "PKCE verification failed")
        return db.tx {
            if (update("UPDATE oauth_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL", now, hash) != 1) {
                throw OAuthError("invalid_grant", "authorization code already used")
            }
            issueTokens(row.userId, client.clientId, family = Secrets.token(8))
        }
    }

    private data class RefreshRow(val userId: String, val clientId: String, val family: String, val expires: Long, val revoked: Boolean)

    private fun refresh(client: OAuthClient, p: Map<String, String?>): JsonObject {
        val refresh = p["refresh_token"] ?: throw OAuthError("invalid_request", "refresh_token is required")
        val hash = Secrets.sha256(refresh)
        val now = clock()
        val row = db.one("SELECT * FROM oauth_tokens WHERE token_hash = ? AND kind = 'refresh'", hash) { rs ->
            RefreshRow(rs.getString("user_id"), rs.getString("client_id"), rs.getString("family"), rs.getLong("expires_at"), rs.getObject("revoked_at") != null)
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
            issueTokens(row.userId, row.clientId, row.family)
        }
    }

    private fun Db.issueTokens(userId: String, clientId: String, family: String): JsonObject {
        val access = Secrets.token()
        val refresh = Secrets.token()
        val now = clock()
        update(
            "INSERT INTO oauth_tokens (token_hash, user_id, client_id, kind, family, expires_at, created_at) VALUES (?,?,?,?,?,?,?)",
            Secrets.sha256(access), userId, clientId, "access", family, now + config.accessTokenTtlMs, now,
        )
        update(
            "INSERT INTO oauth_tokens (token_hash, user_id, client_id, kind, family, expires_at, created_at) VALUES (?,?,?,?,?,?,?)",
            Secrets.sha256(refresh), userId, clientId, "refresh", family, now + config.refreshTokenTtlMs, now,
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

    /** Validates a Bearer access token for /mcp. */
    fun authenticate(accessToken: String): Grant? {
        val row = db.one(
            "SELECT user_id, client_id FROM oauth_tokens WHERE token_hash = ? AND kind = 'access' AND revoked_at IS NULL AND expires_at >= ?",
            Secrets.sha256(accessToken), clock(),
        ) { it.getString(1) to it.getString(2) } ?: return null
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

    fun disconnect(userId: String, clientId: String) {
        db.update("UPDATE oauth_tokens SET revoked_at = ? WHERE user_id = ? AND client_id = ? AND revoked_at IS NULL", clock(), userId, clientId)
    }

    companion object {
        const val CODE_TTL_MS = 10 * 60 * 1000L
        const val MAX_CLIENTS = 50

        /** Internal code: the redirect URI can't be trusted, so the error is shown, not redirected. */
        const val INVALID_REDIRECT = "invalid_redirect_uri"
    }
}
