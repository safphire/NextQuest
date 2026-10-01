// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.data.sync.SyncOutcome
import java.util.concurrent.TimeUnit

/**
 * Runs one [dev.dreamweave.quest.data.sync.SyncEngine] round. Scheduled periodically, when
 * the app opens and shortly after local changes; WorkManager waits for a network and backs
 * off exponentially while the server is unreachable. Unpushed events wait in the event log.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (val outcome = (applicationContext as QuestApp).container.sync.sync()) {
            is SyncOutcome.Ok, SyncOutcome.NotPaired -> Result.success()
            is SyncOutcome.Failed -> if (outcome.retry) Result.retry() else Result.failure()
        }

    companion object {
        private const val SOON = "sync-soon"
        private const val PERIODIC = "sync-periodic"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Debounced: a newer request replaces a pending one, so a burst of edits syncs once. */
        fun soon(context: Context, delaySeconds: Long = 5) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
        }

        fun now(context: Context) = soon(context, delaySeconds = 0)

        /** Every 15 minutes (WorkManager's minimum) while paired, so AI proposals arrive without opening the app. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(SOON)
                cancelUniqueWork(PERIODIC)
            }
        }
    }
}
