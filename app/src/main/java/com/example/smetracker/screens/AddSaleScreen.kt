// screens/AddSaleScreen.kt
package com.example.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.smetracker.data.entities.InventoryItem
import com.example.smetracker.data.entities.PaymentMethod
import com.example.smetracker.utils.CurrencyUtils
import com.example.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSaleScreen(viewModel: SMEViewModel, navController: NavController) {
    val inventoryItems by viewModel.inventoryItems.collectAsState()

    var customer by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var paymentMethod by remember { mutableStateOf(PaymentMethod.CASH) }
    var expanded by remember { mutableStateOf(false) }

    // Null = custom/service sale, no cost basis, reconciled by definition
    // (see SMEViewModel.addSale). Selecting a real item is what lets this
    // sale carry stock + profit tracking through to the Reconciliation
    // screen — that path was previously unreachable because this screen
    // never offered a way to link one.
    var selectedItem by remember { mutableStateOf<InventoryItem?>(null) }
    var itemExpanded by remember { mutableStateOf(false) }
    var quantityInput by remember { mutableStateOf("1") }
    // Tracks whether the amount field was hand-edited after an item was
    // selected, so we stop overwriting it with the auto-suggested total
    // once the owner/worker has typed their own price (e.g. a discount).
    var amountManuallyEdited by remember { mutableStateOf(false) }

    val quantity = quantityInput.toIntOrNull()
    val exceedsStock = selectedItem != null && quantity != null && quantity > selectedItem!!.quantity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Sale") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = customer,
                onValueChange = { customer = it },
                label = { Text("Customer Name") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Product / Description") },
                modifier = Modifier.fillMaxWidth()
            )

            // Inventory Item Dropdown — optional. Leaving it on "Custom / service
            // sale" keeps today's behavior (no cost basis, reconciled by
            // definition). Picking a tracked item is what feeds the
            // reconciliation queue and stock adjustments.
            ExposedDropdownMenuBox(
                expanded = itemExpanded,
                onExpandedChange = { itemExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedItem?.name ?: "Custom / service sale",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Inventory Item") },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = itemExpanded,
                    onDismissRequest = { itemExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Custom / service sale") },
                        onClick = {
                            selectedItem = null
                            amountManuallyEdited = false
                            itemExpanded = false
                        }
                    )
                    inventoryItems.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.name)
                                    Text(
                                        "${item.quantity} in stock · ${CurrencyUtils.formatUgx(item.sellingPrice)}",
                                        fontSize = 12.sp
                                    )
                                }
                            },
                            enabled = item.quantity > 0,
                            onClick = {
                                selectedItem = item
                                amountManuallyEdited = false
                                if (description.isBlank()) description = item.name
                                val qty = quantityInput.toIntOrNull() ?: 1
                                amount = (item.sellingPrice * qty).let {
                                    if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
                                }
                                itemExpanded = false
                            }
                        )
                    }
                }
            }

            if (selectedItem != null) {
                OutlinedTextField(
                    value = quantityInput,
                    onValueChange = { newQty ->
                        quantityInput = newQty
                        // Keep suggesting a total as quantity changes, same rule as
                        // on selection: stop once the amount's been hand-edited.
                        if (!amountManuallyEdited) {
                            val item = selectedItem
                            val qty = newQty.toIntOrNull()
                            if (item != null && qty != null) {
                                amount = (item.sellingPrice * qty).let {
                                    if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
                                }
                            }
                        }
                    },
                    label = { Text("Quantity") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = exceedsStock,
                    supportingText = {
                        if (exceedsStock) {
                            Text("Only ${selectedItem!!.quantity} in stock")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            OutlinedTextField(
                value = amount,
                onValueChange = {
                    amount = it
                    amountManuallyEdited = true
                },
                label = { Text("Amount (UGX)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // Payment Method Dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = paymentMethod.name.replace("_", " "),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Payment Method") },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    PaymentMethod.entries.forEach { method ->
                        DropdownMenuItem(
                            text = { Text(method.name.replace("_", " ")) },
                            onClick = {
                                paymentMethod = method
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    val qty = quantity ?: 1
                    if (customer.isNotBlank() && amt > 0 && !exceedsStock && qty > 0) {
                        // Profit is calculated in reports/reconciliation, not entered
                        // manually here.
                        viewModel.addSale(
                            customerName = customer,
                            description = description,
                            amount = amt,
                            paymentMethod = paymentMethod,
                            inventoryItemId = selectedItem?.id,
                            quantity = qty
                        )
                        navController.popBackStack()
                    }
                },
                enabled = customer.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0 && !exceedsStock && (quantity ?: 1) > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Sale")
            }
        }
    }
}