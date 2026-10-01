// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest

import android.app.Application
import dev.dreamweave.quest.data.DataContainer
import dev.dreamweave.quest.presence.Notifications
import dev.dreamweave.quest.presence.Presence
import dev.dreamweave.quest.presence.ReminderScheduler
import dev.dreamweave.quest.vault.Backups
import dev.dreamweave.quest.vault.VaultWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QuestApp : Application() {
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
    }
}

/** Process-wide dependencies (manual DI). */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val data = DataContainer(app)
    val repository get() = data.repository
    val actions get() = data.actions
    val settings get() = data.settings
    val scheduler = ReminderScheduler(app)
    val presence = Presence(app, data.repository, data.settings, scheduler)
    val backups = Backups(data.repository)
}
