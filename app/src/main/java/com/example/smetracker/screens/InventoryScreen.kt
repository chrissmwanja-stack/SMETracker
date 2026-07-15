package com.example.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.example.smetracker.data.entities.InventoryItem
import com.example.smetracker.viewmodel.SMEViewModel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(viewModel: SMEViewModel, navController: NavController) {
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
                        onEdit = {
                            selectedItem = item
                            showDialog = true
                        },
                        onDelete = { viewModel.deleteInventoryItem(item) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showDialog) {
        InventoryItemDialog(
            item = selectedItem,
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
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showConfirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
}

@Composable
private fun InventoryItemDialog(
    item: InventoryItem?,
    onDismiss: () -> Unit,
    onConfirm: (InventoryItem) -> Unit
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "") }
    var quantity by remember { mutableStateOf(item?.quantity?.toString() ?: "0") }
    var reorderLevel by remember { mutableStateOf(item?.reorderLevel?.toString() ?: "5") }
    var costPrice by remember { mutableStateOf(item?.costPrice?.toString() ?: "0") }
    var sellingPrice by remember { mutableStateOf(item?.sellingPrice?.toString() ?: "0") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (item == null) "Add Item" else "Edit Item", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Item Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = quantity, onValueChange = { quantity = it }, label = { Text("Qty") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = reorderLevel, onValueChange = { reorderLevel = it }, label = { Text("Reorder") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = costPrice, onValueChange = { costPrice = it }, label = { Text("Cost") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = sellingPrice, onValueChange = { sellingPrice = it }, label = { Text("Selling") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        onConfirm(
                            InventoryItem(
                                id = item?.id ?: 0,
                                name = name,
                                category = category,
                                quantity = quantity.toIntOrNull() ?: 0,
                                reorderLevel = reorderLevel.toIntOrNull() ?: 0,
                                costPrice = costPrice.toDoubleOrNull() ?: 0.0,
                                sellingPrice = sellingPrice.toDoubleOrNull() ?: 0.0,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }) { Text("Save") }
                }
            }
        }
    }
}
