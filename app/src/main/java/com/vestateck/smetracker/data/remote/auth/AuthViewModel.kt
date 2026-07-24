package com.vestateck.smetracker.data.remote.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.google.firebase.auth.PhoneAuthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where the login flow currently stands. The screen switches on this rather
 * than tracking its own ad-hoc booleans.
 */
sealed class AuthScreenState {
    object EnterPhone : AuthScreenState()
    data class EnterOtp(val phoneNumberE164: String, val verificationId: String) : AuthScreenState()
    object Verifying : AuthScreenState()
    object NotRegistered : AuthScreenState()   // phone not in phoneIndex, no owner sign-up in progress
    object NeedsOwnerSignUp : AuthScreenState() // reserved for the explicit "create business" entry point
    data class Error(val message: String) : AuthScreenState()
    data class LoggedIn(val businessId: String, val role: MemberRole) : AuthScreenState()
}

/**
 * Obtained via hiltViewModel() wherever it's needed - AuthRepository and
 * SessionManager come from RepositoryModule (see di/ package).
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _screenState = MutableStateFlow<AuthScreenState>(AuthScreenState.EnterPhone)
    val screenState: StateFlow<AuthScreenState> = _screenState.asStateFlow()

    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var pendingPhoneNumber: String? = null

    fun sendOtp(phoneNumberE164: String, activity: Activity) {
        pendingPhoneNumber = phoneNumberE164
        _screenState.value = AuthScreenState.Verifying

        viewModelScope.launch {
            authRepository.startPhoneVerification(phoneNumberE164, activity).collect { event ->
                when (event) {
                    is OtpEvent.CodeSent -> {
                        resendToken = event.token
                        _screenState.value = AuthScreenState.EnterOtp(
                            phoneNumberE164 = phoneNumberE164,
                            verificationId = event.verificationId
                        )
                    }
                    is OtpEvent.AutoVerified -> {
                        val result = authRepository.signInWithCredential(event.credential)
                        handleSignInResult(result, phoneNumberE164)
                    }
                    is OtpEvent.VerificationFailed -> {
                        _screenState.value = AuthScreenState.Error(event.message)
                    }
                }
            }
        }
    }

    /**
     * Re-sends the OTP for the number currently in flight, using the force-resend
     * token captured from the original CodeSent event. No-ops if called before
     * a code has ever been sent (shouldn't be reachable from the UI in that state).
     */
    fun resendOtp(activity: Activity) {
        val phoneNumberE164 = pendingPhoneNumber ?: return
        val token = resendToken ?: return

        viewModelScope.launch {
            authRepository.resendVerification(phoneNumberE164, activity, token).collect { event ->
                when (event) {
                    is OtpEvent.CodeSent -> {
                        resendToken = event.token
                        _screenState.value = AuthScreenState.EnterOtp(
                            phoneNumberE164 = phoneNumberE164,
                            verificationId = event.verificationId
                        )
                    }
                    is OtpEvent.AutoVerified -> {
                        val result = authRepository.signInWithCredential(event.credential)
                        handleSignInResult(result, phoneNumberE164)
                    }
                    is OtpEvent.VerificationFailed -> {
                        _screenState.value = AuthScreenState.Error(event.message)
                    }
                }
            }
        }
    }

    fun submitOtpCode(verificationId: String, code: String, phoneNumberE164: String) {
        _screenState.value = AuthScreenState.Verifying
        viewModelScope.launch {
            val result = authRepository.verifyOtpCode(verificationId, code)
            handleSignInResult(result, phoneNumberE164)
        }
    }

    private suspend fun handleSignInResult(result: Result<Unit>, phoneNumberE164: String) {
        result.fold(
            onSuccess = {
                sessionManager.savePhoneNumber(phoneNumberE164)
                when (val lookup = authRepository.resolvePhoneIndex(phoneNumberE164)) {
                    is PhoneIndexResult.Registered -> {
                        val role = MemberRole.fromString(lookup.role)
                        if (role == null) {
                            _screenState.value = AuthScreenState.Error("Unrecognized role on account.")
                            return
                        }
                        sessionManager.saveBusinessMembership(lookup.businessId, role)
                        _screenState.value = AuthScreenState.LoggedIn(lookup.businessId, role)
                    }
                    PhoneIndexResult.NotRegistered -> {
                        // Not auto-routed into sign-up — see NotRegistered screen copy.
                        // Owner sign-up is only reachable via an explicit "Create a business" action.
                        _screenState.value = AuthScreenState.NotRegistered
                    }
                }
            },
            onFailure = { e ->
                _screenState.value = AuthScreenState.Error(e.message ?: "Sign-in failed")
            }
        )
    }

    /** Explicit entry point for someone choosing "Create a business" from NotRegistered screen. */
    fun startOwnerSignUp() {
        _screenState.value = AuthScreenState.NeedsOwnerSignUp
    }

    fun resetToPhoneEntry() {
        _screenState.value = AuthScreenState.EnterPhone
    }

    /** Used right after LoggedIn fires, to tag a new offline-PIN credential row. See AuthNavGate. */
    fun currentFirebaseUid(): String? = authRepository.currentUid

    fun signOut(onComplete: () -> Unit = {}) {
        authRepository.signOut()
        viewModelScope.launch {
            sessionManager.clearSession()
            _screenState.value = AuthScreenState.EnterPhone
            onComplete()
        }
    }
}