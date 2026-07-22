package com.vestateck.smetracker.testutil

import android.app.Activity
import android.util.Log
import com.google.firebase.FirebaseApp
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Points [FirebaseAuth] and [FirebaseFirestore] at the Local Emulator Suite
 * (see firebase.json: auth 9099, firestore 8080) and gives tests a way to
 * drive a real phone-auth sign-in against the Auth emulator.
 *
 * "10.0.2.2" is the special alias the Android *emulator* (AVD) NATs to the
 * host machine's loopback.
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

        if (emulatorsConfigured.compareAndSet(false, true)) {
            auth.useEmulator(emulatorHost, authPort)
            auth.firebaseAuthSettings.setAppVerificationDisabledForTesting(true)
            firestore.useEmulator(emulatorHost, firestorePort)
        }

        try {
            blockingHttp("DELETE", authEmulatorUrl("accounts"))
            blockingHttp("DELETE", firestoreEmulatorUrl("documents"))
        } catch (e: Exception) {
            Log.w("FirebaseEmulatorRule", "Cleanup failed (emulator might not be reachable): ${e.message}")
        }
        auth.signOut()
    }

    override fun finished(description: Description) {
        auth.signOut()
        super.finished(description)
    }

    /**
     * Runs a real verifyPhoneNumber() flow against the Auth emulator.
     */
    suspend fun signInWithPhoneNumber(
        phoneNumber: String,
        activity: Activity,
        timeoutSeconds: Long = 30L
    ): FirebaseUser = withContext(Dispatchers.IO) {
        // withContext(Dispatchers.IO) ensures we use real time for the timeout
        // even when called from runTest, and prevents blocking the test thread.
        withTimeout(TimeUnit.SECONDS.toMillis(timeoutSeconds + 10)) {
            val credential = suspendCancellableCoroutine<PhoneAuthCredential> { cont ->
                val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                        if (cont.isActive) cont.resume(credential)
                    }

                    override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onCodeSent(
                        verificationId: String,
                        token: PhoneAuthProvider.ForceResendingToken
                    ) {
                        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                        executor.execute {
                            try {
                                val code = fetchVerificationCode(phoneNumber)
                                if (cont.isActive) {
                                    cont.resume(PhoneAuthProvider.getCredential(verificationId, code))
                                }
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resumeWithException(e)
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

                activity.runOnUiThread {
                    PhoneAuthProvider.verifyPhoneNumber(options)
                }
            }

            auth.signInWithCredential(credential).await().user
                ?: error("signInWithCredential succeeded but returned no user")
        }
    }

    private fun fetchVerificationCode(phoneNumber: String, retries: Int = 10): String {
        repeat(retries) { attempt ->
            val body = blockingHttp("GET", authEmulatorUrl("verificationCodes"))
            val codes = JSONObject(body).optJSONArray("verificationCodes")
            if (codes != null) {
                for (i in codes.length() - 1 downTo 0) {
                    val entry = codes.getJSONObject(i)
                    if (entry.optString("phoneNumber") == phoneNumber) {
                        return entry.optString("code", entry.optString("sessionInfo"))
                    }
                }
            }
            if (attempt < retries - 1) Thread.sleep(300)
        }
        error("No verification code recorded for $phoneNumber after $retries attempts")
    }

    private fun authEmulatorUrl(path: String) =
        "http://$emulatorHost:$authPort/emulator/v1/projects/$projectId/$path"

    private fun firestoreEmulatorUrl(path: String) =
        "http://$emulatorHost:$firestorePort/emulator/v1/projects/$projectId/databases/(default)/$path"

    private fun blockingHttp(method: String, urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val responseCode = try {
                connection.responseCode
            } catch (e: Exception) {
                throw IllegalStateException("Failed to connect to emulator at $urlString. Error: ${e.message}", e)
            }
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: "{}"
        } finally {
            connection.disconnect()
        }
    }

    private fun BufferedReader.readText(): String = readLines().joinToString("\n")

    companion object {
        private val emulatorsConfigured = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

suspend fun <T> withIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }