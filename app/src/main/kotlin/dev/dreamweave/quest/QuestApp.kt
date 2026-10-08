// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import dev.dreamweave.quest.data.DataContainer
import dev.dreamweave.quest.presence.Notifications
import dev.dreamweave.quest.presence.Presence
import dev.dreamweave.quest.presence.ReminderScheduler
import dev.dreamweave.quest.sync.SyncWorker
import dev.dreamweave.quest.vault.Backups
import dev.dreamweave.quest.vault.VaultWorker
import kotlinx.coroutines.CoroutineExceptionHandler
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
        container.appScope.launch {
            container.sync.reconcile() // a restored install has settings but no token
            if (container.sync.isPaired) {
                SyncWorker.schedulePeriodic(this@QuestApp)
                SyncWorker.now(this@QuestApp)
            }
        }
        container.repository.changes
            .filter { it.local && container.sync.isPaired }
            .onEach { SyncWorker.soon(this) }
            .launchIn(container.appScope)
    }
}

/** Process-wide dependencies (manual DI). */
class AppContainer(app: Application) {
    /** Background work (alarms, widget, sync scheduling). A failure there is logged, never fatal. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("QuestApp", "Background task failed", e) },
    )
    val data = DataContainer(app)
    val repository get() = data.repository
    val actions get() = data.actions
    val settings get() = data.settings
    val sync get() = data.sync
    val scheduler = ReminderScheduler(app)
    val presence = Presence(app, data.repository, data.settings, scheduler)
    val backups = Backups(data.repository)
}
