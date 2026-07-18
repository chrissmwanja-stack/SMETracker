package com.vestateck.smetracker.ui.auth

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vestateck.smetracker.data.remote.auth.AuthRepository
import com.vestateck.smetracker.data.remote.auth.AuthViewModel
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.model.MemberRole

/**
 * Manual DI factory, matching SMETracker's existing pattern (no Hilt).
 * Wire this in wherever the app currently constructs its top-level
 * ViewModels (e.g. MainActivity or an AppContainer).
 */
class AuthViewModelFactory(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AuthViewModel(authRepository, sessionManager) as T
    }
}

/**
 * Entry point router: reads persisted session state and decides whether to
 * show login, business setup, or hand off into the main app. This is
 * intentionally minimal for Phase 2 — Phase 5 (role gating) builds on top
 * of the SessionState this already exposes, it doesn't replace it.
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

    when {
        session == null -> {
            // Still reading DataStore for the first time.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        session!!.isLoggedIn && session!!.hasBusiness -> {
            onEnterApp(session!!.businessId!!, session!!.role!!)
        }

        else -> {
            // Not logged in, or logged in but never completed business setup.
            var pendingOwnerPhone by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<String?>(null)
            }

            if (pendingOwnerPhone == null) {
                LoginScreen(
                    viewModel = authViewModel,
                    onLoggedIn = { businessId, role -> onEnterApp(businessId, role) },
                    onCreateBusiness = {
                        pendingOwnerPhone = session?.phoneNumberE164
                            ?: authViewModel.let { null } // phone should already be persisted by this point
                    }
                )
            } else {
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
                            onEnterApp(businessId, MemberRole.OWNER)
                        }
                    }
                )
            }
        }
    }
}