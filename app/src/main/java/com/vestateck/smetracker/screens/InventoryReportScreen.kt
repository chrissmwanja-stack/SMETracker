// screens/InventoryReportScreen.kt
package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.vestateck.smetracker.screens.components.ReportPdfExportActions
import com.vestateck.smetracker.screens.components.ReportRow
import com.vestateck.smetracker.screens.components.rememberReportPdfExportState
import com.vestateck.smetracker.utils.CurrencyUtils
import com.vestateck.smetracker.utils.ReportPdfMappers
import com.vestateck.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryReportScreen(viewModel: SMEViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsState()
    val business by viewModel.business.collectAsState()
    val analytics = uiState.analytics

    val pdfExport = rememberReportPdfExportState()
    val pdfData = remember(business, analytics, uiState.inventoryItems, uiState.totalStockValue, uiState.lowStockItems) {
        ReportPdfMappers.inventoryReport(business, analytics, uiState.inventoryItems, uiState.totalStockValue, uiState.lowStockItems.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory Report") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ReportPdfExportActions(pdfExport, pdfData, "inventory_report")
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
            pdfExport.statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
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