// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import kotlinx.datetime.TimeZone
import java.io.File

/**
 * Server settings, all from environment variables (see docker-compose.yml).
 * `APP_NAME` names the server; [APP_NAME_DEFAULT] is the one place the product name lives
 * in server code.
 */
data class Config(
    val port: Int = 8080,
    /** The URL people and AI clients use to reach this server, without a trailing slash. */
    val publicUrl: String,
    val adminPassword: String,
    val appName: String = APP_NAME_DEFAULT,
    val dataDir: File = File("data"),
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    /** Single-user mode: every row belongs to this user. Hosted mode would map logins to users. */
    val userId: String = "owner",
    val mcpCallsPerMinute: Int = 60,
    val proposalsPerHour: Int = 30,
    /** Browser origins allowed to call /mcp (requests without an Origin header are allowed). */
    val allowedOrigins: Set<String> = setOf("https://claude.ai", "https://chatgpt.com"),
    val accessTokenTtlMs: Long = 60 * 60 * 1000L,
    val refreshTokenTtlMs: Long = 30L * 24 * 60 * 60 * 1000,
    /**
     * Reverse proxies in front of the server whose `X-Forwarded-For` is trusted (0 = none:
     * rate limits key on the socket peer). Set it to the number of proxy hops, e.g. 1 for
     * Caddy on the same host; with more than the real number, clients can pick their key.
     */
    val trustProxy: Int = 0,
) {
    init {
        require(adminPassword.length >= 12) { "ADMIN_PASSWORD must be at least 12 characters" }
        require(publicUrl.startsWith("https://") || publicUrl.startsWith("http://")) { "PUBLIC_URL must be an http(s) URL" }
        require(trustProxy in 0..10) { "TRUST_PROXY must be a number of proxy hops (0-10)" }
    }

    val secureCookies: Boolean get() = publicUrl.startsWith("https://")
    val mcpUrl: String get() = "$publicUrl/mcp"
    val dbFile: File get() = File(dataDir, "quest.db")

    companion object {
        const val APP_NAME_DEFAULT = "NextQuest"

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val port = env["PORT"]?.toIntOrNull() ?: 8080
            return Config(
                port = port,
                publicUrl = (env["PUBLIC_URL"] ?: "http://localhost:$port").trimEnd('/'),
                adminPassword = env["ADMIN_PASSWORD"] ?: error("Set ADMIN_PASSWORD (at least 12 characters)."),
                appName = env["APP_NAME"]?.takeIf { it.isNotBlank() } ?: APP_NAME_DEFAULT,
                dataDir = File(env["DATA_DIR"] ?: "data"),
                timeZone = env["TZ"]?.takeIf { it.isNotBlank() }?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault(),
                mcpCallsPerMinute = env["MCP_CALLS_PER_MINUTE"]?.toIntOrNull() ?: 60,
                proposalsPerHour = env["PROPOSALS_PER_HOUR"]?.toIntOrNull() ?: 30,
                allowedOrigins = env["ALLOWED_ORIGINS"]?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim().trimEnd('/') }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }
                    ?: setOf("https://claude.ai", "https://chatgpt.com"),
                trustProxy = env["TRUST_PROXY"]?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
                    v.toIntOrNull() ?: error("TRUST_PROXY must be a number of proxy hops (0 = no proxy), got '$v'")
                } ?: 0,
            )
        }
    }
}
