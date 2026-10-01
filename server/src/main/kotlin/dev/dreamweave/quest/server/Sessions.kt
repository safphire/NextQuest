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

/**
 * Sliding-window limiter, per key, in memory. Keys whose window has passed are swept once
 * a minute, and whenever the map has doubled since the last sweep, so memory stays bounded
 * by the keys active within their windows (an attacker rotating addresses can't grow it
 * forever).
 */
class RateLimiter(private val clock: () -> Long) {
    private class Window(val ms: Long) {
        val hits = ArrayDeque<Long>()
    }

    private val windows = HashMap<String, Window>()
    private var sweepAt = SWEEP_MIN
    private var lastSweep = 0L

    /** Records a hit and returns null if allowed, or the seconds to wait. */
    @Synchronized
    fun hit(key: String, limit: Int, windowMs: Long): Long? {
        blocked(key, limit, windowMs)?.let { return it }
        windows.getValue(key).hits.addLast(clock())
        return null
    }

    /** Like [hit] without recording one: for budgets spent only on failure. */
    @Synchronized
    fun blocked(key: String, limit: Int, windowMs: Long): Long? {
        val now = clock()
        if (windows.size >= sweepAt || now - lastSweep >= SWEEP_INTERVAL_MS) sweep(now)
        val w = windows.getOrPut(key) { Window(windowMs) }
        while (w.hits.isNotEmpty() && w.hits.first() <= now - windowMs) w.hits.removeFirst()
        if (w.hits.size >= limit) return ((w.hits.first() + windowMs - now) / 1000).coerceAtLeast(1)
        return null
    }

    @Synchronized
    fun size(): Int = windows.size

    private fun sweep(now: Long) {
        windows.values.removeAll { w -> w.hits.lastOrNull()?.let { it <= now - w.ms } ?: true }
        sweepAt = maxOf(SWEEP_MIN, windows.size * 2)
        lastSweep = now
    }

    companion object {
        const val SWEEP_MIN = 1_024
        const val SWEEP_INTERVAL_MS = 60_000L
    }
}
