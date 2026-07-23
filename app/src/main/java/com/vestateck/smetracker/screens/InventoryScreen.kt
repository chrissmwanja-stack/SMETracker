package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.vestateck.smetracker.navigation.Screen
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.utils.ImageUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel

import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(viewModel: SMEViewModel, navController: NavController, isOwner: Boolean = false) {
    val inventoryItems by viewModel.inventoryItems.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<InventoryItem?>(null) }
    val ugx = NumberFormat.getNumberInstance(Locale.US)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Bulk CSV import — sits alongside the FAB's single-item
                    // quick add rather than replacing it (see
                    // BulkAddInventoryScreen / InventoryCsvImporter).
                    IconButton(onClick = { navController.navigate(Screen.BulkAddInventory.route) }) {
                        Icon(
                            Icons.Default.UploadFile,
                            contentDescription = "Bulk Import from CSV",
                            tint = androidx.compose.ui.graphics.Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    selectedItem = null
                    showDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Item")
            }
        }
    ) { padding ->
        if (inventoryItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    Text("No inventory items", fontSize = 18.sp, color = androidx.compose.ui.graphics.Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(inventoryItems, key = { it.id }) { item ->
                    InventoryListItem(
                        item = item,
                        ugx = ugx,
                        isOwner = isOwner,
                        onEdit = {
                            selectedItem = item
                            showDialog = true
                        },
                        onDelete = {
                            ImageUtils.deleteLocalCopy(item.localImagePath)
                            viewModel.deleteInventoryItem(item)
                        },
                        onReceiveStock = { qty, note -> viewModel.receiveStock(item.id, qty, note) },
                        onRecount = { newQty, note -> viewModel.recountStock(item.id, newQty, note) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showDialog) {
        InventoryItemDialog(
            item = selectedItem,
            isOwner = isOwner,
            onDismiss = { showDialog = false },
            onConfirm = { item ->
                viewModel.upsertInventoryItem(item)
                showDialog = false
            }
        )
    }
}

@Composable
private fun InventoryListItem(
    item: InventoryItem,
    ugx: NumberFormat,
    isOwner: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReceiveStock: (quantity: Int, note: String?) -> Unit,
    onRecount: (newQuantity: Int, note: String) -> Unit
) {
    var showConfirmDelete by remember { mutableStateOf(false) }
    var showReceiveDialog by remember { mutableStateOf(false) }
    var showRecountDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InventoryThumbnail(
                    localImagePath = item.localImagePath,
                    imageUrl = item.imageUrl,
                    outOfStock = item.quantity == 0,
                    size = 56.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(item.category, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Qty: ${item.quantity}", fontSize = 14.sp,
                            color = if (item.quantity <= item.reorderLevel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Price: UGX ${ugx.format(item.sellingPrice.toLong())}", fontSize = 14.sp)
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { showConfirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            // Quantity no longer changes through Edit — see InventoryItemDialog.
            // These are the only two paths that can move it: Incoming Stock
            // (additive, anyone) and Recount (either direction, owner-only,
            // requires a reason). Both are logged via StockAdjustment.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { showReceiveDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Incoming Stock", fontSize = 13.sp)
                }
                if (isOwner) {
                    OutlinedButton(onClick = { showRecountDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FactCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Recount", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Delete Item") },
            text = { Text("Are you sure you want to delete ${item.name}?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirmDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    if (showReceiveDialog) {
        ReceiveStockDialog(
            itemName = item.name,
            onDismiss = { showReceiveDialog = false },
            onConfirm = { qty, note ->
                onReceiveStock(qty, note)
                showReceiveDialog = false
            }
        )
    }

    if (showRecountDialog) {
        RecountStockDialog(
            itemName = item.name,
            currentQuantity = item.quantity,
            onDismiss = { showRecountDialog = false },
            onConfirm = { newQty, note ->
                onRecount(newQty, note)
                showRecountDialog = false
            }
        )
    }
}
