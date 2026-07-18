// data/DashboardUiState.kt
package com.example.smetracker.data

import com.example.smetracker.data.entities.Customer
import com.example.smetracker.data.entities.InventoryItem
import com.example.smetracker.data.entities.Sale

data class DashboardUiState(
    val totalRevenue: Double = 0.0,
    val todayRevenue: Double = 0.0,
    val todayProfit: Double = 0.0,
    val totalOutstandingDebt: Double = 0.0,
    val recentSales: List<Sale> = emptyList(),
    val customers: List<Customer> = emptyList(),
    val inventoryItems: List<InventoryItem> = emptyList(),
    val lowStockItems: List<InventoryItem> = emptyList(),
    val totalStockValue: Double = 0.0,
    val analytics: DashboardAnalytics = DashboardAnalytics.from(emptyList(), emptyList(), emptyList())
)