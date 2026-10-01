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
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.util.Format
import java.util.concurrent.TimeUnit

/** Mirrors notes and writes the day's snapshot into the vault folder, if one is set. */
class VaultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as QuestApp).container
        val settings = c.settings.current()
        val uri = settings.vaultUri?.let(Uri::parse) ?: return Result.success()
        return try {
            val state = c.repository.current()
            val writer = VaultWriter(applicationContext)
            writer.mirror(uri, state, c.repository.zone)
            if (inputData.getBoolean(KEY_SNAPSHOT, false)) {
                val now = System.currentTimeMillis()
                writer.writeSnapshot(uri, Backups(c.repository).export(), Format.dateOf(now, c.repository.zone).toString())
                c.settings.update { it.copy(lastVaultSnapshotAt = now) }
            }
            Result.success()
        } catch (e: Exception) {
            // Folder removed or permission revoked: try again later, Settings shows the problem.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_SNAPSHOT = "snapshot"
        private const val DAILY = "vault-daily"
        private const val MIRROR = "vault-mirror"

        /** Daily snapshot + mirror. Safe to call repeatedly. */
        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<VaultWorker>(1, TimeUnit.DAYS)
                .setInputData(androidx.work.workDataOf(KEY_SNAPSHOT to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Mirror soon after a change (debounced: a newer request replaces a pending one). */
        fun mirrorSoon(context: Context, snapshot: Boolean = false, delaySeconds: Long = 20) {
            val request = OneTimeWorkRequestBuilder<VaultWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setInputData(androidx.work.workDataOf(KEY_SNAPSHOT to snapshot))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MIRROR, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
