// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import android.content.Context
import dev.dreamweave.quest.data.db.QuestDatabase
import dev.dreamweave.quest.data.sync.KtorSyncTransport
import dev.dreamweave.quest.data.sync.SyncCredentials
import dev.dreamweave.quest.data.sync.SyncEngine

/** Manual dependency container for the data layer; the app keeps one per process. */
class DataContainer(context: Context) {
    private val app = context.applicationContext
    val database: QuestDatabase = QuestDatabase.open(app)
    val deviceId: String = DeviceIdentity.get(app)
    val repository = QuestRepository(database.events(), deviceId)
    val actions = QuestActions(repository)
    val settings = SettingsStore(app)
    val sync: SyncEngine by lazy { SyncEngine(repository, settings, SyncCredentials(app), KtorSyncTransport.create()) }
}
