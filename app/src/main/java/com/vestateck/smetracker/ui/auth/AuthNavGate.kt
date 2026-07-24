package com.vestateck.smetracker.ui.auth

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vestateck.smetracker.data.entities.LocalCredential
import com.vestateck.smetracker.data.remote.auth.AuthViewModel
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.model.MemberRole

/**
 * Entry point router: reads persisted session state and decides whether to
 * show login, business setup, or hand off into the main app.
 *
 * Also gates on SessionManager.deviceBusinessId: if this device has already
 * completed one full online phone-OTP verification for some business (i.e.
 * has a saved PIN), it offers PIN entry instead of the full phone/OTP flow -
 * this is what lets sign-in work over plain GSM/SMS-only signal, with no
 * data connection needed, for every login after the very first one. See
 * SessionManager's offline PIN credential section for the storage side of
 * this.
 *
 * IMPORTANT: pin-setup routing is driven by (session, localCredential)
 * state, not by local composable flags. session.isLoggedIn flips to true
 * as soon as login/verification completes, which recomposes this whole
 * `when` from the top - any pending-setup intent held in composable-local
 * state tied to a *different* branch would be silently abandoned the
 * instant that happens. Keying off localCredential (backed by Room, so it
 * survives recomposition) avoids that.
 */
@Composable
fun AuthNavGate(
    context: Context,
    authViewModel: AuthViewModel,
    sessionManager: SessionManager,
    businessRepository: BusinessRepository,
    onEnterApp: (businessId: String, role: MemberRole) -> Unit
) {
    val scope = rememberCoroutineScope()
    val session by sessionManager.sessionState.collectAsState(initial = null)
    val deviceBusinessId by sessionManager.deviceBusinessId.collectAsState(initial = null)

    // The Flow above only carries the businessId - the phone/role to show on
    // the PIN screen live in the local_credentials row itself, fetched once
    // per change so a signed-out device (session cleared, but the PIN kept)
    // still resolves correctly.
    var localCredential by remember { mutableStateOf<LocalCredential?>(null) }
    var credentialChecked by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var verifyingPin by remember { mutableStateOf(false) }
    var pinSetupSkipped by remember { mutableStateOf(false) }

    // Re-check localCredential whenever deviceBusinessId OR the logged-in
    // business changes - covers both "new device, known business" and
    // "just finished OTP for a business this device hasn't verified before".
    val credentialLookupKey = deviceBusinessId ?: session?.businessId

    LaunchedEffect(credentialLookupKey) {
        credentialChecked = false
        pinError = null
        localCredential = credentialLookupKey?.let { sessionManager.getLocalCredential(it) }
        credentialChecked = true
    }

    // Reset the "user tapped skip" flag whenever we move to a different
    // business/session so a skip on one login doesn't suppress the prompt
    // for a later, different business on the same device.
    LaunchedEffect(session?.businessId) {
        pinSetupSkipped = false
    }

    when {
        session == null || !credentialChecked -> {
            // Still reading DataStore/Room for the first time.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        // Logged in with a business, but this device has never completed
        // PIN setup for it yet - prompt before handing off into the app.
        // Checked BEFORE the plain "enter app" branch below so a fresh
        // login/business-creation can't skip straight past this screen.
        session!!.isLoggedIn && session!!.hasBusiness && localCredential == null && !pinSetupSkipped -> {
            val businessId = session!!.businessId!!
            val role = session!!.role!!
            SetPinScreen(
                onPinSet = { pin ->
                    scope.launch {
                        val phone = sessionManager.sessionState.first().phoneNumberE164 ?: return@launch
                        val uid = authViewModel.currentFirebaseUid() ?: ""
                        sessionManager.savePinAfterOnlineVerification(
                            businessId = businessId,
                            phoneNumberE164 = phone,
                            role = role,
                            firebaseUid = uid,
                            pin = pin
                        )
                        // Refresh so this branch's condition (localCredential
                        // == null) stops matching on the next recomposition.
                        localCredential = sessionManager.getLocalCredential(businessId)
                        onEnterApp(businessId, role)
                    }
                },
                onSkip = {
                    pinSetupSkipped = true
                    onEnterApp(businessId, role)
                }
            )
        }

        session!!.isLoggedIn && session!!.hasBusiness -> {
            onEnterApp(session!!.businessId!!, session!!.role!!)
        }

        // Not "logged in" this session (e.g. fresh app launch, or after a
        // normal sign-out), but this device already has an offline PIN saved
        // for some business - offer PIN entry instead of forcing OTP.
        localCredential != null -> {
            val cred = localCredential!!
            PinEntryScreen(
                phoneNumberE164 = cred.phoneNumberE164,
                isVerifying = verifyingPin,
                errorMessage = pinError,
                onPinSubmit = { pin ->
                    scope.launch {
                        verifyingPin = true
                        pinError = null
                        val ok = sessionManager.verifyPinOffline(cred.businessId, pin)
                        verifyingPin = false
                        if (ok) {
                            val role = MemberRole.fromString(cred.role) ?: MemberRole.WORKER
                            sessionManager.savePhoneNumber(cred.phoneNumberE164)
                            sessionManager.saveBusinessMembership(cred.businessId, role)
                            onEnterApp(cred.businessId, role)
                        } else {
                            pinError = "Incorrect PIN. Try again."
                        }
                    }
                },
                onUseDifferentAccount = {
                    scope.launch {
                        sessionManager.forgetDeviceCredential(cred.businessId)
                        localCredential = null
                    }
                }
            )
        }

        else -> {
            // Not logged in, or logged in but never completed business setup.
            var pendingOwnerPhone by remember { mutableStateOf<String?>(null) }

            when {
                pendingOwnerPhone == null -> {
                    LoginScreen(
                        viewModel = authViewModel,
                        onLoggedIn = { _, _ ->
                            // No manual routing needed here anymore - as soon
                            // as login completes, session.isLoggedIn flips to
                            // true, this composable recomposes, and the
                            // top-level branches above take over (pin-setup
                            // branch first if localCredential is still null).
                        },
                        onCreateBusiness = {
                            pendingOwnerPhone = session?.phoneNumberE164
                        }
                    )
                }

                else -> {
                    OwnerSignUpScreen(
                        ownerPhoneE164 = pendingOwnerPhone!!,
                        businessRepository = businessRepository,
                        onBusinessCreated = { businessId ->
                            // saveBusinessMembership already happened inside sign-up's
                            // Firestore write; mirror it into the session store here.
                            // No manual pin-setup routing needed - same reasoning
                            // as onLoggedIn above.
                            scope.launch {
                                sessionManager.saveBusinessMembership(
                                    businessId,
                                    MemberRole.OWNER
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}