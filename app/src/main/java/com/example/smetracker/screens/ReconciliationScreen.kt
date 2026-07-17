package com.example.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.smetracker.data.entities.InventoryItem
import com.example.smetracker.data.entities.Sale
import com.example.smetracker.utils.CurrencyUtils
import com.example.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconciliationScreen(viewModel: SMEViewModel, navController: NavController) {
    val unreconciledSales by viewModel.unreconciledSales.collectAsState()
    val unreconciledItems by viewModel.unreconciledInventoryItems.collectAsState()
    
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
            }

            if (selectedTab == 0) {
                if (unreconciledSales.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("All sales reconciled", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(unreconciledSales, key = { it.id }) { sale ->
                            SaleReconciliationItem(sale, onReconcile = { cost -> viewModel.reconcileSale(sale.id, cost) })
                        }
                    }
                }
            } else {
                if (unreconciledItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("All inventory reconciled", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(unreconciledItems, key = { it.id }) { item ->
                            InventoryReconciliationItem(item, onReconcile = { cost -> viewModel.reconcileInventoryCost(item.id, cost) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SaleReconciliationItem(sale: Sale, onReconcile: (Double) -> Unit) {
    var costPrice by remember { mutableStateOf("") }
    
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = costPrice,
                    onValueChange = { costPrice = it },
                    label = { Text("Cost Price per Unit") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = { costPrice.toDoubleOrNull()?.let { onReconcile(it) } },
                    enabled = costPrice.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Reconcile")
                }
            }
        }
    }
}

@Composable
fun InventoryReconciliationItem(item: InventoryItem, onReconcile: (Double) -> Unit) {
    var costPrice by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (item.category.isNotBlank()) {
                Text(item.category, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Selling Price: ${CurrencyUtils.formatUgx(item.sellingPrice)}", fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = costPrice,
                    onValueChange = { costPrice = it },
                    label = { Text("Cost Price") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = { costPrice.toDoubleOrNull()?.let { onReconcile(it) } },
                    enabled = costPrice.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Reconcile")
                }
            }
        }
    }
}
