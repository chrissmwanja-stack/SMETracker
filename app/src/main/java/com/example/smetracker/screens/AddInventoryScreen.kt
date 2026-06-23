// screens/AddInventoryScreen.kt
package com.example.smetracker.screens

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddInventoryScreen(viewModel: SMEViewModel, navController: NavController) {
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var costPrice by remember { mutableStateOf("") }       // ← NEW: what you paid per unit
    var sellingPrice by remember { mutableStateOf("") }   // ← was "unitPrice"
    var category by remember { mutableStateOf("") }
    var minStockLevel by remember { mutableStateOf("5") }
    var showError by remember { mutableStateOf(false) }

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

            // Cost Price (what you paid — used for profit calculation)
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
                    } else {
                        // Live margin hint once both prices are filled
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
                    val cost = costPrice.toDoubleOrNull()
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
                            reorderLevel = minStock
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