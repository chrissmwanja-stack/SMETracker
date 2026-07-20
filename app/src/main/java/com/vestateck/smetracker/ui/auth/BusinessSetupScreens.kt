package com.vestateck.smetracker.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * Owner-only screen: view the business's name/phone (read-only — those are
 * set at sign-up and change only via other flows) and edit its address.
 *
 * Loads fresh from Firestore on entry rather than reusing SMEViewModel's
 * cached businessName — this screen needs `address`, which the ViewModel's
 * dashboard state doesn't carry, so a direct BusinessRepository round trip
 * (same pattern as AddWorkerScreen above) is simpler than threading a new
 * field through the ViewModel just for one screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessSettingsScreen(
    businessId: String,
    businessRepository: BusinessRepository,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var businessName by remember { mutableStateOf("") }
    var ownerPhone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var originalAddress by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(businessId) {
        val result = businessRepository.getBusiness(businessId)
        result.fold(
            onSuccess = { business ->
                businessName = business.name
                ownerPhone = business.ownerPhone
                address = business.address
                originalAddress = business.address
            },
            onFailure = { e -> errorMessage = e.message ?: "Could not load business details" }
        )
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Business Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
        ) {
            Text(
                "This information can appear on sale receipts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = businessName,
                onValueChange = {},
                label = { Text("Business name") },
                singleLine = true,
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ownerPhone,
                onValueChange = {},
                label = { Text("Phone number") },
                singleLine = true,
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    saved = false
                },
                label = { Text("Business address") },
                placeholder = { Text("e.g. Plot 12, Kampala Road, Kampala") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (saved) {
                Spacer(Modifier.height(12.dp))
                Text("Saved", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    isSaving = true
                    errorMessage = null
                    saved = false
                    scope.launch {
                        val result = businessRepository.updateBusinessAddress(businessId, address.trim())
                        isSaving = false
                        result.fold(
                            onSuccess = {
                                originalAddress = address.trim()
                                address = originalAddress
                                saved = true
                            },
                            onFailure = { e -> errorMessage = e.message ?: "Could not save address" }
                        )
                    }
                },
                enabled = !isSaving && address.trim() != originalAddress,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        }
    }
}