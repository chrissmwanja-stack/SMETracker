package com.vestateck.smetracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Root of the Hilt dependency graph (see di/ package for the modules that
 * actually provide things). Also implements WorkManager's
 * Configuration.Provider so SyncWorker can be a @HiltWorker rather than
 * hand-constructing SMEDatabase/SessionManager/SyncEngine itself the way it
 * used to - WorkManager auto-detects this Application implementing
 * Configuration.Provider and uses workManagerConfiguration below instead of
 * its default configuration, no manifest changes required.
 */
@HiltAndroidApp
class SMETrackerApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}