package com.vestateck.smetracker.testutil

import android.app.Activity
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

private const val TAG = "FirebaseEmulatorRule"
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

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
 *
 * Android 17+ (targetSdk 37) blocks sockets to local-network addresses such as
 * 10.0.2.2 unless the app holds ACCESS_LOCAL_NETWORK. The permission is declared
 * in src/debug/AndroidManifest.xml and granted here before the first connection.
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

    override fun apply(base: org.junit.runners.model.Statement, description: Description): org.junit.runners.model.Statement {
        return object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                starting(description)
                try {
                    base.evaluate()
                    succeeded(description)
                } catch (e: Throwable) {
                    failed(e, description)
                    throw e
                } finally {
                    finished(description)
                }
            }
        }
    }

    override fun starting(description: Description) {
        super.starting(description)

        Log.d(TAG, "Starting test: ${description.methodName}")

        // Must happen before any socket to 10.0.2.2 is opened.
        grantLocalNetworkPermission()

        configureFirebaseEmulators()

        // ALWAYS ensure app verification is disabled, even if emulators were already
        // configured in a previous test. Without this, Phone Auth falls back to
        // real Play Integrity/reCAPTCHA and fails with FirebaseNetworkException
        // in a non-internet test environment.
        try {
            auth.firebaseAuthSettings.setAppVerificationDisabledForTesting(true)
            Log.d(TAG, "App verification disabled for testing")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable app verification: ${e.message}")
        }

        // Fail fast if emulator is unreachable to avoid confusing FirebaseAuth
        // Play Integrity/reCAPTCHA errors later.
        try {
            // Root URL returns 200 {"authEmulator":{"ready":true}}. Do NOT GET /accounts: it is DELETE-only (405).
            val response = blockingHttp("GET", "http://$emulatorHost:$authPort/")
            Log.i(TAG, "Auth emulator reachable at $emulatorHost:$authPort. Response: $response")
        } catch (e: Exception) {
            val msg = "Firebase Auth emulator is NOT reachable at $emulatorHost:$authPort. " +
                    "1. Run 'firebase emulators:start --only auth,firestore' on your host machine. " +
                    "2. Ensure firebase.json has host 0.0.0.0. " +
                    "3. Check your firewall settings. " +
                    "4. On Android 17+, ensure ACCESS_LOCAL_NETWORK is declared in the debug manifest. " +
                    "Error: ${e.message}"

            Log.e(TAG, msg, e)
            throw IllegalStateException(msg, e)
        }

        try {
            blockingHttp("DELETE", authEmulatorUrl("accounts"))
            blockingHttp("DELETE", firestoreEmulatorUrl())
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
     * Android 17 (API 37) blocks local-network sockets for apps targeting 37+
     * unless ACCESS_LOCAL_NETWORK is granted. The adb shell is exempt, which is why
     * `adb shell nc 10.0.2.2 9099` works while the app's own connection times out.
     *
     * Harmless on older Android: the grant simply fails or is not needed.
     */
    private fun grantLocalNetworkPermission() {
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val pkg = context.packageName

            if (context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "ACCESS_LOCAL_NETWORK already granted")
                return
            }

            val pfd = instrumentation.uiAutomation
                .executeShellCommand("pm grant $pkg $LOCAL_NETWORK_PERMISSION")
            // Drain the output so the command has finished before we continue.
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }

            val granted = context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "ACCESS_LOCAL_NETWORK granted=$granted")
        } catch (e: Exception) {
            Log.w(TAG, "Could not grant ACCESS_LOCAL_NETWORK (fine on older Android): ${e.message}")
        }
    }

    /**
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
    }

    /**
     * Runs a real verifyPhoneNumber() flow against the Auth emulator.
     *
     * Time budget: the outer withTimeout is (timeoutSeconds + 10)s, and code polling
     * is capped at CODE_POLL_TIMEOUT_MS, so a slow poll can never eat the whole budget
     * silently; it fails with a message saying what the emulator returned.
     *
     * Session reuse: the Firebase Auth SDK reuses an open verification session for the
     * same phone number for up to timeoutSeconds. Back-to-back tests using the same
     * number would then get onCodeSent instantly with the OLD verificationId and the
     * emulator would never generate a new code (its state was just cleared). Passing
     * the previous ForceResendingToken forces a real backend call every time.
     */
    suspend fun signInWithPhoneNumber(
        phoneNumber: String,
        activity: Activity,
        timeoutSeconds: Long = 30L
    ): FirebaseUser = withContext(Dispatchers.IO) {
        Log.d(TAG, "signInWithPhoneNumber started for $phoneNumber")

        // Codes already recorded for this number (from earlier sign-ins in the same
        // emulator session). They must never be mistaken for the one we're about to
        // request, so snapshot them before calling verifyPhoneNumber.
        val staleCodes = snapshotCodesFor(phoneNumber)

        withTimeout((timeoutSeconds + 10).seconds) {
            val credential = suspendCancellableCoroutine { cont ->
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
                        // Remember the token so the next sign-in with this number can
                        // force a fresh send instead of reusing this session.
                        resendTokens[phoneNumber] = token

                        Log.d(TAG, "onCodeSent: $verificationId")

                        val executor = Executors.newSingleThreadExecutor()

                        executor.execute {
                            try {
                                val code = fetchVerificationCode(phoneNumber, exclude = staleCodes)
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

                    override fun onCodeAutoRetrievalTimeOut(verificationId: String) {
                        // Diagnostic only: tells you in logcat that the SMS-retriever
                        // phase ended for this verification.
                        Log.d(TAG, "onCodeAutoRetrievalTimeOut: $verificationId")
                    }
                }

                val previousToken = resendTokens[phoneNumber]

                val options = PhoneAuthOptions.newBuilder(auth)
                    .setPhoneNumber(phoneNumber)
                    .setTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .setActivity(activity)
                    .setCallbacks(callbacks)
                    .apply { previousToken?.let { setForceResendingToken(it) } }
                    .build()

                Log.d(
                    TAG,
                    "Calling PhoneAuthProvider.verifyPhoneNumber for $phoneNumber " +
                            "(forceResend=${previousToken != null})..."
                )

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

    /**
     * Returns every code the Auth emulator currently has recorded for [phoneNumber].
     * Best-effort: any failure yields an empty set.
     */
    private fun snapshotCodesFor(phoneNumber: String): Set<String> {
        return try {
            val body = blockingHttp("GET", authEmulatorUrl("verificationCodes"), maxRetries = 1)
            codesFor(body, phoneNumber).toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Could not snapshot existing codes for $phoneNumber: ${e.message}")
            emptySet()
        }
    }

    private fun codesFor(body: String, phoneNumber: String): List<String> {
        val codes = JSONObject(body).optJSONArray("verificationCodes") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until codes.length()) {
            val entry = codes.getJSONObject(i)
            if (entry.optString("phoneNumber") == phoneNumber) {
                val code = entry.optString("code")
                if (code.isNotBlank()) out.add(code)
            }
        }
        return out
    }

    /**
     * Polls the Auth emulator until a code for [phoneNumber] appears that is not in
     * [exclude], or until [timeoutMs] elapses. One HTTP attempt per poll (the loop is
     * the retry), so total time is bounded by [timeoutMs] plus one connection timeout.
     * On failure, the message includes the last response body and last error so the
     * cause (empty list vs. connection trouble) is visible without digging in logcat.
     */
    private fun fetchVerificationCode(
        phoneNumber: String,
        exclude: Set<String> = emptySet(),
        timeoutMs: Long = CODE_POLL_TIMEOUT_MS
    ): String {
        Log.d(TAG, "Polling emulator for verification code for $phoneNumber (excluding ${exclude.size} stale)...")

        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var lastBody = "<no response>"
        var lastError = "none"

        while (System.currentTimeMillis() < deadline) {
            attempt++
            try {
                val body = blockingHttp("GET", authEmulatorUrl("verificationCodes"), maxRetries = 1)
                lastBody = body

                // Newest first; skip anything that existed before this sign-in began.
                val fresh = codesFor(body, phoneNumber).asReversed().firstOrNull { it !in exclude }
                if (fresh != null) return fresh
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Poll attempt $attempt failed: $lastError")
            }

            Thread.sleep(500)
        }

        error(
            "No verification code for $phoneNumber after $attempt polls " +
                    "(${timeoutMs}ms). Last body: $lastBody. Last error: $lastError"
        )
    }

    private fun authEmulatorUrl(path: String): String {
        return "http://$emulatorHost:$authPort/emulator/v1/projects/$projectId/$path"
    }

    private fun firestoreEmulatorUrl(): String {
        return "http://$emulatorHost:$firestorePort/emulator/v1/projects/$projectId/databases/(default)/documents"
    }

    private fun blockingHttp(
        method: String,
        urlString: String,
        maxRetries: Int = 3
    ): String {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(urlString).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.useCaches = false
                connection.instanceFollowRedirects = false

                val responseCode = connection.responseCode

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

                return stream?.let {
                    BufferedReader(InputStreamReader(it)).readAllText()
                } ?: "{}"
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }

        throw IllegalStateException(
            "Failed to connect to emulator at $urlString after $maxRetries attempts. Error: ${lastException?.message}",
            lastException
        )
    }

    private fun BufferedReader.readAllText(): String {
        return readLines().joinToString("\n")
    }

    companion object {
        private val emulatorsConfigured = AtomicBoolean(false)

        // Last resend token per phone number. Passing it to verifyPhoneNumber() forces the
        // SDK to hit the backend (and so the emulator) instead of reusing the still-open
        // verification session from the previous test.
        private val resendTokens =
            ConcurrentHashMap<String, PhoneAuthProvider.ForceResendingToken>()

        // Must stay comfortably below signInWithPhoneNumber's withTimeout (40s by default).
        private const val CODE_POLL_TIMEOUT_MS = 20_000L
    }
}