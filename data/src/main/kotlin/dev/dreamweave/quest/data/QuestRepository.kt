// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import dev.dreamweave.quest.core.Actors
import dev.dreamweave.quest.core.Commits
import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Fold
import dev.dreamweave.quest.core.Folder
import dev.dreamweave.quest.core.PendingEvent
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.data.db.EventDao
import dev.dreamweave.quest.data.db.EventEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/** What changed, for listeners (sync scheduler, widget, alarms, vault mirror). */
data class Change(val before: QuestState, val after: QuestState, val events: List<Event>, val local: Boolean)

sealed interface CommitResult {
    data class Ok(val change: Change) : CommitResult
    data class Rejected(val reason: String) : CommitResult
}

data class IngestResult(val added: Int, val skipped: List<String>)

/**
 * The single writer of the event log. Keeps an in-memory [Folder] in step with the
 * Room table: local commits are validated as a whole before they are stored, and
 * events from sync or import are stored and folded (refolding from scratch when they
 * sort before what is already applied).
 */
class QuestRepository(
    private val dao: EventDao,
    val deviceId: String,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var folder: Folder? = null
    private var factory: EventFactory? = null
    private var tz: TimeZone = timeZone()

    private val _state = MutableStateFlow<QuestState?>(null)

    /** Null until [load] finished. */
    val state: StateFlow<QuestState?> = _state.asStateFlow()

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 16)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    val zone: TimeZone get() = tz

    suspend fun load(): QuestState = mutex.withLock { ensureLoaded() }

    private suspend fun ensureLoaded(): QuestState {
        folder?.let { return it.snapshot() }
        return withContext(io) {
            val events = dao.all().map { it.toEvent() }
            val f = Fold.folder(events, tz)
            folder = f
            factory = EventFactory(
                deviceId = deviceId,
                lastSeq = dao.maxSeq(deviceId) ?: 0,
                lastTs = dao.maxTs() ?: 0,
                clock = clock,
            )
            f.snapshot().also { _state.value = it }
        }
    }

    /** Current state, loading it first if needed. */
    suspend fun current(): QuestState = mutex.withLock { ensureLoaded() }

    /**
     * Stamps, validates and stores one user action. All-or-nothing: if any event would
     * be refused by the fold, nothing is written and the reason is returned.
     */
    suspend fun commit(pending: List<PendingEvent>, actor: String = Actors.USER): CommitResult = mutex.withLock {
        val before = ensureLoaded()
        if (pending.isEmpty()) return@withLock CommitResult.Rejected("nothing to save")
        val f = folder!!
        val fac = factory!!
        val savedSeq = fac.currentSeq
        val savedTs = fac.currentTs
        val events = fac.createAll(pending, actor)
        when (val result = Commits.validate(f, events)) {
            is Commits.Result.Rejected -> {
                // Unused sequence numbers are fine, but keep the clock where it was.
                factory = EventFactory(deviceId, savedSeq, savedTs, clock)
                CommitResult.Rejected(result.reason)
            }
            is Commits.Result.Ok -> {
                withContext(io) { dao.insertAll(events.map(EventEntity::from)) }
                folder = result.folder
                val after = result.folder.snapshot()
                _state.value = after
                val change = Change(before, after, events, local = true)
                _changes.tryEmit(change)
                CommitResult.Ok(change)
            }
        }
    }

    /**
     * Stores events that came from elsewhere (sync pull, snapshot import). Duplicates are
     * ignored; invalid events are kept in the log but skipped by the fold.
     */
    suspend fun ingest(events: List<Event>): IngestResult = mutex.withLock {
        val before = ensureLoaded()
        if (events.isEmpty()) return@withLock IngestResult(0, emptyList())
        val fresh = withContext(io) {
            val existing = events.map { it.id }.chunked(500).flatMap { dao.existingIds(it) }.toSet()
            val unique = events.filter { it.id !in existing }.distinctBy { it.id }
            dao.insertAll(unique.map(EventEntity::from))
            unique
        }
        if (fresh.isEmpty()) return@withLock IngestResult(0, emptyList())
        fresh.forEach { factory!!.observe(it.ts) }
        val f = folder!!
        val skippedBefore = f.snapshot().skipped.size
        val needsRefold = fresh.any { f.isOutOfOrder(it) }
        val newFolder = if (needsRefold) {
            withContext(io) { Fold.folder(dao.all().map { it.toEvent() }, tz) }
        } else {
            fresh.sortedWith(Event.ORDER).forEach { f.apply(it) }
            f
        }
        folder = newFolder
        val after = newFolder.snapshot()
        _state.value = after
        _changes.tryEmit(Change(before, after, fresh, local = false))
        val freshIds = fresh.map { it.id }.toSet()
        val skipped = if (needsRefold) after.skipped.filter { it.eventId in freshIds } else after.skipped.drop(skippedBefore)
        IngestResult(fresh.size, skipped.map { "${it.type}: ${it.reason}" })
    }

    /** Re-derives everything, e.g. after the device time zone changed (streak days move). */
    suspend fun refold() = mutex.withLock {
        tz = timeZone()
        val events = withContext(io) { dao.all().map { it.toEvent() } }
        val f = Fold.folder(events, tz)
        folder = f
        _state.value = f.snapshot()
    }

    suspend fun allEvents(): List<Event> = withContext(io) { dao.all().map { it.toEvent() } }
}
