// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * SQLite through plain JDBC. One connection, serialized access (the server is small and
 * single-process). Every table carries `user_id`, so hosted multi-user is a matter of
 * mapping logins to user ids, not a migration.
 */
class Db(file: File) : AutoCloseable {
    private val conn: Connection

    init {
        file.absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA foreign_keys=ON")
            it.execute("PRAGMA busy_timeout=5000")
        }
        migrate()
    }

    private fun migrate() = synchronized(this) {
        val version = conn.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        MIGRATIONS.drop(version).forEachIndexed { i, sql ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { s -> sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(s::execute) }
                conn.createStatement().use { it.execute("PRAGMA user_version = ${version + i + 1}") }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun <T> tx(block: Db.() -> T): T = synchronized(this) {
        conn.autoCommit = false
        try {
            block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setObject(i + 1, null)
                is String -> ps.setString(i + 1, a)
                is Int -> ps.setInt(i + 1, a)
                is Long -> ps.setLong(i + 1, a)
                is Boolean -> ps.setInt(i + 1, if (a) 1 else 0)
                else -> ps.setString(i + 1, a.toString())
            }
        }
    }

    fun update(sql: String, vararg args: Any?): Int = synchronized(this) {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = synchronized(this) {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

    override fun close() = conn.close()

    companion object {
        /** Schema history. Append only; never edit an applied entry. */
        val MIGRATIONS = listOf(
            """
            CREATE TABLE meta (user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (user_id, key));
            CREATE TABLE events (
                cursor INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, id TEXT NOT NULL, device_id TEXT NOT NULL,
                seq INTEGER NOT NULL, ts INTEGER NOT NULL, actor TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL,
                received_at INTEGER NOT NULL, UNIQUE (user_id, id));
            CREATE INDEX events_user_cursor ON events (user_id, cursor);
            CREATE TABLE devices (
                user_id TEXT NOT NULL, device_id TEXT NOT NULL, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL, last_seen_at INTEGER, revoked_at INTEGER, PRIMARY KEY (user_id, device_id));
            CREATE TABLE pairing_codes (code_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires_at INTEGER NOT NULL, used_at INTEGER);
            CREATE TABLE oauth_clients (
                client_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, client_name TEXT NOT NULL, redirect_uris TEXT NOT NULL,
                secret_hash TEXT, auth_method TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE TABLE oauth_codes (
                code_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, client_id TEXT NOT NULL, redirect_uri TEXT NOT NULL,
                code_challenge TEXT NOT NULL, scope TEXT, resource TEXT, expires_at INTEGER NOT NULL, used_at INTEGER);
            CREATE TABLE oauth_tokens (
                token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, client_id TEXT NOT NULL, kind TEXT NOT NULL,
                family TEXT NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER, created_at INTEGER NOT NULL);
            CREATE INDEX oauth_tokens_client ON oauth_tokens (user_id, client_id);
            CREATE TABLE web_sessions (session_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, csrf TEXT NOT NULL, expires_at INTEGER NOT NULL);
            CREATE TABLE mcp_calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, at INTEGER NOT NULL, client_id TEXT NOT NULL,
                client_name TEXT NOT NULL, method TEXT NOT NULL, tool TEXT, ok INTEGER NOT NULL, detail TEXT);
            CREATE INDEX mcp_calls_user_at ON mcp_calls (user_id, at)
            """.trimIndent(),
            // 2: clients the owner approved (they count toward the cap; the rest expire),
            // and the audience (RFC 8707 resource) each token was issued for.
            """
            ALTER TABLE oauth_clients ADD COLUMN approved_at INTEGER;
            UPDATE oauth_clients SET approved_at = created_at WHERE client_id IN (SELECT client_id FROM oauth_codes);
            ALTER TABLE oauth_tokens ADD COLUMN resource TEXT
            """.trimIndent(),
        )
    }
}
