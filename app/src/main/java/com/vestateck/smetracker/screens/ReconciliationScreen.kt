package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.ui.components.RecountStockDialog
import com.vestateck.smetracker.ui.components.SaleCostReviewDialog
import com.vestateck.smetracker.utils.CurrencyUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconciliationScreen(viewModel: SMEViewModel, navController: NavController) {
    val unreconciledSales by viewModel.unreconciledSales.collectAsState()
    val unreconciledItems by viewModel.unreconciledInventoryItems.collectAsState()
    val oversoldItems by viewModel.oversoldItems.collectAsState()
    // Needed so a sale's review dialog can suggest the linked item's current
    // cost price as a starting point (see SaleReconciliationDialog below).
    val inventoryItems by viewModel.inventoryItems.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reconciliation") },
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
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        BadgedBox(badge = { if (unreconciledSales.isNotEmpty()) Badge { Text(unreconciledSales.size.toString()) } }) {
                            Text("Sales")
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        BadgedBox(badge = { if (unreconciledItems.isNotEmpty()) Badge { Text(unreconciledItems.size.toString()) } }) {
                            Text("Inventory")
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        BadgedBox(badge = { if (oversoldItems.isNotEmpty()) Badge { Text(oversoldItems.size.toString()) } }) {
                            Text("Stock")
                        }
                    }
                )
            }

            when (selectedTab) {
                0 -> {
                    if (unreconciledSales.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("All sales reconciled", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(unreconciledSales, key = { it.id }) { sale ->
                                val linkedItem = sale.inventoryItemId?.let { id -> inventoryItems.find { it.id == id } }
                                SaleReconciliationCard(
                                    sale = sale,
                                    linkedItem = linkedItem,
                                    onReconcile = { costPricePerUnit -> viewModel.reconcileSale(sale.id, costPricePerUnit) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    if (unreconciledItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("All inventory reconciled", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(unreconciledItems, key = { it.id }) { item ->
                                InventoryReconciliationCard(
                                    item = item,
                                    onReconcile = { costPrice -> viewModel.reconcileInventoryCost(item.id, costPrice) }
                                )
                            }
                        }
                    }
                }
                else -> {
                    if (oversoldItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No oversold items", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(oversoldItems, key = { it.id }) { item ->
                                OversoldItemCard(
                                    item = item,
                                    onRecount = { newQty, note -> viewModel.recountStock(item.id, newQty, note) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaleReconciliationCard(
    sale: Sale,
    linkedItem: InventoryItem?,
    onReconcile: (Double) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(sale.customerName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(sale.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Price: ${CurrencyUtils.formatUgx(sale.amount)}", fontSize = 14.sp)
                Text("Qty: ${sale.quantity}", fontSize = 14.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showDialog = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Review")
            }
        }
    }

    if (showDialog) {
        SaleReconciliationDialog(
            sale = sale,
            linkedItem = linkedItem,
            onDismiss = { showDialog = false },
            onConfirm = { costPricePerUnit ->
                onReconcile(costPricePerUnit)
                showDialog = false
            }
        )
    }
}

@Composable
private fun SaleReconciliationDialog(
    sale: Sale,
    linkedItem: InventoryItem?,
    onDismiss: () -> Unit,
    onConfirm: (costPricePerUnit: Double) -> Unit
) {
    // Suggest the linked item's current cost price as a starting point — the
    // owner can still override it (e.g. if this particular unit was bought
    // at a different price), but most of the time it's the right answer and
    // saves retyping. Only a genuine positive cost counts as a suggestion;
    // 0.0 means the item's own cost is itself unreconciled, so there's
    // nothing useful to prefill.
    val suggestedCost = linkedItem?.costPrice?.takeIf { it > 0.0 }
    SaleCostReviewDialog(
        title = "Review Sale",
        sale = sale,
        initialCostPricePerUnit = suggestedCost,
        supportingText = when {
            suggestedCost != null -> "Suggested from ${linkedItem.name}: ${CurrencyUtils.formatUgx(suggestedCost)}"
            linkedItem != null -> "${linkedItem.name}'s own cost price hasn't been set yet"
            else -> null
        },
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun InventoryReconciliationCard(
    item: InventoryItem,
    onReconcile: (Double) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (item.category.isNotBlank()) {
                Text(item.category, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Selling Price: ${CurrencyUtils.formatUgx(item.sellingPrice)}", fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showDialog = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Set Cost")
            }
        }
    }

    if (showDialog) {
        InventoryReconciliationDialog(
            item = item,
            onDismiss = { showDialog = false },
            onConfirm = { costPrice ->
                onReconcile(costPrice)
                showDialog = false
            }
        )
    }
}

// Two offline devices can each validly see enough stock and sell against
// it — combined, more than actually existed. That's not a sync bug (see
// InventoryDao.applyRemoteStockAdjustment / InventorySync's pull listener,
// which fixed the actual bug where one device's stock change could
// silently clobber another's); it's a real business event that needs a
// human decision, same as any other stock discrepancy. Resolution reuses
// the same Recount action as InventoryScreen — the owner enters the actual
// physical count and a reason, same as correcting an everyday miscount.
@Composable
private fun OversoldItemCard(
    item: InventoryItem,
    onRecount: (newQuantity: Int, note: String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (item.category.isNotBlank()) {
                Text(item.category, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "On record: ${item.quantity}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showDialog = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Recount")
            }
        }
    }

    if (showDialog) {
        RecountStockDialog(
            itemName = item.name,
            currentQuantity = item.quantity,
            onDismiss = { showDialog = false },
            onConfirm = { newQty, note ->
                onRecount(newQty, note)
                showDialog = false
            }
        )
    }
}

@Composable
private fun InventoryReconciliationDialog(
    item: InventoryItem,
    onDismiss: () -> Unit,
    onConfirm: (costPrice: Double) -> Unit
) {
    // No prefill here — unlike a sale, there's no "last known good" cost to
    // suggest for a brand-new item; a worker-created item's costPrice is
    // always the unset 0.0 default (see SMEViewModel.upsertInventoryItem).
    var costPriceInput by remember { mutableStateOf("") }
    val costPrice = costPriceInput.toDoubleOrNull()
    val projectedMargin = costPrice?.let { item.sellingPrice - it }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Set Cost Price", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (item.category.isNotBlank()) {
                    Text(item.category, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Text("Selling Price: ${CurrencyUtils.formatUgx(item.sellingPrice)}", fontSize = 14.sp)

                OutlinedTextField(
                    value = costPriceInput,
                    onValueChange = { costPriceInput = it },
                    label = { Text("Cost Price") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Margin per unit", fontSize = 14.sp)
                        Text(
                            text = projectedMargin?.let { CurrencyUtils.formatUgx(it) } ?: "—",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = when {
                                projectedMargin == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                projectedMargin < 0 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { costPrice?.let { onConfirm(it) } },
                        enabled = costPrice != null
                    ) { Text("Confirm") }
                }
            }
        }
    }
}