package com.vestateck.smetracker.data.remote.auth

import android.app.Activity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Result of resolving a phone number against phoneIndex/{phoneNumberE164}
 * after a successful OTP verification.
 */
sealed class PhoneIndexResult {
    data class Registered(val businessId: String, val role: String) : PhoneIndexResult()
    object NotRegistered : PhoneIndexResult()
}

/**
 * Events emitted while an OTP verification is in flight. Mirrors Firebase's
 * PhoneAuthProvider callback shape but as a cold Flow so the ViewModel can
 * collect it with normal coroutine machinery instead of nested callbacks.
 */
sealed class OtpEvent {
    data class CodeSent(
        val verificationId: String,
        val token: PhoneAuthProvider.ForceResendingToken
    ) : OtpEvent()
    data class AutoVerified(val credential: PhoneAuthCredential) : OtpEvent()
    data class VerificationFailed(val message: String) : OtpEvent()
}

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    val currentPhoneNumber: String?
        get() = auth.currentUser?.phoneNumber

    /** Used to tag the local offline-PIN credential row (see SessionManager.savePinAfterOnlineVerification). */
    val currentUid: String?
        get() = auth.currentUser?.uid

    val isLoggedIn: Boolean
        get() = auth.currentUser != null

    /**
     * Kicks off Firebase Phone Auth for [phoneNumberE164] (must already be
     * formatted E.164, e.g. "+256701234567" — reuse the same formatter used
     * in ClubVest). Emits CodeSent once the SMS goes out, or AutoVerified if
     * Google Play Services auto-retrieves the code without user input.
     */
    fun startPhoneVerification(
        phoneNumberE164: String,
        activity: Activity
    ): Flow<OtpEvent> = callbackFlow {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                trySend(OtpEvent.AutoVerified(credential))
            }

            override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                trySend(OtpEvent.VerificationFailed(e.message ?: "Verification failed"))
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                trySend(OtpEvent.CodeSent(verificationId, token))
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumberE164)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)

        awaitClose { /* Firebase has no explicit unregister for this call */ }
    }

    /** Resends the OTP using the force-resend token captured from onCodeSent, if needed later. */
    fun resendVerification(
        phoneNumberE164: String,
        activity: Activity,
        token: PhoneAuthProvider.ForceResendingToken
    ): Flow<OtpEvent> = callbackFlow {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                trySend(OtpEvent.AutoVerified(credential))
            }

            override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                trySend(OtpEvent.VerificationFailed(e.message ?: "Verification failed"))
            }

            override fun onCodeSent(
                verificationId: String,
                newToken: PhoneAuthProvider.ForceResendingToken
            ) {
                trySend(OtpEvent.CodeSent(verificationId, newToken))
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumberE164)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .setForceResendingToken(token)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)

        awaitClose { }
    }

    /** Verifies the 6-digit code the user typed against the verificationId from CodeSent. */
    suspend fun verifyOtpCode(verificationId: String, code: String): Result<Unit> {
        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, code)
            signInWithCredential(credential)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Used for the auto-verified path where Firebase hands us a credential directly. */
    suspend fun signInWithCredential(credential: PhoneAuthCredential): Result<Unit> {
        return try {
            auth.signInWithCredential(credential).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Looks up phoneIndex/{phoneNumberE164} to resolve businessId + role.
     * Call this immediately after a successful sign-in.
     */
    suspend fun resolvePhoneIndex(phoneNumberE164: String): PhoneIndexResult {
        val doc = firestore.collection("phoneIndex")
            .document(phoneNumberE164)
            .get()
            .await()

        if (!doc.exists()) return PhoneIndexResult.NotRegistered

        val businessId = doc.getString("businessId") ?: return PhoneIndexResult.NotRegistered
        val role = doc.getString("role") ?: return PhoneIndexResult.NotRegistered

        return PhoneIndexResult.Registered(businessId = businessId, role = role)
    }

    fun signOut() {
        auth.signOut()
    }
}