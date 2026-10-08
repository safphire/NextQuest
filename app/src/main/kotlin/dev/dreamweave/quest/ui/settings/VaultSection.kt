// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dev.dreamweave.quest.vault.Backups
import dev.dreamweave.quest.vault.ImportReport
import dev.dreamweave.quest.vault.VaultStatus
import dev.dreamweave.quest.vault.VaultWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val TREE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/** Snapshots are a few MB even after years of use; anything far bigger isn't one. */
private const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

/** True if the app holds a lasting read + write grant on [uri]. */
private fun hasPersistedAccess(context: Context, uri: Uri): Boolean =
    runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }.getOrDefault(false)

private class TooLargeException : Exception()

/** Reads at most [max] bytes; a longer file throws [TooLargeException] before it fills memory. */
private fun readCapped(context: Context, uri: Uri, max: Long): ByteArray? =
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw TooLargeException()
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

private fun failedImport(reason: String) = ImportReport(0, 0, emptyList(), emptyList(), reason)

private suspend fun importSnapshot(context: Context, backups: Backups, uri: Uri): ImportReport {
    val bytes = try {
        withContext(Dispatchers.IO) { readCapped(context, uri, MAX_IMPORT_BYTES) } ?: return failedImport("Couldn't read that file.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: TooLargeException) {
        return failedImport("That file is too large to be a snapshot (over 50 MB).")
    } catch (e: Exception) {
        return failedImport("Couldn't read that file.")
    }
    return try {
        withContext(Dispatchers.Default) { backups.import(bytes.decodeToString()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failedImport("Couldn't import that file. Nothing was changed.")
    } catch (e: OutOfMemoryError) {
        failedImport("That file is too large to import on this phone.")
    }
}

/** Vault folder, mirror and snapshot backups (export / import). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultSection(vm: QuestViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val c = (context.applicationContext as QuestApp).container
    val colors = Quest.colors
    var report by remember { mutableStateOf<ImportReport?>(null) }

    // The worker's last problem, live.
    var vaultError by remember { mutableStateOf(VaultStatus.lastError(context)) }
    DisposableEffect(context) {
        val prefs = VaultStatus.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == null || key == VaultStatus.KEY_ERROR) vaultError = p.getString(VaultStatus.KEY_ERROR, null)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val taken = runCatching { context.contentResolver.takePersistableUriPermission(uri, TREE_FLAGS) }.isSuccess
            if (!taken || !hasPersistedAccess(context, uri)) {
                vm.message("Couldn't get lasting access to that folder. Try another one.")
            } else {
                // "Change folder": give back the grant on the folder we no longer use.
                val old = settings.vaultUri
                if (old != null && old != uri.toString()) {
                    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), TREE_FLAGS) }
                }
                VaultStatus.clearError(context)
                vm.updateSettings { it.copy(vaultUri = uri.toString()) }
                VaultWorker.mirrorSoon(context, snapshot = true, delaySeconds = 0)
                vm.message("Vault set. Notes go to “Quests/”, daily backups to “Quests/.backups/”.")
            }
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
            vm.launch { report = importSnapshot(context, c.backups, uri) }
        }
    }

    SectionTitle("Vault & backups")
    QuestCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val folderName = remember(settings.vaultUri) {
                settings.vaultUri?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() }
            }
            val accessLost = remember(settings.vaultUri, vaultError) {
                settings.vaultUri?.let { !hasPersistedAccess(context, Uri.parse(it)) } ?: false
            }
            Text("Vault mirror", style = MaterialTheme.typography.titleSmall, color = colors.text)
            Text(
                when {
                    settings.vaultUri == null ->
                        "Pick a folder (e.g. your Obsidian vault). Each quest becomes a Markdown note, and a full backup is written there every day. One-way: edits in the folder don't come back."
                    accessLost -> "Folder access lost — choose it again."
                    else -> "Mirroring to “${folderName ?: "your folder"}”." +
                        (settings.lastVaultSnapshotAt?.let { " Last backup ${Format.longDate(it, vm.zone)}." } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (accessLost) colors.text else colors.textSecondary,
            )
            val error = vaultError
            if (settings.vaultUri != null && !accessLost && error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.text)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.vaultUri == null) {
                    LimeButton("Choose folder", onClick = { pickFolder.launch(null) })
                } else {
                    if (accessLost) {
                        LimeButton("Choose folder again", onClick = { pickFolder.launch(null) })
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
                    }
                    TextButton(onClick = {
                        settings.vaultUri?.let { u ->
                            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(u), TREE_FLAGS) }
                        }
                        VaultStatus.clearError(context)
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
                        if (r.rejected.isNotEmpty()) {
                            Text("${r.rejected.size} entries ignored:")
                            r.rejected.take(3).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                            if (r.rejected.size > 3) Text("· and ${r.rejected.size - 3} more", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text("OK") } },
        )
    }
}
