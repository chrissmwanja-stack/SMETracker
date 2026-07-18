// screens/AddSaleScreen.kt
package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.utils.CurrencyUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel
import com.vestateck.smetracker.viewmodel.SaleLineInput
import java.util.UUID

// One row in the "cart" of products being sold to the same customer in this
// transaction. Immutable + replaced-by-index in the list below rather than
// holding individually-mutable fields, so each line's own recomposition
// scope stays simple (same pattern as the single-item fields this screen
// used to have, just repeated per line).
private data class SaleLineItem(
    val localId: String = UUID.randomUUID().toString(),
    val selectedItem: InventoryItem? = null,
    val description: String = "",
    val quantityInput: String = "1",
    val amount: String = "",
    val amountManuallyEdited: Boolean = false
)

private fun suggestedAmount(item: InventoryItem, qty: Int): String {
    val total = item.sellingPrice * qty
    return if (total == total.toLong().toDouble()) total.toLong().toString() else total.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSaleScreen(viewModel: SMEViewModel, navController: NavController) {
    val inventoryItems by viewModel.inventoryItems.collectAsState()
    val customers by viewModel.customers.collectAsState()

    // ── Customer: pick a saved one, or type a fresh name for a walk-in/
    // one-off sale. selectedCustomerId is what actually links the Sale back
    // to a Customer record (see SMEViewModel.addSale); it's cleared the
    // moment the typed text no longer matches the selected customer's name,
    // so editing the field after picking someone always falls back cleanly
    // to "this is just a name" rather than silently keeping a stale link.
    var customerName by remember { mutableStateOf("") }
    var selectedCustomerId by remember { mutableStateOf<String?>(null) }
    var customerFieldExpanded by remember { mutableStateOf(false) }
    // Only meaningful when there's a typed name that doesn't match a saved
    // customer (selectedCustomerId == null). Lets a walk-in be promoted to a
    // real Customer record at save time instead of staying a name-only sale.
    var saveAsNewCustomer by remember { mutableStateOf(false) }
    val matchingCustomers = remember(customerName, customers) {
        if (customerName.isBlank()) customers
        else customers.filter {
            it.name.contains(customerName, ignoreCase = true) ||
                    (it.phone.isNotBlank() && it.phone.contains(customerName))
        }
    }

    var paymentMethod by remember { mutableStateOf(PaymentMethod.CASH) }
    var paymentExpanded by remember { mutableStateOf(false) }

    val lineItems = remember { mutableStateListOf(SaleLineItem()) }

    fun updateLine(index: Int, updated: SaleLineItem) {
        lineItems[index] = updated
    }

    // An item already picked on another line would race against itself in
    // SMEViewModel.addSale's stock check (each line's addSale call reads
    // inventoryItems.value independently, so two lines selling the same item
    // can both pass a check that's only valid one-at-a-time). Simplest safe
    // rule: an item can only be picked on one line — bump quantity there
    // instead of adding a second line for it.
    fun itemAlreadyUsedElsewhere(item: InventoryItem, exceptLocalId: String): Boolean =
        lineItems.any { it.localId != exceptLocalId && it.selectedItem?.id == item.id }

    fun lineExceedsStock(line: SaleLineItem): Boolean {
        val qty = line.quantityInput.toIntOrNull()
        return line.selectedItem != null && qty != null && qty > line.selectedItem.quantity
    }

    fun lineIsValid(line: SaleLineItem): Boolean {
        val amt = line.amount.toDoubleOrNull() ?: 0.0
        val qty = line.quantityInput.toIntOrNull() ?: 0
        val hasProduct = line.selectedItem != null || line.description.isNotBlank()
        return hasProduct && amt > 0 && qty > 0 && !lineExceedsStock(line)
    }

    val totalAmount = lineItems.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    val allLinesValid = lineItems.isNotEmpty() && lineItems.all { lineIsValid(it) }
    val canSave = customerName.isNotBlank() && allLinesValid

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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Customer picker ──────────────────────────────────────
            ExposedDropdownMenuBox(
                expanded = customerFieldExpanded,
                onExpandedChange = { customerFieldExpanded = it }
            ) {
                OutlinedTextField(
                    value = customerName,
                    onValueChange = { text ->
                        customerName = text
                        // Typing after a pick invalidates the link unless it
                        // still matches exactly (e.g. focus loss/regain).
                        if (selectedCustomerId != null &&
                            customers.find { it.id == selectedCustomerId }?.name != text
                        ) {
                            selectedCustomerId = null
                        }
                        saveAsNewCustomer = false
                        customerFieldExpanded = true
                    },
                    label = { Text("Customer Name") },
                    placeholder = { Text("Search saved customers or type a new name") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    singleLine = true
                )
                if (matchingCustomers.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = customerFieldExpanded,
                        onDismissRequest = { customerFieldExpanded = false }
                    ) {
                        matchingCustomers.forEach { customer: Customer ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(customer.name)
                                        if (customer.phone.isNotBlank()) {
                                            Text(customer.phone, fontSize = 12.sp)
                                        }
                                    }
                                },
                                onClick = {
                                    customerName = customer.name
                                    selectedCustomerId = customer.id
                                    saveAsNewCustomer = false
                                    customerFieldExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            if (customerName.isNotBlank() && selectedCustomerId == null) {
                Text(
                    "No saved customer matches \"$customerName\".",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = saveAsNewCustomer,
                        onCheckedChange = { saveAsNewCustomer = it }
                    )
                    Text("Save \"$customerName\" as a new customer", fontSize = 13.sp)
                }
                if (!saveAsNewCustomer) {
                    Text(
                        "Unchecked: this sale is recorded under that name only, and won't appear in Customers.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ── Products (repeatable line items) ─────────────────────
            Text("Products", fontWeight = FontWeight.Bold)

            lineItems.forEachIndexed { index, line ->
                SaleLineItemCard(
                    line = line,
                    inventoryItems = inventoryItems,
                    canRemove = lineItems.size > 1,
                    itemAlreadyUsedElsewhere = { item -> itemAlreadyUsedElsewhere(item, line.localId) },
                    onChange = { updated -> updateLine(index, updated) },
                    onRemove = { lineItems.removeAt(index) }
                )
            }

            TextButton(
                onClick = { lineItems.add(SaleLineItem()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add another product")
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ── Payment method (applies to the whole sale) ───────────
            ExposedDropdownMenuBox(
                expanded = paymentExpanded,
                onExpandedChange = { paymentExpanded = it }
            ) {
                OutlinedTextField(
                    value = paymentMethod.name.replace("_", " "),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Payment Method") },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = paymentExpanded,
                    onDismissRequest = { paymentExpanded = false }
                ) {
                    PaymentMethod.entries.forEach { method ->
                        DropdownMenuItem(
                            text = { Text(method.name.replace("_", " ")) },
                            onClick = {
                                paymentMethod = method
                                paymentExpanded = false
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Total", fontWeight = FontWeight.Bold)
                Text(CurrencyUtils.formatUgx(totalAmount), fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (!canSave) return@Button
                    // One Sale row per line item, matching the app's existing
                    // one-item-per-sale data model (Reports/Reconciliation
                    // both operate on individual Sale rows) — this just lets
                    // several of them be entered and saved together for one
                    // customer instead of round-tripping this screen per item.
                    // addSaleLines resolves/creates the customer once up
                    // front (honoring the "Save as new customer" toggle) so
                    // every line shares the same customerId.
                    viewModel.addSaleLines(
                        customerName = customerName,
                        selectedCustomerId = selectedCustomerId,
                        saveAsNewCustomer = saveAsNewCustomer,
                        paymentMethod = paymentMethod,
                        lines = lineItems.map { line ->
                            SaleLineInput(
                                description = line.description.ifBlank { line.selectedItem?.name ?: "" },
                                amount = line.amount.toDoubleOrNull() ?: 0.0,
                                inventoryItemId = line.selectedItem?.id,
                                quantity = line.quantityInput.toIntOrNull() ?: 1
                            )
                        }
                    )
                    navController.popBackStack()
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (lineItems.size > 1) "Save Sale (${lineItems.size} items)" else "Save Sale")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaleLineItemCard(
    line: SaleLineItem,
    inventoryItems: List<InventoryItem>,
    canRemove: Boolean,
    itemAlreadyUsedElsewhere: (InventoryItem) -> Boolean,
    onChange: (SaleLineItem) -> Unit,
    onRemove: () -> Unit
) {
    var itemExpanded by remember { mutableStateOf(false) }
    val exceedsStock = line.selectedItem != null &&
            line.quantityInput.toIntOrNull()?.let { it > line.selectedItem.quantity } == true

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Product / Description",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove product")
                    }
                }
            }
            OutlinedTextField(
                value = line.description,
                onValueChange = { onChange(line.copy(description = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownMenuBox(
                expanded = itemExpanded,
                onExpandedChange = { itemExpanded = it }
            ) {
                OutlinedTextField(
                    value = line.selectedItem?.name ?: "Custom / service sale",
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
                            onChange(line.copy(selectedItem = null, amountManuallyEdited = false))
                            itemExpanded = false
                        }
                    )
                    inventoryItems.forEach { item ->
                        val usedElsewhere = itemAlreadyUsedElsewhere(item)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.name)
                                    Text(
                                        if (usedElsewhere) "Already added to this sale — adjust its quantity instead"
                                        else "${item.quantity} in stock · ${CurrencyUtils.formatUgx(item.sellingPrice)}",
                                        fontSize = 12.sp,
                                        color = if (usedElsewhere) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            enabled = item.quantity > 0 && !usedElsewhere,
                            onClick = {
                                val qty = line.quantityInput.toIntOrNull() ?: 1
                                onChange(
                                    line.copy(
                                        selectedItem = item,
                                        description = line.description.ifBlank { item.name },
                                        amount = suggestedAmount(item, qty),
                                        amountManuallyEdited = false
                                    )
                                )
                                itemExpanded = false
                            }
                        )
                    }
                }
            }

            if (line.selectedItem != null) {
                OutlinedTextField(
                    value = line.quantityInput,
                    onValueChange = { newQty ->
                        val item = line.selectedItem
                        val amount = if (!line.amountManuallyEdited && item != null) {
                            newQty.toIntOrNull()?.let { suggestedAmount(item, it) } ?: line.amount
                        } else line.amount
                        onChange(line.copy(quantityInput = newQty, amount = amount))
                    },
                    label = { Text("Quantity") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = exceedsStock,
                    supportingText = {
                        if (exceedsStock) {
                            Text("Only ${line.selectedItem.quantity} in stock")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            OutlinedTextField(
                value = line.amount,
                onValueChange = { onChange(line.copy(amount = it, amountManuallyEdited = true)) },
                label = { Text("Amount (UGX)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}