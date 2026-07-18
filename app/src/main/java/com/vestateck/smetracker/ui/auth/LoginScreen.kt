package com.vestateck.smetracker.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vestateck.smetracker.data.remote.auth.AuthScreenState
import com.vestateck.smetracker.data.remote.auth.AuthViewModel
import com.vestateck.smetracker.data.remote.model.MemberRole

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoggedIn: (businessId: String, role: MemberRole) -> Unit,
    onCreateBusiness: () -> Unit
) {
    val state by viewModel.screenState.collectAsState()
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    LaunchedEffect(state) {
        if (state is AuthScreenState.LoggedIn) {
            val loggedIn = state as AuthScreenState.LoggedIn
            onLoggedIn(loggedIn.businessId, loggedIn.role)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Trust strip only makes sense before anyone's committed to a
        // number — it's a first-impression cue, not something to repeat
        // once they're mid-flow verifying a code or hitting an error.
        AuthHeader(showTrustStrip = state is AuthScreenState.EnterPhone)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is AuthScreenState.EnterPhone -> PhoneEntryContent(
                    onSubmit = { phone ->
                        activity?.let { viewModel.sendOtp(phone, it) }
                    }
                )

                is AuthScreenState.Verifying -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Verifying…")
                }

                is AuthScreenState.EnterOtp -> OtpEntryContent(
                    phoneNumber = s.phoneNumberE164,
                    verificationId = s.verificationId,
                    onSubmit = { code ->
                        viewModel.submitOtpCode(s.verificationId, code, s.phoneNumberE164)
                    },
                    onBack = { viewModel.resetToPhoneEntry() },
                    onResend = { activity?.let { viewModel.resendOtp(it) } }
                )

                is AuthScreenState.NotRegistered -> NotRegisteredContent(
                    onCreateBusiness = onCreateBusiness,
                    onTryDifferentNumber = { viewModel.resetToPhoneEntry() }
                )

                is AuthScreenState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.resetToPhoneEntry() }) {
                        Text("Try again")
                    }
                }

                else -> { /* NeedsOwnerSignUp / LoggedIn handled by navigation */ }
            }
        }
    }
}

// Colored block anchoring the whole auth flow — the one thing the old
// screen had none of. Rounded-bottom shape so the white content area
// below reads as a card sitting on top of it, same idea as the
// Reconciliation dialogs' Surface-on-Dialog layering elsewhere in the app.
@Composable
private fun AuthHeader(showTrustStrip: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 28.dp, start = 24.dp, end = 24.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "SME Tracker",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Run your shop from your phone",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )

            if (showTrustStrip) {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TrustStripItem(Icons.Default.Inventory2, "Stock")
                    TrustStripItem(Icons.Default.PointOfSale, "Sales")
                    TrustStripItem(Icons.Default.Group, "Team")
                }
            }
        }
    }
}

@Composable
private fun TrustStripItem(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun PhoneEntryContent(onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Enter your phone number to sign in",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number") },
            placeholder = { Text("+2567XXXXXXXX") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(phone.trim()) },
            enabled = phone.trim().startsWith("+") && phone.trim().length >= 10,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send code")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your number in international format, e.g. +256701234567",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun OtpEntryContent(
    phoneNumber: String,
    verificationId: String,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onResend: () -> Unit
) {
    var code by remember { mutableStateOf("") }

    // Cooldown resets whenever a fresh verificationId comes in — i.e. right
    // after the initial send AND right after a successful resend.
    var secondsUntilResend by remember(verificationId) { mutableStateOf(30) }
    LaunchedEffect(verificationId) {
        secondsUntilResend = 30
        while (secondsUntilResend > 0) {
            kotlinx.coroutines.delay(1000)
            secondsUntilResend -= 1
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Enter the code sent to", style = MaterialTheme.typography.bodyMedium)
        Text(phoneNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6) code = it },
            label = { Text("6-digit code") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(code) },
            enabled = code.length == 6,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Verify")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onResend,
            enabled = secondsUntilResend == 0
        ) {
            Text(if (secondsUntilResend > 0) "Resend code in ${secondsUntilResend}s" else "Resend code")
        }
        TextButton(onClick = onBack) {
            Text("Use a different number")
        }
    }
}

@Composable
private fun NotRegisteredContent(
    onCreateBusiness: () -> Unit,
    onTryDifferentNumber: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "This number isn't registered with a business yet.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "If you're a worker, ask your business owner to add your number in the app. If you're setting up a new business, you can create one now.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreateBusiness, modifier = Modifier.fillMaxWidth()) {
            Text("Create a business")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onTryDifferentNumber) {
            Text("Try a different number")
        }
    }
}