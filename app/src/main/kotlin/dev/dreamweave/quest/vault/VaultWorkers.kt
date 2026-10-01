// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.vault

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.protocol.SnapshotCodec
import dev.dreamweave.quest.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Writes the day's snapshot (when asked) and mirrors notes into the vault folder, if one
 * is set. The two are independent: a failing mirror never costs the backup, and the other
 * way round. Problems are kept in [VaultStatus] for Settings.
 */
class VaultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as QuestApp).container
        val wantSnapshot = inputData.getBoolean(KEY_SNAPSHOT, false)
        val writer = VaultWriter(applicationContext)
        val errors = mutableListOf<String>()
        var snapshotAt: Long? = null

        // One writer at a time across every vault job; once started, a run goes to the end.
        val hasVault = withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                // Read inside the lock: a job queued for a folder the user just replaced writes to the new one.
                val uri = c.settings.current().vaultUri?.let(Uri::parse) ?: return@withLock false
                val zone = c.repository.zone
                if (wantSnapshot) {
                    try {
                        val snapshot = c.backups.snapshot()
                        // Nothing to back up yet (fresh install): never add an empty snapshot.
                        if (snapshot.events.isNotEmpty()) {
                            val now = snapshot.exportedAt
                            writer.writeSnapshot(uri, SnapshotCodec.encode(snapshot), Format.dateOf(now, zone).toString(), timeOf(now, zone.id))
                            snapshotAt = now
                        }
                    } catch (e: Exception) {
                        errors += VaultStatus.describe("Backup", e)
                    }
                }
                try {
                    writer.mirror(uri, c.repository.current(), zone)
                } catch (e: Exception) {
                    errors += VaultStatus.describe("Mirror", e)
                }
                true
            }
        }
        if (!hasVault) return Result.success()
        snapshotAt?.let { at -> c.settings.update { it.copy(lastVaultSnapshotAt = at) } }
        if (errors.isEmpty()) {
            VaultStatus.clearError(applicationContext)
            return Result.success()
        }
        VaultStatus.recordError(applicationContext, errors.joinToString("\n"))
        // Folder removed or permission revoked: try again later; Settings shows the problem.
        return if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        const val KEY_SNAPSHOT = "snapshot"
        private const val DAILY = "vault-daily"
        private const val MIRROR = "vault-mirror"
        private const val SNAPSHOT = "vault-snapshot"

        /** Serialises every vault write in this process (daily job, mirror, backup now). */
        private val lock = Mutex()

        private fun timeOf(ms: Long, zoneId: String): String = runCatching {
            DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneId.of(zoneId)).format(Instant.ofEpochMilli(ms))
        }.getOrDefault("000000")

        /** Daily snapshot + mirror. Safe to call repeatedly. */
        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<VaultWorker>(1, TimeUnit.DAYS)
                .setInputData(workDataOf(KEY_SNAPSHOT to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Mirror soon after a change. Plain mirrors are debounced (a newer request replaces a
         * pending one); backup requests have their own queue, so a later mirror can never
         * cancel a pending backup, and a backup also mirrors.
         */
        fun mirrorSoon(context: Context, snapshot: Boolean = false, delaySeconds: Long = 20) {
            val request = OneTimeWorkRequestBuilder<VaultWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_SNAPSHOT to snapshot))
                .build()
            val wm = WorkManager.getInstance(context)
            if (snapshot) {
                wm.enqueueUniqueWork(SNAPSHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            } else {
                wm.enqueueUniqueWork(MIRROR, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
