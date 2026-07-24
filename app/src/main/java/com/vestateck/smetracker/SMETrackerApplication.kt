package com.vestateck.smetracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.vestateck.smetracker.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SMETrackerApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Debug builds bypass Play Integrity/reCAPTCHA entirely via the Debug
        // provider (needs a one-time token registered in Firebase Console ->
        // App Check -> manage debug tokens, printed to Logcat on first run).
        // Release builds use real Play Integrity attestation - requires SHA
        // fingerprints registered in Firebase and the Play Integrity API
        // enabled in Google Cloud Console for this project.
        val providerFactory = if (BuildConfig.DEBUG) {
            DebugAppCheckProviderFactory.getInstance()
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }

        Firebase.appCheck.installAppCheckProviderFactory(providerFactory)
    }
}