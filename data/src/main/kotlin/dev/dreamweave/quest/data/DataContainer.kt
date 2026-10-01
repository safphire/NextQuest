// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import android.content.Context
import dev.dreamweave.quest.data.db.QuestDatabase

/** Manual dependency container for the data layer; the app keeps one per process. */
class DataContainer(context: Context) {
    private val app = context.applicationContext
    val database: QuestDatabase = QuestDatabase.open(app)
    val deviceId: String = DeviceIdentity.get(app)
    val repository = QuestRepository(database.events(), deviceId)
    val actions = QuestActions(repository)
    val settings = SettingsStore(app)
}
