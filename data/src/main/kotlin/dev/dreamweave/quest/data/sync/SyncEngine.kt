// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.sync

import dev.dreamweave.quest.core.Envelope
import dev.dreamweave.quest.data.QuestRepository
import dev.dreamweave.quest.data.SettingsStore
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PushRequest
import dev.dreamweave.quest.protocol.SyncApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncOutcome {
    data class Ok(val pushed: Int, val pulled: Int, val refused: List<String>) : SyncOutcome
    data object NotPaired : SyncOutcome

    /** [retry]: worth trying again later (offline, server busy). False when the user must act. */
    data class Failed(val message: String, val retry: Boolean) : SyncOutcome
}

/**
 * One sync round: push this device's unpushed events (the offline queue), then pull
 * everything new after the stored cursor. Both directions are idempotent, so a round cut
 * short anywhere is simply repeated. Rounds never overlap.
 */
class SyncEngine(
    private val repository: QuestRepository,
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val transport: SyncTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    val isPaired: Boolean get() = tokens.token != null

    /**
     * Exchanges a one-time code for a device token, then syncs. Null on success, otherwise
     * a message for the user.
     */
    suspend fun pair(serverUrl: String, code: String, deviceName: String): String? = mutex.withLock {
        val server = normalize(serverUrl) ?: return@withLock "That doesn't look like a server address (it should start with https://)."
        val response = try {
            transport.pair(server, PairRequest(code.trim(), repository.deviceId, deviceName.take(60)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncHttpException) {
            return@withLock e.message
        } catch (e: Exception) {
            return@withLock "Can't reach $server. Check the address and your connection."
        }
        tokens.token = response.token
        settings.update { it.copy(serverUrl = server, syncCursor = 0, lastSyncAt = null, lastSyncError = null) }
        // A (new) server has none of this device's events yet.
        repository.markAllUnpushed()
        null
    }.also { if (it == null) sync() }

    /** Forgets the server. Nothing local is deleted; pairing again re-uploads this device's events. */
    suspend fun disconnect() = mutex.withLock {
        tokens.token = null
        settings.update { it.copy(serverUrl = null, syncCursor = 0, lastSyncAt = null, lastSyncError = null) }
    }

    suspend fun sync(): SyncOutcome = mutex.withLock {
        val server = settings.current().serverUrl
        val token = tokens.token
        if (server == null || token == null) return@withLock SyncOutcome.NotPaired
        val outcome = try {
            round(server, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncHttpException) {
            when {
                e.status == 401 -> SyncOutcome.Failed("This phone was removed from the server. Pair it again in Settings.", retry = false)
                e.status == 429 || e.status >= 500 -> SyncOutcome.Failed(e.message ?: "The server is busy.", retry = true)
                else -> SyncOutcome.Failed(e.message ?: "The server refused the request.", retry = false)
            }
        } catch (e: Exception) {
            SyncOutcome.Failed("Can't reach the server.", retry = true)
        }
        when (outcome) {
            is SyncOutcome.Ok -> settings.update {
                it.copy(
                    lastSyncAt = clock(),
                    lastSyncError = outcome.refused.firstOrNull()?.let { r -> "${outcome.refused.size} change(s) refused by the server ($r)" },
                )
            }
            is SyncOutcome.Failed -> settings.update { it.copy(lastSyncError = outcome.message) }
            SyncOutcome.NotPaired -> Unit
        }
        outcome
    }

    private suspend fun round(server: String, token: String): SyncOutcome.Ok {
        var cursor = settings.current().syncCursor

        // Push. A refusal is final (the event is marked done, so it never blocks the queue)
        // except "dated in the future", which clears once the server's clock catches up.
        // Only refusals of this device's own events are worth telling the user about.
        var pushed = 0
        val refused = mutableListOf<String>()
        var afterId = ""
        while (true) {
            val batch = repository.unpushed(afterId, SyncApi.MAX_PUSH)
            if (batch.isEmpty()) break
            val r = transport.push(server, token, PushRequest(batch))
            val retryLater = r.rejected.filter { "future" in it.reason }.map { it.id }.toSet()
            repository.markPushed(batch.map { it.id }.filter { it !in retryLater })
            val byId = batch.associateBy { it.id }
            pushed += batch.size - r.rejected.size
            refused += r.rejected.filter { byId[it.id]?.deviceId == repository.deviceId }.map { it.reason }
            // The server's log is behind our cursor: it was reset or replaced. Pull everything again.
            if (r.cursor < cursor) cursor = 0
            afterId = batch.last().id
            if (batch.size < SyncApi.MAX_PUSH) break
        }

        // Pull, page by page, saving the cursor after each page so progress survives interruptions.
        var pulled = 0
        while (true) {
            val r = transport.pull(server, token, cursor, SyncApi.MAX_PULL)
            val now = clock()
            val events = r.events.map { it.event }
            // Same rule as the server and import: nothing more than a day ahead of this clock (D-112).
            val (future, usable) = events.partition { Envelope.tooFarAhead(it, now) }
            if (usable.isNotEmpty()) pulled += repository.ingest(usable, fromServer = true).added
            if (future.isNotEmpty()) {
                refused += "${future.size} event(s) from the server are dated in the future; check this phone's clock"
                // Don't move past them: they are pulled again next round, when the clock may be right.
                break
            }
            cursor = r.cursor
            settings.update { it.copy(syncCursor = cursor) }
            if (!r.hasMore) break
        }
        if (cursor == 0L) settings.update { it.copy(syncCursor = 0) }
        return SyncOutcome.Ok(pushed, pulled, refused)
    }

    companion object {
        /** Trims, drops trailing slashes and a trailing "/mcp", requires http(s). */
        fun normalize(url: String): String? {
            var u = url.trim().trimEnd('/')
            if (u.endsWith("/mcp")) u = u.removeSuffix("/mcp")
            if (!u.startsWith("https://") && !u.startsWith("http://")) return null
            if (u.substringAfter("://").isBlank()) return null
            return u
        }
    }
}
