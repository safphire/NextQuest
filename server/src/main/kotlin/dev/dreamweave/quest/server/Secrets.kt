// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Random tokens and hashing. Only SHA-256 hashes of tokens and codes are ever stored. */
object Secrets {
    private val random = SecureRandom()
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O, 1/I

    fun token(bytes: Int = 32): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** Human-friendly one-time pairing code, e.g. "K7QM-2XRP". */
    fun pairingCode(): String {
        val chars = (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        return chars.substring(0, 4) + "-" + chars.substring(4)
    }

    fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** PKCE S256: BASE64URL(SHA256(verifier)) without padding. */
    fun pkceChallenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
