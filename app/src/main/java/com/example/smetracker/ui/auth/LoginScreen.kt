package com.example.smetracker.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smetracker.data.remote.auth.AuthScreenState
import com.example.smetracker.data.remote.auth.AuthViewModel

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoggedIn: (businessId: String, role: com.example.smetracker.data.remote.auth.MemberRole) -> Unit,
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
                onSubmit = { code ->
                    viewModel.submitOtpCode(s.verificationId, code, s.phoneNumberE164)
                },
                onBack = { viewModel.resetToPhoneEntry() }
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

@Composable
private fun PhoneEntryContent(onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Welcome to SMETracker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
            enabled = phone.trim().startsWith("+") && phone.trim().length >= 10
        ) {
            Text("Send code")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your number in international format, e.g. +256701234567",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OtpEntryContent(
    phoneNumber: String,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit
) {
    var code by remember { mutableStateOf("") }

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
            enabled = code.length == 6
        ) {
            Text("Verify")
        }
        Spacer(Modifier.height(8.dp))
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
        Button(onClick = onCreateBusiness) {
            Text("Create a business")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onTryDifferentNumber) {
            Text("Try a different number")
        }
    }
}