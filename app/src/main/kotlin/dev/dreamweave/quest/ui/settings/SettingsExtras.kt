// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.settings

import androidx.compose.runtime.Composable
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.ui.ExternalRequest
import dev.dreamweave.quest.ui.QuestViewModel

/** Settings sections owned by other features (filled in by their phases). */
object SettingsExtras {
    @Composable
    fun reminders(vm: QuestViewModel, settings: AppSettings) = Unit

    @Composable
    fun sync(vm: QuestViewModel, state: QuestState, settings: AppSettings, pairRequest: ExternalRequest.Pair?, onPairHandled: () -> Unit) = Unit

    @Composable
    fun vault(vm: QuestViewModel, state: QuestState, settings: AppSettings) = Unit
}
