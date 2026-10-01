// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentCharset
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import java.net.InetAddress

/** Body caps per route (bytes). Anything larger gets 413 before it is buffered. */
object BodyLimits {
    /** OAuth, login, admin forms and pairing: a few fields each. */
    const val FORM = 64 * 1024

    /** `POST /v1/events`: up to [dev.dreamweave.quest.protocol.SyncApi.MAX_PUSH] events. */
    const val EVENTS = 4 * 1024 * 1024

    /** `POST /mcp`: JSON-RPC messages with tool arguments. */
    const val MCP = 1024 * 1024
}

/** Thrown by the capped readers; StatusPages turns it into 413. */
class BodyTooLarge(val limit: Int) : Exception("request body larger than $limit bytes")

/**
 * Reads the body as text, refusing more than [limit] bytes: up front from Content-Length
 * when it is declared, and while reading when it is not (chunked), so a client can never
 * make the server buffer an unbounded body.
 */
suspend fun ApplicationCall.receiveTextLimited(limit: Int): String {
    val declared = request.contentLength()
    if (declared != null && declared > limit) throw BodyTooLarge(limit)
    val bytes = receiveChannel().readRemaining(limit + 1L).readByteArray()
    if (bytes.size > limit) throw BodyTooLarge(limit)
    return String(bytes, request.contentCharset() ?: Charsets.UTF_8)
}

/** A form (`application/x-www-form-urlencoded`) body, capped like [receiveTextLimited]. */
suspend fun ApplicationCall.receiveFormLimited(limit: Int = BodyLimits.FORM): Parameters =
    parseQueryString(receiveTextLimited(limit))

/** A JSON body decoded with [ToolJson]; null when it doesn't parse. */
suspend fun <T> ApplicationCall.receiveJsonLimited(serializer: KSerializer<T>, limit: Int): T? {
    val text = receiveTextLimited(limit)
    return runCatching { ToolJson.decodeFromString(serializer, text) }.getOrNull()
}

/**
 * The client address used for rate limits. The peer's IP as the socket sees it (never a
 * reverse-DNS name). Behind [Config.trustProxy] reverse proxies, the client is the entry
 * that many hops from the right of `X-Forwarded-For`: each trusted proxy appends the
 * address it received the request from, so entries further left are client-supplied and
 * can be forged. The header counts only when the peer itself could be such a proxy — on
 * loopback, a private network or the Docker bridge ([isProxyPeer]); a client connecting
 * from the internet can't pick its own key. When the header is shorter than expected,
 * the peer address is used.
 */
fun clientAddress(peer: String, forwardedFor: List<String>, trustProxy: Int): String {
    if (trustProxy <= 0 || !isProxyPeer(peer)) return peer
    val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    return hops.getOrNull(hops.size - trustProxy) ?: peer
}

fun ApplicationCall.clientAddress(config: Config): String =
    clientAddress(request.local.remoteAddress, request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty(), config.trustProxy)

/** The per-client rate-limit key: [clientAddress], with IPv6 reduced to its /64 ([rateLimitKey]). */
fun ApplicationCall.rateLimitKey(config: Config): String = rateLimitKey(clientAddress(config))

/**
 * An address as a rate-limit key. IPv4 addresses are used as they are; an IPv6 client
 * usually holds a whole /64 and can rotate through it freely, so it is keyed by that
 * prefix. Anything else (a malformed forwarded entry) is kept, bounded in length.
 */
fun rateLimitKey(address: String): String {
    val bytes = ipBytes(address) ?: return address.take(64)
    if (bytes.size != 16) return address
    return (0 until 8 step 2).joinToString(":", postfix = "::/64") {
        Integer.toHexString(((bytes[it].toInt() and 0xff) shl 8) or (bytes[it + 1].toInt() and 0xff))
    }
}

/**
 * Whether a socket peer may be a reverse proxy whose `X-Forwarded-For` counts: loopback,
 * RFC 1918 (which includes Docker's bridge networks, 172.16.0.0/12), IPv4 link-local,
 * IPv6 unique-local (fc00::/7) and link-local (fe80::/10). `localhost` is accepted for
 * engines that report a name for in-process requests (the test host); sockets never do.
 */
fun isProxyPeer(peer: String): Boolean {
    if (peer == "localhost") return true
    val b = ipBytes(peer)?.map { it.toInt() and 0xff } ?: return false
    return if (b.size == 4) {
        b[0] == 127 || b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
    } else {
        (b.take(15).all { it == 0 } && b[15] == 1) || (b[0] and 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] and 0xc0) == 0x80)
    }
}

/**
 * The bytes of an IP literal (4 or 16; IPv4-mapped IPv6 comes back as 4), or null. Never
 * resolves names: IPv4 is parsed here, and Java parses a string of hex digits and colons
 * as an IPv6 literal (failing, not looking it up, when it isn't one).
 */
private fun ipBytes(address: String): ByteArray? {
    val a = address.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    if (':' in a) {
        // Hex digits, colons and dots only, starting with one of the first two: anything
        // else could reach the resolver.
        if (a.first().digitToIntOrNull(16) == null && a.first() != ':') return null
        if (!a.all { it.digitToIntOrNull(16) != null || it == ':' || it == '.' }) return null
        return runCatching { InetAddress.getByName(a).address }.getOrNull()
    }
    val parts = a.split('.')
    if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.length > 3 || !p.all(Char::isDigit) || p.toInt() > 255 }) return null
    return ByteArray(4) { parts[it].toInt().toByte() }
}

/**
 * Sent with every response: nothing here may be framed (clickjacking of the consent
 * page), sniffed or leak its URL (codes, states) as a referrer. Pages use inline styles
 * and inline SVG only; no scripts. `form-action` is deliberately absent: the consent
 * form posts here and is then redirected to the connector's redirect URI, which Chrome
 * would check against `form-action` too.
 */
val SECURITY_HEADERS = listOf(
    "X-Frame-Options" to "DENY",
    "Content-Security-Policy" to "frame-ancestors 'none'; default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; base-uri 'none'",
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
)
