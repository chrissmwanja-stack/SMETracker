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
        Log.i(TAG, "onCreate: isRunningInstrumentedTest=$isInstrumentedTest")

        if (isInstrumentedTest) {
            Log.i(TAG, "Instrumented test detected. Configuring emulators only; skipping App Check.")
            configureFirebaseEmulatorsForInstrumentedTests()

            // App Check is deliberately NOT installed here. The local Auth/Firestore
            // emulators (see configureFirebaseEmulatorsForInstrumentedTests() above)
            // don't simulate App Check enforcement at all - that's a production-only
            // gate on the real backend (see Firebase's own App Check docs: enforcement
            // blocks "emulator or CI environments" specifically because they aren't a
            // valid device, which only matters when the *destination* is the real
            // backend). Every instrumented test here talks to 10.0.2.2, not
            // production, so App Check has nothing to protect in this path.
            //
            // The DebugAppCheckProviderFactory still needs a real network round-trip
            // to Firebase's production App Check backend to exchange the debug secret
            // for a token, and needs that secret pre-registered in the console's debug
            // token allow-list to succeed. Neither of those has anything to do with
            // what PhoneAuthEmulatorTest (or any other instrumented test here) is
            // actually verifying, so it was pure unnecessary risk: a slow/unavailable
            // network, or a debug token that was never registered, could stall or
            // fail app startup before the test even gets to run its own assertions -
            // and any such failure would look exactly like a Phone Auth problem from
            // the test's perspective, since it happens inside the same Application
            // .onCreate() the test relies on.
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
                "FirebaseAuth configured for emulator at $AUTH_EMULATOR_HOST:$AUTH_EMULATOR_PORT"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure FirebaseAuth emulator: ${e.message}")
        }

        try {
            FirebaseFirestore.getInstance().useEmulator(
                FIRESTORE_EMULATOR_HOST,
                FIRESTORE_EMULATOR_PORT
            )

            Log.i(
                TAG,
                "FirebaseFirestore configured for emulator at $FIRESTORE_EMULATOR_HOST:$FIRESTORE_EMULATOR_PORT"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure FirebaseFirestore emulator: ${e.message}")
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