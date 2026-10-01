// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.sync.SyncWorker
import dev.dreamweave.quest.ui.ExternalRequest
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.components.SectionTitle
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format
import kotlinx.coroutines.launch

/**
 * Pairing with a self-hosted sync server, and its status. A pairing link (QR code on the
 * server page) only pre-fills the form: the user always sees the server address and
 * confirms, so a link from elsewhere can't silently send the quest log to a stranger.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncSection(vm: QuestViewModel, settings: AppSettings, pairRequest: ExternalRequest.Pair?, onPairHandled: () -> Unit) {
    val context = LocalContext.current
    val c = (context.applicationContext as QuestApp).container
    val colors = Quest.colors
    val scope = rememberCoroutineScope()

    var server by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(0) }

    LaunchedEffect(pairRequest) {
        if (pairRequest != null) {
            server = pairRequest.serverUrl
            code = pairRequest.code
            error = null
            onPairHandled()
        }
    }
    LaunchedEffect(settings.lastSyncAt, settings.serverUrl) { pending = c.repository.unpushedCount() }

    fun pair() {
        if (!server.trim().startsWith("https://")) {
            error = "Use the server's https:// address (Android doesn't allow unencrypted connections)."
            return
        }
        busy = true
        error = null
        scope.launch {
            val deviceName = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Android phone" }
            val problem = c.sync.pair(server, code, deviceName)
            busy = false
            if (problem == null) {
                code = ""
                SyncWorker.schedulePeriodic(context)
                vm.message("Paired. Changes now sync with your server.")
            } else {
                error = problem
            }
        }
    }

    SectionTitle("Sync & AI")
    QuestCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.serverUrl == null) {
                Text("Sync server", style = MaterialTheme.typography.titleSmall, color = colors.text)
                Text(
                    "Optional. Pair with your own server to sync devices and let Claude or ChatGPT read your quests and suggest changes " +
                        "you approve here. On the server page, choose “Pair a device” and scan the QR code, or type its address and code.",
                    style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it.trim() },
                    label = { Text("Server address") },
                    placeholder = { Text("https://quests.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(20) },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pairRequest != null || (server.isNotBlank() && code.isNotBlank())) {
                    Text(
                        "This phone's quests will be sent to $server. Only pair with a server you run or trust.",
                        style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text) }
                LimeButton(if (busy) "Pairing…" else "Pair", onClick = { pair() }, enabled = !busy && server.isNotBlank() && code.isNotBlank())
            } else {
                Text("Synced with ${settings.serverUrl}", style = MaterialTheme.typography.titleSmall, color = colors.text)
                val now = System.currentTimeMillis()
                val status = buildList {
                    add(settings.lastSyncAt?.let { "Last sync ${Format.age(it, now).let { a -> if (a == "now") "just now" else "$a ago" }}." } ?: "Not synced yet.")
                    if (pending > 0) add("$pending change(s) waiting to upload.")
                    settings.lastSyncError?.let { add(it) }
                }.joinToString(" ")
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (settings.lastSyncError != null) colors.text else colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton("Sync now", onClick = {
                        SyncWorker.now(context)
                        vm.message("Syncing…")
                    })
                    TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            containerColor = colors.surface,
            title = { Text("Disconnect from the server?") },
            text = { Text("Nothing is deleted on this phone or on the server. Proposals from AI stop arriving until you pair again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    SyncWorker.cancel(context)
                    scope.launch { c.sync.disconnect() }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}
