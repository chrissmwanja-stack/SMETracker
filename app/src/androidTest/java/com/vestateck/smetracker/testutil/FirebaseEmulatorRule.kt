package com.vestateck.smetracker.testutil

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
 * host machine's loopback. This does NOT work on a physical device or a
 * cloud device farm — those need firebase.json emulators configured with
 * "host": "0.0.0.0" and the real LAN IP here instead.
 *
 * Usage:
 * ```
 * @get:Rule val emulator = FirebaseEmulatorRule()
 *
 * @Test
 * fun signsInWithPhoneNumber() = runTest {
 *     val scenario = ActivityScenario.launch(EmulatorHostActivity::class.java)
 *     scenario.onActivity { activity ->
 *         // launch a coroutine from a test-appropriate scope
 *     }
 *     val user = emulator.signInWithPhoneNumber("+15555550123", activity)
 *     assertThat(user.phoneNumber).isEqualTo("+15555550123")
 * }
 * ```
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
            // useEmulator() throws if called more than once per process, so
            // this only happens on the first test in the process.
            auth.useEmulator(emulatorHost, authPort)
            auth.firebaseAuthSettings.setAppVerificationDisabledForTesting(true)
            firestore.useEmulator(emulatorHost, firestorePort)
        }

        // Reset state between tests regardless of whether we just configured
        // the emulators — this is safe to call every time.
        blockingHttp("DELETE", authEmulatorUrl("accounts"))
        blockingHttp("DELETE", firestoreEmulatorUrl("documents"))
        auth.signOut()
    }

    override fun finished(description: Description) {
        auth.signOut()
        super.finished(description)
    }

    /**
     * Runs a real verifyPhoneNumber() flow against the Auth emulator: sends
     * the code, pulls it back out of the emulator's verificationCodes
     * endpoint (the emulator never sends a real SMS), and completes
     * signInWithCredential.
     *
     * [activity] must be a live Activity — verifyPhoneNumber() requires one
     * to attach to. In an instrumented test, get this from
     * ActivityScenario.launch(EmulatorHostActivity::class.java).
     */
    suspend fun signInWithPhoneNumber(
        phoneNumber: String,
        activity: Activity,
        timeoutSeconds: Long = 30L
    ): FirebaseUser {
        val credential = suspendCancellableCoroutine<PhoneAuthCredential> { cont ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    // Auto-verification path (rare against the emulator, but
                    // handle it so the rule works if Google ever changes this).
                    cont.resume(credential, null)
                }

                override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                    cont.resumeWithException(e)
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    try {
                        val code = fetchVerificationCode(phoneNumber)
                        cont.resume(PhoneAuthProvider.getCredential(verificationId, code), null)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            }

            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(phoneNumber)
                .setTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()

            PhoneAuthProvider.verifyPhoneNumber(options)
        }

        return auth.signInWithCredential(credential).await().user
            ?: error("signInWithCredential succeeded but returned no user")
    }

    /**
     * Polls GET /emulator/v1/projects/{projectId}/verificationCodes for the
     * most recent code issued to [phoneNumber]. The emulator can take a
     * moment to record it after onCodeSent fires, so this retries briefly.
     */
    private fun fetchVerificationCode(phoneNumber: String, retries: Int = 10): String {
        repeat(retries) { attempt ->
            val body = blockingHttp("GET", authEmulatorUrl("verificationCodes"))
            val codes = JSONObject(body).optJSONArray("verificationCodes")
            if (codes != null) {
                for (i in codes.length() - 1 downTo 0) {
                    val entry = codes.getJSONObject(i)
                    if (entry.optString("phoneNumber") == phoneNumber) {
                        // Field is "code" in current emulator versions; fall
                        // back to "sessionInfo" if Google renames it again.
                        return entry.optString("code", entry.optString("sessionInfo"))
                    }
                }
            }
            if (attempt < retries - 1) Thread.sleep(300)
        }
        error("No verification code recorded for $phoneNumber after $retries attempts — is the Auth emulator running on $emulatorHost:$authPort?")
    }

    private fun authEmulatorUrl(path: String) =
        "http://$emulatorHost:$authPort/emulator/v1/projects/$projectId/$path"

    private fun firestoreEmulatorUrl(path: String) =
        "http://$emulatorHost:$firestorePort/emulator/v1/projects/$projectId/databases/(default)/$path"

    private fun blockingHttp(method: String, urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 2_000 // Reduced from 5s to 2s
            connection.readTimeout = 2_000    // Reduced from 5s to 2s
            val responseCode = try {
                connection.responseCode
            } catch (e: Exception) {
                throw IllegalStateException("Failed to connect to emulator at $urlString. Is the Firebase Emulator Suite running? Error: ${e.message}", e)
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
        // useEmulator() can only be called once per FirebaseAuth/FirebaseFirestore
        // instance per process; guard it since the instrumentation process is
        // often reused across test classes.
        private val emulatorsConfigured = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

/**
 * Runs [block] on Dispatchers.IO — convenience for the blocking-HTTP helpers
 * above if you want to call them outside starting()/finished().
 */
suspend fun <T> withIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }