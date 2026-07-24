// screens/AddInventoryScreen.kt
package com.vestateck.smetracker.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.vestateck.smetracker.utils.ImageUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddInventoryScreen(viewModel: SMEViewModel, navController: NavController, isOwner: Boolean = false) {
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var costPrice by remember { mutableStateOf("") }       // ← NEW: what you paid per unit
    var sellingPrice by remember { mutableStateOf("") }   // ← was "unitPrice"
    var category by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var minStockLevel by remember { mutableStateOf("5") }
    var showError by remember { mutableStateOf(false) }

    // Photo handling - identical flow to InventoryItemDialog's (see that
    // file's doc comment): pick -> resize/copy into this device's internal
    // storage -> mark imagePendingUpload so InventorySync.pushPending picks
    // it up on the next sync. No imageUrl/existing-item state needed here
    // since this screen only ever creates brand-new items.
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var localImagePath by remember { mutableStateOf<String?>(null) }
    var isProcessingPhoto by remember { mutableStateOf(false) }

    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isProcessingPhoto = true
        coroutineScope.launch {
            val newPath = withContext(Dispatchers.IO) { ImageUtils.copyToInternalStorage(context, uri) }
            if (newPath != null) {
                ImageUtils.deleteLocalCopy(localImagePath)
                localImagePath = newPath
            }
            isProcessingPhoto = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Inventory Item") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Photo - same picker/thumbnail as InventoryItemDialog uses,
            // reusing that composable directly (same package, no import
            // needed) so the two add-item paths look and behave the same.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clickable {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                ) {
                    InventoryThumbnail(localImagePath = localImagePath, imageUrl = null, size = 64.dp)
                    if (isProcessingPhoto) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    TextButton(onClick = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Text(if (localImagePath == null) "Add photo" else "Change photo", fontSize = 13.sp)
                    }
                    if (localImagePath != null) {
                        TextButton(onClick = {
                            ImageUtils.deleteLocalCopy(localImagePath)
                            localImagePath = null
                        }) {
                            Text("Remove photo", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Product Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; showError = false },
                label = { Text("Product Name *") },
                leadingIcon = { Icon(Icons.Default.Inventory, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                isError = showError && name.isBlank(),
                supportingText = {
                    if (showError && name.isBlank())
                        Text("Product name is required", color = MaterialTheme.colorScheme.error)
                }
            )

            // Quantity
            OutlinedTextField(
                value = quantity,
                onValueChange = {
                    if (it.isEmpty() || it.all { c -> c.isDigit() }) {
                        quantity = it; showError = false
                    }
                },
                label = { Text("Quantity *") },
                leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                isError = showError && quantity.isBlank(),
                supportingText = {
                    if (showError && quantity.isBlank())
                        Text("Quantity is required", color = MaterialTheme.colorScheme.error)
                }
            )

            // Cost Price (what you paid — used for profit calculation).
            // Owner-only: costPrice lives in the owner-only inventoryCosts
            // Firestore collection (see firestore.rules) — a worker's push
            // never attempts that write (SyncEngine gates it on role), so
            // letting a worker type one in here would just be a value that
            // sits in local Room and never syncs anywhere. Hidden entirely
            // rather than shown-but-discarded, to avoid a confusing UI.
            if (isOwner) {
                OutlinedTextField(
                    value = costPrice,
                    onValueChange = {
                        if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) {
                            costPrice = it; showError = false
                        }
                    },
                    label = { Text("Cost Price (UGX) *") },
                    placeholder = { Text("What you paid per unit") },
                    leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    ),
                    isError = showError && costPrice.isBlank(),
                    supportingText = {
                        if (showError && costPrice.isBlank())
                            Text("Cost price is required", color = MaterialTheme.colorScheme.error)
                        else
                            Text("Used to calculate profit on sales")
                    }
                )
            }

            // Selling Price (what you charge customers)
            OutlinedTextField(
                value = sellingPrice,
                onValueChange = {
                    if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) {
                        sellingPrice = it; showError = false
                    }
                },
                label = { Text("Selling Price (UGX) *") },
                placeholder = { Text("What you charge customers") },
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next
                ),
                isError = showError && sellingPrice.isBlank(),
                supportingText = {
                    if (showError && sellingPrice.isBlank()) {
                        Text("Selling price is required", color = MaterialTheme.colorScheme.error)
                    } else if (isOwner) {
                        // Live margin hint once both prices are filled — owner-only,
                        // since it's derived from costPrice.
                        val cost = costPrice.toDoubleOrNull()
                        val sell = sellingPrice.toDoubleOrNull()
                        if (cost != null && sell != null && sell > 0) {
                            val margin = ((sell - cost) / sell * 100).toInt()
                            Text("Margin: $margin%  |  Profit per unit: UGX ${(sell - cost).toLong()}")
                        }
                    }
                }
            )

            // Category
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Category") },
                leadingIcon = { Icon(Icons.Default.Category, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            // Barcode / SKU - assign by scanning the product now, or leave
            // blank and add one later via the item's edit dialog.
            if (sku.isBlank()) {
                BarcodeScanField(
                    label = "Barcode / SKU (optional)",
                    modifier = Modifier.fillMaxWidth(),
                    onScan = { code -> sku = code }
                )
            } else {
                OutlinedTextField(
                    value = sku,
                    onValueChange = { sku = it },
                    label = { Text("Barcode / SKU") },
                    trailingIcon = {
                        TextButton(onClick = { sku = "" }) { Text("Clear") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Min Stock Level
            OutlinedTextField(
                value = minStockLevel,
                onValueChange = {
                    if (it.isEmpty() || it.all { c -> c.isDigit() }) minStockLevel = it
                },
                label = { Text("Minimum Stock Alert Level") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val qty = quantity.toIntOrNull()
                    // A worker never sees the cost field, so it's not required
                    // for them — default to 0.0 rather than blocking the save.
                    // That value never syncs to Firestore anyway (see
                    // SyncEngine — inventoryCosts is owner-only), so it's a
                    // harmless local placeholder, not a fabricated real cost.
                    val cost = if (isOwner) costPrice.toDoubleOrNull() else 0.0
                    val sell = sellingPrice.toDoubleOrNull()
                    val minStock = minStockLevel.toIntOrNull() ?: 5

                    if (name.isBlank() || qty == null || cost == null || sell == null) {
                        showError = true
                    } else {
                        viewModel.addInventoryItem(
                            name = name,
                            quantity = qty,
                            costPrice = cost,       // ← passed through to ViewModel
                            sellingPrice = sell,
                            category = category,
                            reorderLevel = minStock,
                            localImagePath = localImagePath,
                            imagePendingUpload = localImagePath != null,
                            sku = sku.trim().ifBlank { null }
                        )
                        navController.popBackStack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Save Inventory Item", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Cancel")
            }
        }
    }
}