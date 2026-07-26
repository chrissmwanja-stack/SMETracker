package com.vestateck.smetracker

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

private const val TAG = "SMETrackerApplication"

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

        val isInstrumentedTest = isRunningInstrumentedTest()
        Log.i(TAG, "onCreate: isRunningInstrumentedTest=\$isInstrumentedTest")

        if (isInstrumentedTest) {
            Log.i(TAG, "Instrumented test detected. Configuring emulators and disabling App Check token auto-refresh.")
            configureFirebaseEmulatorsForInstrumentedTests()

            // Install Debug provider for App Check in instrumented tests so that
            // requests to emulators (Auth/Firestore) don't fail due to missing tokens
            // or attempts to reach production App Check.
            try {
                Firebase.appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Firebase.appCheck.setTokenAutoRefreshEnabled(false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure App Check for tests: ${e.message}")
            }
            return
        }

        installFirebaseAppCheck()
    }

    private fun installFirebaseAppCheck() {
        Log.i(TAG, "Installing Firebase App Check")
        val providerFactory = if (BuildConfig.DEBUG) {
            DebugAppCheckProviderFactory.getInstance()
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }

        Firebase.appCheck.installAppCheckProviderFactory(providerFactory)
    }

    /**
     * Instrumented tests run inside an Android device/emulator and should use
     * the local Firebase Emulator Suite instead of production Firebase.
     *
     * 10.0.2.2 is the Android Emulator's special alias for the host machine.
     */
    private fun configureFirebaseEmulatorsForInstrumentedTests() {
        try {
            FirebaseAuth.getInstance().useEmulator(AUTH_EMULATOR_HOST, AUTH_EMULATOR_PORT)
            FirebaseAuth.getInstance()
                .firebaseAuthSettings
                .setAppVerificationDisabledForTesting(true)

            Log.i(
                TAG,
                "FirebaseAuth configured for emulator at \$AUTH_EMULATOR_HOST:\$AUTH_EMULATOR_PORT"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure FirebaseAuth emulator: \${e.message}")
        }

        try {
            FirebaseFirestore.getInstance().useEmulator(
                FIRESTORE_EMULATOR_HOST,
                FIRESTORE_EMULATOR_PORT
            )

            Log.i(
                TAG,
                "FirebaseFirestore configured for emulator at \$FIRESTORE_EMULATOR_HOST:\$FIRESTORE_EMULATOR_PORT"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure FirebaseFirestore emulator: \${e.message}")
        }
    }

    /**
     * Detects Android instrumentation tests.
     *
     * androidx.test.platform.app.InstrumentationRegistry is the modern AndroidX
     * test registry class. The second check keeps compatibility with older
     * AndroidX test setups.
     */
    private fun isRunningInstrumentedTest(): Boolean {
        return classExists("androidx.test.platform.app.InstrumentationRegistry") ||
                classExists("androidx.test.InstrumentationRegistry")
    }

    private fun classExists(className: String): Boolean {
        return try {
            Class.forName(className)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    companion object {
        private const val AUTH_EMULATOR_HOST = "10.0.2.2"
        private const val AUTH_EMULATOR_PORT = 9099

        private const val FIRESTORE_EMULATOR_HOST = "10.0.2.2"
        private const val FIRESTORE_EMULATOR_PORT = 8080
    }
}
