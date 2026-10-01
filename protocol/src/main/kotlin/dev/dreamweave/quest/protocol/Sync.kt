// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol

import dev.dreamweave.quest.core.Event
import kotlinx.serialization.Serializable

/*
 * Sync API (docs/SYNC.md). Devices push their events and pull everyone else's,
 * including `proposal.created` events the server writes for AI clients.
 */

/** POST /v1/pair — exchange a one-time pairing code for a device token. */
@Serializable
data class PairRequest(val code: String, val deviceId: String, val deviceName: String)

@Serializable
data class PairResponse(val token: String, val serverName: String, val userId: String)

/** POST /v1/events — idempotent by event id. */
@Serializable
data class PushRequest(val events: List<Event>)

@Serializable
data class RejectedEvent(val id: String, val reason: String)

@Serializable
data class PushResponse(
    val accepted: Int,
    val duplicates: Int,
    val rejected: List<RejectedEvent> = emptyList(),
    /**
     * The server's latest cursor after this push. Clients use it only to notice a reset
     * server (a cursor lower than the one they have pulled to); it is never a pull cursor,
     * since events that arrived before it may not have been pulled yet.
     */
    val cursor: Long,
)

@Serializable
data class ServerEvent(val cursor: Long, val event: Event)

/** GET /v1/events?after=<cursor>&limit=<n> */
@Serializable
data class PullResponse(val events: List<ServerEvent>, val cursor: Long, val hasMore: Boolean)

@Serializable
data class ApiError(val error: String, val message: String)

object SyncApi {
    const val PAIR = "/v1/pair"
    const val EVENTS = "/v1/events"
    const val MAX_PUSH = 1_000
    const val MAX_PULL = 500
}
