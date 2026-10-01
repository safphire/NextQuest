// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest

import android.app.Application
import androidx.work.Configuration
import dev.dreamweave.quest.data.DataContainer
import dev.dreamweave.quest.presence.Notifications
import dev.dreamweave.quest.presence.Presence
import dev.dreamweave.quest.presence.ReminderScheduler
import dev.dreamweave.quest.sync.SyncWorker
import dev.dreamweave.quest.vault.Backups
import dev.dreamweave.quest.vault.VaultWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Also a WorkManager [Configuration.Provider], so WorkManager initializes on demand
 * wherever the startup provider hasn't run (Robolectric tests, some OEM restore paths).
 */
class QuestApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.presence.start(container.appScope)
        VaultWorker.scheduleDaily(this)
        // Covers app updates and process restarts in addition to the system broadcasts.
        container.appScope.launch { container.presence.refresh() }
        // Sync: periodically while paired, and shortly after every local change (debounced).
        if (container.sync.isPaired) {
            SyncWorker.schedulePeriodic(this)
            SyncWorker.now(this)
        }
        container.repository.changes
            .filter { it.local && container.sync.isPaired }
            .onEach { SyncWorker.soon(this) }
            .launchIn(container.appScope)
    }
}

/** Process-wide dependencies (manual DI). */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val data = DataContainer(app)
    val repository get() = data.repository
    val actions get() = data.actions
    val settings get() = data.settings
    val sync get() = data.sync
    val scheduler = ReminderScheduler(app)
    val presence = Presence(app, data.repository, data.settings, scheduler)
    val backups = Backups(data.repository)
}
