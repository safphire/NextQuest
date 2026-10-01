// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest

import android.app.Application
import dev.dreamweave.quest.data.DataContainer

class QuestApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Process-wide dependencies (manual DI). */
class AppContainer(app: Application) {
    val data = DataContainer(app)
    val repository get() = data.repository
    val actions get() = data.actions
    val settings get() = data.settings
}
