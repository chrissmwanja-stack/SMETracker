package com.vestateck.smetracker.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import kotlinx.coroutines.launch

/**
 * Shown after NotRegistered -> "Create a business". Uses the phone number
 * already verified during OTP sign-in — no re-entry needed.
 */
@Composable
fun OwnerSignUpScreen(
    ownerPhoneE164: String,
    businessRepository: BusinessRepository,
    onBusinessCreated: (businessId: String) -> Unit
) {
    var ownerName by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Set up your business", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = businessName,
                onValueChange = { businessName = it },
                label = { Text("Business name") },
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ownerName,
                onValueChange = { ownerName = it },
                label = { Text("Your name") },
                singleLine = true
            )

            errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    isSubmitting = true
                    errorMessage = null
                    scope.launch {
                        val result = businessRepository.createBusinessWithOwner(
                            ownerPhoneE164 = ownerPhoneE164,
                            ownerName = ownerName.trim(),
                            businessName = businessName.trim()
                        )
                        isSubmitting = false
                        result.fold(
                            onSuccess = { businessId -> onBusinessCreated(businessId) },
                            onFailure = { e -> errorMessage = e.message ?: "Something went wrong" }
                        )
                    }
                },
                enabled = !isSubmitting && ownerName.isNotBlank() && businessName.isNotBlank()
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Create business")
                }
            }
        }
    }
}

/**
 * Owner-only screen: add a worker's phone number ahead of time. The worker
 * simply logs in with OTP on their own device afterward — no code to share,
 * no acceptance step.
 */
@Composable
fun AddWorkerScreen(
    businessId: String,
    businessRepository: BusinessRepository,
    onWorkerAdded: () -> Unit,
    onCancel: () -> Unit
) {
    var workerPhone by remember { mutableStateOf("") }
    var workerName by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Text("Add a worker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "They'll log in on their own phone with this number — no code needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = workerName,
            onValueChange = { workerName = it },
            label = { Text("Worker's name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = workerPhone,
            onValueChange = { workerPhone = it },
            label = { Text("Phone number") },
            placeholder = { Text("+2567XXXXXXXX") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))
        Row {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    isSubmitting = true
                    errorMessage = null
                    scope.launch {
                        val result = businessRepository.addWorker(
                            businessId = businessId,
                            workerPhoneE164 = workerPhone.trim(),
                            workerName = workerName.trim()
                        )
                        isSubmitting = false
                        result.fold(
                            onSuccess = { onWorkerAdded() },
                            onFailure = { e -> errorMessage = e.message ?: "Something went wrong" }
                        )
                    }
                },
                enabled = !isSubmitting &&
                        workerName.isNotBlank() &&
                        workerPhone.trim().startsWith("+") &&
                        workerPhone.trim().length >= 10,
                modifier = Modifier.weight(1f)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Add worker")
                }
            }
        }
    }
}