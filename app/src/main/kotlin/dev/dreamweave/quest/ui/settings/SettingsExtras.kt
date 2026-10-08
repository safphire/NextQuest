// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.presence.Notifications
import dev.dreamweave.quest.ui.ExternalRequest
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.theme.Quest

/** Settings sections owned by other features. */
object SettingsExtras {
    /**
     * What the system currently allows, with the fallback that applies when it doesn't —
     * reminders never silently disappear.
     */
    @Composable
    fun reminders(vm: QuestViewModel, settings: AppSettings) {
        val context = LocalContext.current
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
        val scheduler = (context.applicationContext as QuestApp).container.scheduler
        val notifications = remember(lifecycle) { Notifications.canPost(context) }
        val exact = remember(lifecycle) { scheduler.canScheduleExact() }
        val fullScreen = remember(lifecycle) { Notifications.canUseFullScreen(context) }
        if (lifecycle != Lifecycle.State.RESUMED && lifecycle != Lifecycle.State.STARTED) return
        if (notifications && exact && fullScreen) return
        Spacer(Modifier.height(8.dp))
        QuestCard {
            Column {
                MetaLabel("Permissions")
                if (!notifications) {
                    PermissionRow(
                        "Notifications are off",
                        "Reminders, the digest and the streak guard can't appear.",
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
                if (!exact && Build.VERSION.SDK_INT >= 31) {
                    PermissionRow(
                        "Exact alarms not allowed",
                        "Reminders still come, but may be a few minutes late.",
                    ) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }
                if (!fullScreen && Build.VERSION.SDK_INT >= 34) {
                    PermissionRow(
                        "Lock-screen alarms not allowed",
                        "Step alarms show as a heads-up notification instead of over the lock screen.",
                    ) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
        }
    }

    @Composable
    private fun PermissionRow(title: String, body: String, onAllow: () -> Unit) {
        val c = Quest.colors
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            QuietButton("Allow", onClick = { runCatching(onAllow) })
        }
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    fun sync(vm: QuestViewModel, state: QuestState, settings: AppSettings, pairRequest: ExternalRequest.Pair?, onPairHandled: () -> Unit) =
        SyncSection(vm, settings, pairRequest, onPairHandled)

    @Composable
    fun vault(vm: QuestViewModel, state: QuestState, settings: AppSettings) = VaultSection(vm, settings)
}
