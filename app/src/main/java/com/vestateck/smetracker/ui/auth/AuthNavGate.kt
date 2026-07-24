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

    LaunchedEffect(deviceBusinessId) {
        credentialChecked = false
        pinError = null
        localCredential = deviceBusinessId?.let { sessionManager.getLocalCredential(it) }
        credentialChecked = true
    }

    when {
        session == null || !credentialChecked -> {
            // Still reading DataStore/Room for the first time.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
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
            var pendingPinSetup by remember { mutableStateOf<Pair<String, MemberRole>?>(null) }

            when {
                pendingPinSetup != null -> {
                    val (businessId, role) = pendingPinSetup!!
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
                                pendingPinSetup = null
                                onEnterApp(businessId, role)
                            }
                        },
                        onSkip = {
                            pendingPinSetup = null
                            onEnterApp(businessId, role)
                        }
                    )
                }

                pendingOwnerPhone == null -> {
                    LoginScreen(
                        viewModel = authViewModel,
                        onLoggedIn = { businessId, role ->
                            scope.launch {
                                // Only prompt for a PIN the first time this
                                // business is verified on this device -
                                // subsequent logins already have one.
                                val existing = sessionManager.getLocalCredential(businessId)
                                if (existing == null) {
                                    pendingPinSetup = businessId to role
                                } else {
                                    onEnterApp(businessId, role)
                                }
                            }
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
                            scope.launch {
                                sessionManager.saveBusinessMembership(
                                    businessId,
                                    MemberRole.OWNER
                                )
                                pendingPinSetup = businessId to MemberRole.OWNER
                            }
                        }
                    )
                }
            }
        }
    }
}