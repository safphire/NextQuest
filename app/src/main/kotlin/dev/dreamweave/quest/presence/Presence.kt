// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.content.Context
import dev.dreamweave.quest.data.QuestRepository
import dev.dreamweave.quest.data.SettingsStore
import dev.dreamweave.quest.vault.VaultWorker
import dev.dreamweave.quest.widget.QuestWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps alarms and the home-screen widget in step with the event log and settings. */
class Presence(
    private val context: Context,
    private val repository: QuestRepository,
    private val settings: SettingsStore,
    val scheduler: ReminderScheduler,
) {
    private val mutex = Mutex()

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        merge(repository.changes.map { }, settings.settings.drop(1).map { })
            .debounce(300)
            .onEach { changed() }
            .launchIn(scope)
    }

    suspend fun refresh() {
        mutex.withLock {
            val state = repository.current()
            scheduler.reschedule(state, settings.current(), zone = repository.zone)
            runCatching { QuestWidget().updateAll(context) }
        }
    }

    /** After a change: keep the vault mirror fresh (debounced in WorkManager). */
    suspend fun changed() {
        refresh()
        if (settings.current().vaultUri != null) VaultWorker.mirrorSoon(context)
    }
}
