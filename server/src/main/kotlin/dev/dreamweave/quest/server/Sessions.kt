// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

/** Admin web login (single-user: the admin password from the environment). */
class Sessions(private val db: Db, private val config: Config, private val clock: () -> Long) {
    data class Session(val userId: String, val csrf: String)

    fun checkPassword(password: String): Boolean =
        Secrets.constantTimeEquals(Secrets.sha256(password), Secrets.sha256(config.adminPassword))

    /** Returns a new session token, or null for a wrong password. */
    fun login(password: String): String? {
        if (!checkPassword(password)) return null
        val token = Secrets.token()
        db.update("DELETE FROM web_sessions WHERE expires_at < ?", clock())
        db.update(
            "INSERT INTO web_sessions (session_hash, user_id, csrf, expires_at) VALUES (?,?,?,?)",
            Secrets.sha256(token), config.userId, Secrets.token(16), clock() + TTL_MS,
        )
        return token
    }

    fun get(token: String?): Session? {
        token ?: return null
        return db.one("SELECT user_id, csrf FROM web_sessions WHERE session_hash = ? AND expires_at >= ?", Secrets.sha256(token), clock()) {
            Session(it.getString(1), it.getString(2))
        }
    }

    fun logout(token: String?) {
        token?.let { db.update("DELETE FROM web_sessions WHERE session_hash = ?", Secrets.sha256(it)) }
    }

    companion object {
        const val COOKIE = "quest_session"
        const val TTL_MS = 12 * 60 * 60 * 1000L
    }
}

/** Sliding-window limiter, per key, in memory. */
class RateLimiter(private val clock: () -> Long) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /** Records a hit and returns null if allowed, or the seconds to wait. */
    @Synchronized
    fun hit(key: String, limit: Int, windowMs: Long): Long? {
        val now = clock()
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && q.first() <= now - windowMs) q.removeFirst()
        if (q.size >= limit) return ((q.first() + windowMs - now) / 1000).coerceAtLeast(1)
        q.addLast(now)
        return null
    }
}
