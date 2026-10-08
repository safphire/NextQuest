// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.sync

import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.Envelope
import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.data.QuestRepository
import dev.dreamweave.quest.data.SettingsStore
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PushRequest
import dev.dreamweave.quest.protocol.SyncApi
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

sealed interface SyncOutcome {
    data class Ok(val pushed: Int, val pulled: Int, val refused: List<String>) : SyncOutcome
    data object NotPaired : SyncOutcome

    /**
     * [retry]: worth trying again later (offline, server busy). False when the user must act.
     * [unpaired]: the server no longer knows this phone; the pairing was forgotten.
     */
    data class Failed(val message: String, val retry: Boolean, val unpaired: Boolean = false) : SyncOutcome
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
     * a message for the user. Once the server answered, the local writes finish even if
     * the caller is cancelled, so a used-up code never leaves a half-paired phone.
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
        withContext(NonCancellable) {
            // A (new) server has none of this device's events yet. Marked first: if the process
            // dies before the token is saved, the phone is simply not paired yet.
            repository.markAllUnpushed()
            tokens.token = response.token
            settings.update { it.copy(serverUrl = server, syncCursor = 0, syncCursorEventId = null, syncPushedThrough = 0, syncPushedThroughEventId = null, lastSyncAt = null, lastSyncError = null) }
        }
        null
    }.also { if (it == null) sync() }

    /** Forgets the server. Nothing local is deleted; pairing again re-uploads the log. */
    suspend fun disconnect() = mutex.withLock { forget(error = null) }

    /**
     * After an Android restore the settings (server address, cursor) come back but the
     * token doesn't (it is excluded from backup), so the phone would look paired and never
     * sync. Call at startup: it turns that state into an honest "pair again".
     */
    suspend fun reconcile() = mutex.withLock {
        if (tokens.token == null && settings.current().serverUrl != null) {
            forget(error = "This phone was restored or reset. Pair it with your server in Settings to resume sync.")
        }
    }

    private suspend fun forget(error: String?) {
        tokens.token = null
        settings.update { it.copy(serverUrl = null, syncCursor = 0, syncCursorEventId = null, syncPushedThrough = 0, syncPushedThroughEventId = null, lastSyncAt = null, lastSyncError = error) }
    }

    suspend fun sync(): SyncOutcome = mutex.withLock {
        val server = settings.current().serverUrl
        val token = tokens.token
        if (server == null || token == null) return@withLock SyncOutcome.NotPaired
        val outcome = try {
            round(server, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e)
        }
        withContext(NonCancellable) {
            when {
                outcome is SyncOutcome.Ok -> settings.update {
                    it.copy(
                        lastSyncAt = clock(),
                        lastSyncError = outcome.refused.firstOrNull()?.let { r -> "${outcome.refused.size} change(s) refused by the server ($r)" },
                    )
                }
                // Removed on the server: this token is dead for good. Forget it so nothing keeps retrying.
                outcome is SyncOutcome.Failed && outcome.unpaired -> forget(outcome.message)
                outcome is SyncOutcome.Failed -> settings.update { it.copy(lastSyncError = outcome.message) }
                else -> Unit
            }
        }
        outcome
    }

    private fun failure(e: Exception): SyncOutcome.Failed = when {
        // A reply this version can't read won't fix itself by retrying (app and server versions differ).
        e is SerializationException || e is ContentConvertException ->
            SyncOutcome.Failed("The server's reply wasn't understood. Update the app and the server to matching versions.", retry = false)
        e !is SyncHttpException -> SyncOutcome.Failed("Can't reach the server.", retry = true)
        e.status == 401 -> SyncOutcome.Failed("This phone was removed from the server. Pair it in Settings to resume sync.", retry = false, unpaired = true)
        e.status == 429 || e.status >= 500 -> SyncOutcome.Failed(e.message ?: "The server is busy.", retry = true)
        else -> SyncOutcome.Failed(e.message ?: "The server refused the request.", retry = false)
    }

    private suspend fun round(server: String, token: String): SyncOutcome {
        val start = settings.current()
        var cursor = start.syncCursor
        var cursorEventId = start.syncCursorEventId

        // Push first, but a push that fails must not keep proposals from arriving: the pull
        // still runs and the push failure is reported afterwards.
        var pushed = 0
        val refused = linkedMapOf<String, String>() // event id → reason, so a retried refusal counts once
        val pushFailure = try {
            // One empty push probes the server's latest cursor for both checks below.
            val latest = if (cursor > 0 || start.syncPushedThrough > 0) transport.push(server, token, PushRequest(emptyList())).cursor else 0L
            if (cursor > 0 && serverWasReset(server, token, latest, cursor, cursorEventId)) {
                // Reset, or restored from an older backup: pull from the start and offer it the
                // whole log again. Unpushed first: if the round stops in between, the next one
                // still sees the old cursor and detects the reset again.
                withContext(NonCancellable) {
                    repository.markAllUnpushed()
                    settings.update { it.copy(syncCursor = 0, syncCursorEventId = null, syncPushedThrough = 0, syncPushedThroughEventId = null) }
                }
                cursor = 0
                cursorEventId = null
            } else if (pushesWereLost(server, token, latest, start)) {
                // Restored to a backup taken after our last pull but before some of our pushes:
                // the pulled part is intact, the pushes after it are gone. Offer the log again
                // (the server keeps each event once) and forget the watermark.
                withContext(NonCancellable) {
                    repository.markAllUnpushed()
                    settings.update { it.copy(syncPushedThrough = 0, syncPushedThroughEventId = null) }
                }
            }
            // Edits made while a round runs are picked up by another pass (sync work is KEEP,
            // so a running round is never cancelled by a newer request).
            for (pass in 1..MAX_PUSH_PASSES) {
                val n = pushAll(server, token, refused)
                pushed += n
                if (n == 0) break
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
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
                refused["pull"] = "${future.size} event(s) from the server are dated in the future; check this phone's clock"
                // Don't move past them: they are pulled again next round, when the clock may be right.
                break
            }
            cursor = r.cursor
            cursorEventId = r.events.lastOrNull()?.event?.id ?: cursorEventId
            // Once the pull has passed the push watermark, the pull-cursor check covers those pushes.
            settings.update {
                if (cursor >= it.syncPushedThrough) {
                    it.copy(syncCursor = cursor, syncCursorEventId = cursorEventId, syncPushedThrough = 0, syncPushedThroughEventId = null)
                } else {
                    it.copy(syncCursor = cursor, syncCursorEventId = cursorEventId)
                }
            }
            if (!r.hasMore) break
        }
        if (pushFailure != null) return failure(pushFailure)
        // Edits made while pulling: send them now instead of at the next periodic sync (a
        // newer sync request doesn't replace a running round). A failure here leaves them queued.
        if (repository.unpushedCount() > 0) {
            try {
                pushed += pushAll(server, token, refused)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failure(e)
            }
        }
        return SyncOutcome.Ok(pushed, pulled, refused.values.toList())
    }

    /**
     * Whether the server no longer has the log this phone pulled: its latest cursor is behind
     * ours, or the event at our cursor is a different one (restored from an older backup and
     * since overtaken by other devices' pushes). An empty push is a cheap probe of the latest.
     */
    private suspend fun serverWasReset(server: String, token: String, latest: Long, cursor: Long, cursorEventId: String?): Boolean {
        if (latest < cursor) return true
        if (cursorEventId == null) return false // pulled before ids were remembered
        val at = transport.pull(server, token, cursor - 1, 1).events.firstOrNull()
        return at == null || at.cursor != cursor || at.event.id != cursorEventId
    }

    /**
     * Whether pushes this phone made after its last pull are gone from the server: the
     * server's latest cursor is below the highest cursor our pushes reached, or another
     * event sits there now (restored, then overtaken by other devices).
     */
    private suspend fun pushesWereLost(server: String, token: String, latest: Long, s: AppSettings): Boolean {
        if (s.syncPushedThrough <= 0) return false
        if (latest < s.syncPushedThrough) return true
        val id = s.syncPushedThroughEventId ?: return false
        return !holds(server, token, s.syncPushedThrough, id)
    }

    /** True when the server's event at [cursor] is [eventId]. */
    private suspend fun holds(server: String, token: String, cursor: Long, eventId: String): Boolean {
        val at = transport.pull(server, token, cursor - 1, 1).events.firstOrNull()
        return at != null && at.cursor == cursor && at.event.id == eventId
    }

    /**
     * Remembers the highest cursor a push of ours reached, with the id of the event the server
     * holds there, so a later restore below it is noticed ([pushesWereLost]).
     */
    private suspend fun rememberPushedThrough(server: String, token: String, cursor: Long) {
        if (cursor <= settings.current().syncPushedThrough) return
        val at = transport.pull(server, token, cursor - 1, 1).events.firstOrNull()?.takeIf { it.cursor == cursor } ?: return
        settings.update { it.copy(syncPushedThrough = cursor, syncPushedThroughEventId = at.event.id) }
    }

    /**
     * One pass over the queue. A refusal is final (the event is marked done, so it never
     * blocks the queue) except "dated in the future" (the server's wording, see EventStore),
     * which clears once the server's clock catches up. Batches stay under the server's
     * body limit by size as well as count. Returns how many events the server stored.
     */
    private suspend fun pushAll(server: String, token: String, refused: MutableMap<String, String>): Int {
        var pushed = 0
        var afterId = ""
        while (true) {
            val page = repository.unpushed(afterId, SyncApi.MAX_PUSH)
            if (page.isEmpty()) break
            for (batch in bySize(page)) {
                val r = transport.push(server, token, PushRequest(batch))
                // Duplicates count too: a push whose reply was lost was stored, and is only confirmed now.
                if (r.accepted + r.duplicates > 0) rememberPushedThrough(server, token, r.cursor)
                val retryLater = r.rejected.filter { "future" in it.reason }.map { it.id }.toSet()
                repository.markPushed(batch.map { it.id }.filter { it !in retryLater })
                val byId = batch.associateBy { it.id }
                pushed += batch.size - r.rejected.size
                // Only refusals of this device's own events are worth telling the user about.
                r.rejected.filter { byId[it.id]?.deviceId == repository.deviceId }.forEach { refused[it.id] = it.reason }
            }
            afterId = page.last().id
            if (page.size < SyncApi.MAX_PUSH) break
        }
        return pushed
    }

    private fun bySize(events: List<Event>): List<List<Event>> {
        val batches = mutableListOf<List<Event>>()
        var current = mutableListOf<Event>()
        var bytes = 0
        for (e in events) {
            val size = CoreJson.encodeToString(Event.serializer(), e).length * 3 // worst case UTF-8
            if (current.isNotEmpty() && bytes + size > MAX_PUSH_BYTES) {
                batches += current
                current = mutableListOf()
                bytes = 0
            }
            current += e
            bytes += size
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    companion object {
        /** Well under the server's 4 MB body limit for pushes. */
        private const val MAX_PUSH_BYTES = 2_000_000
        private const val MAX_PUSH_PASSES = 3

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
