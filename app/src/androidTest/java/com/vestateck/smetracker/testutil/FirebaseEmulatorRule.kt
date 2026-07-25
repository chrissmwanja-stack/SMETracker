package com.vestateck.smetracker.testutil

import android.app.Activity
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "FirebaseEmulatorRule"

/**
 * Points FirebaseAuth and FirebaseFirestore at the Local Emulator Suite
 * and gives tests a way to drive a real phone-auth sign-in against the
 * Firebase Auth emulator.
 *
 * Expected firebase.json ports:
 * - Auth: 9099
 * - Firestore: 8080
 *
 * From an Android emulator/AVD, use 10.0.2.2 to reach the host machine.
 */
class FirebaseEmulatorRule(
    private val emulatorHost: String = "10.0.2.2",
    private val authPort: Int = 9099,
    private val firestorePort: Int = 8080
) : TestWatcher() {

    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val projectId: String by lazy {
        FirebaseApp.getInstance().options.projectId
            ?: error("FirebaseApp has no projectId — check google-services.json")
    }

    override fun starting(description: Description) {
        super.starting(description)

        Log.d(TAG, "Starting test: ${description.methodName}")

        configureFirebaseEmulators()

        // Fail fast if emulator is unreachable to avoid confusing FirebaseAuth
        // Play Integrity/reCAPTCHA errors later.
        try {
            val response = blockingHttp("GET", authEmulatorUrl(""))
            Log.i(TAG, "Auth emulator reachable at $emulatorHost:$authPort. Response: $response")
        } catch (e: Exception) {
            val msg = "Firebase Auth emulator is NOT reachable at $emulatorHost:$authPort. " +
                    "1. Run 'firebase emulators:start --only auth,firestore' on your host machine. " +
                    "2. Ensure firebase.json has host 0.0.0.0 or localhost access is available. " +
                    "3. Check your firewall settings. " +
                    "Error: ${e.message}"

            Log.e(TAG, msg, e)
            throw IllegalStateException(msg, e)
        }

        try {
            blockingHttp("DELETE", authEmulatorUrl("accounts"))
            blockingHttp("DELETE", firestoreEmulatorUrl("documents"))
            Log.d(TAG, "Emulator state cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed; continuing because cleanup is non-fatal: ${e.message}")
        }

        auth.signOut()
    }

    override fun finished(description: Description) {
        auth.signOut()
        Log.d(TAG, "Finished test: ${description.methodName}")
        super.finished(description)
    }

    /**
     * This is the important part for your failure.
     *
     * Without auth.useEmulator(...), FirebaseAuth will attempt the real
     * production phone-auth flow, which triggers Play Integrity/reCAPTCHA
     * and causes:
     *
     * "This request is missing a valid app identifier..."
     */
    private fun configureFirebaseEmulators() {
        if (emulatorsConfigured.compareAndSet(false, true)) {
            try {
                auth.useEmulator(emulatorHost, authPort)
                Log.i(TAG, "FirebaseAuth configured to use emulator at $emulatorHost:$authPort")
            } catch (e: Exception) {
                Log.w(TAG, "FirebaseAuth emulator configuration failed: ${e.message}", e)
            }

            try {
                firestore.useEmulator(emulatorHost, firestorePort)
                Log.i(TAG, "FirebaseFirestore configured to use emulator at $emulatorHost:$firestorePort")
            } catch (e: Exception) {
                Log.w(TAG, "FirebaseFirestore emulator configuration failed: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Firebase emulators were already configured in this test process")
        }

        try {
            auth.useEmulator(emulatorHost, authPort)
            firestore.useEmulator(emulatorHost, firestorePort)

            auth.firebaseAuthSettings.setAppVerificationDisabledForTesting(true)

            Log.i(TAG, "Firebase emulators configured from FirebaseEmulatorRule")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure Firebase emulator configuration: ${e.message}", e)
        }
    }

    /**
     * Runs a real verifyPhoneNumber() flow against the Auth emulator.
     */
    suspend fun signInWithPhoneNumber(
        phoneNumber: String,
        activity: Activity,
        timeoutSeconds: Long = 30L
    ): FirebaseUser = withContext(Dispatchers.IO) {
        Log.d(TAG, "signInWithPhoneNumber started for $phoneNumber")

        withTimeout(TimeUnit.SECONDS.toMillis(timeoutSeconds + 10)) {
            val credential = suspendCancellableCoroutine<PhoneAuthCredential> { cont ->
                val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                        Log.d(TAG, "onVerificationCompleted")

                        if (cont.isActive) {
                            cont.resume(credential)
                        }
                    }

                    override fun onVerificationFailed(e: FirebaseException) {
                        Log.e(TAG, "onVerificationFailed", e)

                        if (cont.isActive) {
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onCodeSent(
                        verificationId: String,
                        token: PhoneAuthProvider.ForceResendingToken
                    ) {
                        Log.d(TAG, "onCodeSent: $verificationId")

                        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

                        executor.execute {
                            try {
                                val code = fetchVerificationCode(phoneNumber)
                                Log.d(TAG, "Fetched emulator verification code: $code")

                                if (cont.isActive) {
                                    cont.resume(
                                        PhoneAuthProvider.getCredential(
                                            verificationId,
                                            code
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "fetchVerificationCode failed", e)

                                if (cont.isActive) {
                                    cont.resumeWithException(e)
                                }
                            } finally {
                                executor.shutdown()
                            }
                        }
                    }
                }

                val options = PhoneAuthOptions.newBuilder(auth)
                    .setPhoneNumber(phoneNumber)
                    .setTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .setActivity(activity)
                    .setCallbacks(callbacks)
                    .build()

                Log.d(TAG, "Calling PhoneAuthProvider.verifyPhoneNumber...")

                activity.runOnUiThread {
                    PhoneAuthProvider.verifyPhoneNumber(options)
                }
            }

            Log.d(TAG, "Credential received, signing in...")

            val result = auth.signInWithCredential(credential).await()

            Log.d(TAG, "signInWithCredential completed: ${result.user?.uid}")

            result.user ?: error("signInWithCredential succeeded but returned no user")
        }
    }

    private fun fetchVerificationCode(
        phoneNumber: String,
        retries: Int = 10
    ): String {
        Log.d(TAG, "Polling emulator for verification code for $phoneNumber...")

        repeat(retries) { attempt ->
            try {
                val body = blockingHttp("GET", authEmulatorUrl("verificationCodes"))
                val codes = JSONObject(body).optJSONArray("verificationCodes")

                if (codes != null) {
                    for (i in codes.length() - 1 downTo 0) {
                        val entry = codes.getJSONObject(i)

                        if (entry.optString("phoneNumber") == phoneNumber) {
                            val code = entry.optString("code")

                            if (code.isNotBlank()) {
                                return code
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll attempt ${attempt + 1} failed: ${e.message}")
            }

            if (attempt < retries - 1) {
                Thread.sleep(500)
            }
        }

        error("No verification code recorded for $phoneNumber after $retries attempts")
    }

    private fun authEmulatorUrl(path: String): String {
        return "http://$emulatorHost:$authPort/emulator/v1/projects/$projectId/$path"
    }

    private fun firestoreEmulatorUrl(path: String): String {
        return "http://$emulatorHost:$firestorePort/emulator/v1/projects/$projectId/databases/(default)/$path"
    }

    private fun blockingHttp(
        method: String,
        urlString: String
    ): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = try {
                connection.responseCode
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to connect to emulator at $urlString. Error: ${e.message}",
                    e
                )
            }

            if (responseCode !in 200..299 && method != "DELETE") {
                val errorBody = connection.errorStream?.let {
                    BufferedReader(InputStreamReader(it)).readAllText()
                } ?: "no error body"

                throw IllegalStateException(
                    "Emulator at $urlString returned HTTP $responseCode: $errorBody"
                )
            }

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            stream?.let {
                BufferedReader(InputStreamReader(it)).readAllText()
            } ?: "{}"
        } finally {
            connection.disconnect()
        }
    }

    private fun BufferedReader.readAllText(): String {
        return readLines().joinToString("\n")
    }

    companion object {
        private val emulatorsConfigured = AtomicBoolean(false)
    }
}

suspend fun <T> withIo(block: suspend () -> T): T {
    return withContext(Dispatchers.IO) {
        block()
    }
}