package com.vestateck.smetracker.data.remote.sync

import android.content.Context
import androidx.work.*
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.remote.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that flushes offline local database changes (pendingSync = 1)
 * to Cloud Firestore whenever internet connectivity is available.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val database = SMEDatabase.getDatabase(applicationContext)
            val sessionManager = SessionManager(applicationContext)

            val syncEngine = SyncEngine(
                smeDao = database.smeDao(),
                inventoryDao = database.inventoryDao(),
                sessionManager = sessionManager,
                externalScope = this,
                context = applicationContext
            )

            // Trigger push for all locally queued mutations
            syncEngine.requestPush()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "SMETracker_PeriodicSync"
        private const val ONE_TIME_WORK_NAME = "SMETracker_ImmediateSync"

        /** Call on App startup (e.g., MainActivity) to schedule background periodic synchronization */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        /** Call after offline edits to queue an immediate retry as soon as network connects */
        fun triggerImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
        }
    }
}