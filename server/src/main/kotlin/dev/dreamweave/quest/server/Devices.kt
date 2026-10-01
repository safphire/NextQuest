// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PairResponse

data class Device(val userId: String, val deviceId: String, val name: String, val createdAt: Long, val lastSeenAt: Long?)

/** Pairing codes (shown on the admin page) and device tokens for the sync API. */
class Devices(private val db: Db, private val config: Config, private val clock: () -> Long) {
    /** A one-time code, valid for [CODE_TTL_MS]. Only its hash is stored. */
    fun newPairingCode(userId: String): Pair<String, Long> {
        val code = Secrets.pairingCode()
        val expires = clock() + CODE_TTL_MS
        db.update("DELETE FROM pairing_codes WHERE expires_at < ?", clock())
        db.update("INSERT INTO pairing_codes (code_hash, user_id, expires_at) VALUES (?,?,?)", Secrets.sha256(Secrets.normalizeCode(code)), userId, expires)
        return code to expires
    }

    /** Redeems a pairing code. Returns null when the code is unknown, used or expired. */
    fun pair(req: PairRequest): PairResponse? {
        if (!Ids.isValid(req.deviceId)) return null
        val hash = Secrets.sha256(Secrets.normalizeCode(req.code))
        val now = clock()
        return db.tx {
            val userId = one("SELECT user_id FROM pairing_codes WHERE code_hash = ? AND used_at IS NULL AND expires_at >= ?", hash, now) { it.getString(1) }
                ?: return@tx null
            update("UPDATE pairing_codes SET used_at = ? WHERE code_hash = ?", now, hash)
            val token = Secrets.token()
            val name = req.deviceName.trim().take(60).ifEmpty { "Phone" }
            update("DELETE FROM devices WHERE user_id = ? AND device_id = ?", userId, req.deviceId)
            update(
                "INSERT INTO devices (user_id, device_id, name, token_hash, created_at) VALUES (?,?,?,?,?)",
                userId, req.deviceId, name, Secrets.sha256(token), now,
            )
            PairResponse(token = token, serverName = config.appName, userId = userId)
        }
    }

    fun authenticate(token: String): Device? {
        val d = db.one(
            "SELECT * FROM devices WHERE token_hash = ? AND revoked_at IS NULL", Secrets.sha256(token),
        ) { Device(it.getString("user_id"), it.getString("device_id"), it.getString("name"), it.getLong("created_at"), it.getLong("last_seen_at").takeIf { v -> v > 0 }) }
            ?: return null
        db.update("UPDATE devices SET last_seen_at = ? WHERE user_id = ? AND device_id = ?", clock(), d.userId, d.deviceId)
        return d
    }

    fun list(userId: String): List<Device> = db.query(
        "SELECT * FROM devices WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at", userId,
    ) { Device(it.getString("user_id"), it.getString("device_id"), it.getString("name"), it.getLong("created_at"), it.getLong("last_seen_at").takeIf { v -> v > 0 }) }

    fun revoke(userId: String, deviceId: String) {
        db.update("UPDATE devices SET revoked_at = ? WHERE user_id = ? AND device_id = ?", clock(), userId, deviceId)
    }

    companion object {
        const val CODE_TTL_MS = 10 * 60 * 1000L
    }
}
