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
 * can be forged. When the header is shorter than expected, the peer address is used.
 */
fun clientAddress(peer: String, forwardedFor: List<String>, trustProxy: Int): String {
    if (trustProxy <= 0) return peer
    val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    return hops.getOrNull(hops.size - trustProxy) ?: peer
}

fun ApplicationCall.clientAddress(config: Config): String =
    clientAddress(request.local.remoteAddress, request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty(), config.trustProxy)

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
