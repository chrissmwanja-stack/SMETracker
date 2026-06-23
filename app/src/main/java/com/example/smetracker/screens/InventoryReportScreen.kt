// screens/InventoryReportScreen.kt
package com.example.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.smetracker.screens.components.ReportRow
import com.example.smetracker.utils.CurrencyUtils
import com.example.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryReportScreen(viewModel: SMEViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsState()
    val analytics = uiState.analytics

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory Report") },
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
            ReportRow("Total Products", "${uiState.inventoryItems.size} SKUs", CurrencyUtils.formatUgx(uiState.totalStockValue))
            HorizontalDivider()
            ReportRow("Total Units", "${analytics.totalStockUnits} units", "")
            HorizontalDivider()
            ReportRow("Low Stock", "${uiState.lowStockItems.size} items", "", color = if (uiState.lowStockItems.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            ReportRow("Out of Stock", "${analytics.outOfStockCount} items", "", color = if (analytics.outOfStockCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)

            if (analytics.categoryBreakdown.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("By Category", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                analytics.categoryBreakdown.forEach { (cat, qty) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cat)
                        Text("$qty units", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    }
                }
            }

            if (analytics.topSellingProducts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Top Selling Products", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                analytics.topSellingProducts.forEachIndexed { i, product ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${i + 1}. ${product.name}")
                        Text(CurrencyUtils.formatUgx(product.revenue), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    }
                }
            }
        }
    }
}