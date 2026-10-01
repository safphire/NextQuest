// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlin.random.Random

/**
 * UUIDv7 (RFC 9562) string ids: a 48-bit Unix-millisecond timestamp followed by
 * random bits, so ids sort roughly by creation time and never collide across devices.
 */
object Ids {
    private val HEX = "0123456789abcdef".toCharArray()

    fun newId(nowMs: Long = System.currentTimeMillis(), random: Random = Random.Default): String {
        require(nowMs >= 0) { "timestamp must be non-negative" }
        val bytes = ByteArray(16)
        for (i in 0 until 6) bytes[i] = (nowMs ushr (8 * (5 - i))).toByte()
        val rnd = random.nextBytes(10)
        rnd.copyInto(bytes, 6)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte() // version 7
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // RFC 4122 variant
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            val b = bytes[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    /**
     * A deterministic UUIDv7 derived from [base] (itself a UUIDv7) and a [salt]: same
     * inputs, same id, on every device. Accepting a proposal derives the ids it creates
     * from the proposal id, so accepting the same proposal on two offline devices yields
     * colliding events, and the fold keeps only the first (docs/SYNC.md).
     */
    fun derive(base: String, salt: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$base/$salt".toByteArray(Charsets.UTF_8))
        // The random part comes straight from the digest, so the id is identical on every
        // platform and Kotlin version.
        val fixed = object : Random() {
            override fun nextBits(bitCount: Int): Int = throw UnsupportedOperationException()
            override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
                for (i in fromIndex until toIndex) array[i] = digest[(i - fromIndex) % digest.size]
                return array
            }
        }
        return newId(timestampOf(base), fixed)
    }

    private val PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /** True for a lowercase UUIDv7 string. */
    fun isValid(id: String): Boolean = PATTERN.matches(id)

    /** The millisecond timestamp embedded in a UUIDv7. */
    fun timestampOf(id: String): Long {
        require(isValid(id)) { "not a UUIDv7: $id" }
        return id.replace("-", "").substring(0, 12).toLong(16)
    }
}
