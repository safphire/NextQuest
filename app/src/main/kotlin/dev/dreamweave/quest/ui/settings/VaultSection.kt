// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.components.SectionTitle
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format
import dev.dreamweave.quest.vault.ImportReport
import dev.dreamweave.quest.vault.VaultWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vault folder, mirror and snapshot backups (export / import). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultSection(vm: QuestViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val c = (context.applicationContext as QuestApp).container
    val colors = Quest.colors
    var report by remember { mutableStateOf<ImportReport?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.updateSettings { it.copy(vaultUri = uri.toString()) }
            VaultWorker.mirrorSoon(context, snapshot = true, delaySeconds = 0)
            vm.message("Vault set. Notes go to “Quests/”, daily backups to “Quests/.backups/”.")
        }
    }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            vm.launch {
                val ok = runCatching {
                    val json = c.backups.export()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: error("no stream")
                    }
                }.isSuccess
                vm.message(if (ok) "Snapshot saved." else "Couldn't save the snapshot there.")
            }
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
                }
                report = if (text == null) ImportReport(0, 0, emptyList(), emptyList(), "Couldn't read that file.") else c.backups.import(text)
            }
        }
    }

    SectionTitle("Vault & backups")
    QuestCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val folderName = remember(settings.vaultUri) {
                settings.vaultUri?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() }
            }
            Text("Vault mirror", style = MaterialTheme.typography.titleSmall, color = colors.text)
            Text(
                if (settings.vaultUri == null) {
                    "Pick a folder (e.g. your Obsidian vault). Each quest becomes a Markdown note, and a full backup is written there every day. One-way: edits in the folder don't come back."
                } else {
                    "Mirroring to “${folderName ?: "your folder"}”." +
                        (settings.lastVaultSnapshotAt?.let { " Last backup ${Format.longDate(it, vm.zone)}." } ?: "")
                },
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.vaultUri == null) {
                    LimeButton("Choose folder", onClick = { pickFolder.launch(null) })
                } else {
                    QuietButton("Mirror now", onClick = {
                        VaultWorker.mirrorSoon(context, delaySeconds = 0)
                        vm.message("Mirroring…")
                    })
                    QuietButton("Back up now", onClick = {
                        VaultWorker.mirrorSoon(context, snapshot = true, delaySeconds = 0)
                        vm.message("Backing up…")
                    })
                    QuietButton("Change folder", onClick = { pickFolder.launch(null) })
                    TextButton(onClick = {
                        settings.vaultUri?.let { u ->
                            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        }
                        vm.updateSettings { it.copy(vaultUri = null) }
                    }) { Text("Stop mirroring") }
                }
            }
            Spacer(Modifier.height(4.dp))
            MetaLabel("Snapshot", secondary = true)
            Text(
                "A snapshot is the complete history in one JSON file. Importing merges it with what's here — nothing is deleted.",
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Export", onClick = { exportTo.launch("quests-snapshot-${Format.dateOf(System.currentTimeMillis(), vm.zone)}.json") })
                QuietButton("Import", onClick = { importFrom.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            }
        }
    }

    report?.let { r ->
        AlertDialog(
            onDismissRequest = { report = null },
            containerColor = colors.surface,
            title = { Text(if (r.error == null) "Import finished" else "Couldn't import") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (r.error != null) {
                        Text(r.error)
                    } else {
                        Text("${r.added} new events merged, ${r.alreadyHad} already here.")
                        if (r.skipped.isNotEmpty()) Text("${r.skipped.size} kept in the log but not applied (they conflict with your current quests).")
                        if (r.rejected.isNotEmpty()) Text("${r.rejected.size} damaged entries ignored.")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text("OK") } },
        )
    }
}
