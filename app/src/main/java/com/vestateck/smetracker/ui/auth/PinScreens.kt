package com.vestateck.smetracker.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shown when this device already has an offline PIN saved for a business
 * (see SessionManager.deviceBusinessId). Verification itself is offline -
 * no network call happens on this screen at all, which is the entire point:
 * it's what lets login work over plain GSM/SMS-only signal.
 */
@Composable
fun PinEntryScreen(
    phoneNumberE164: String,
    isVerifying: Boolean,
    errorMessage: String?,
    onPinSubmit: (String) -> Unit,
    onUseDifferentAccount: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Enter your PIN",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            phoneNumberE164,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            isError = errorMessage != null
        )
        if (errorMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onPinSubmit(pin) },
            enabled = pin.length in 4..6 && !isVerifying,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isVerifying) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Continue")
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onUseDifferentAccount) {
            Text("Use a different number")
        }
    }
}

/**
 * Shown once, right after the first successful online phone-OTP
 * verification for a given business on this device. The PIN set here is
 * what SessionManager hashes and stores for all future offline logins.
 */
@Composable
fun SetPinScreen(
    onPinSet: (String) -> Unit,
    onSkip: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Set up a PIN for offline login",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "You'll only need to type this PIN to sign in from now on - even with no internet connection, as long as you're on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { pin = it; error = null } },
            label = { Text("New PIN (4-6 digits)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { confirmPin = it; error = null } },
            label = { Text("Confirm PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            isError = error != null
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when {
                    pin.length !in 4..6 -> error = "PIN must be 4-6 digits."
                    pin != confirmPin -> error = "PINs don't match."
                    else -> onPinSet(pin)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Set PIN")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Skip for now")
        }
        Text(
            "If you skip, you'll need a data or WiFi connection to sign in every time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}