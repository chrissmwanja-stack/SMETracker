// screens/DashboardScreen.kt
package com.example.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.smetracker.data.DashboardAnalytics
import com.example.smetracker.data.DashboardUiState
import com.example.smetracker.data.ProductRanking
import com.example.smetracker.data.CustomerRanking
import com.example.smetracker.data.entities.InventoryItem
import com.example.smetracker.data.entities.Sale
import com.example.smetracker.navigation.Screen
import com.example.smetracker.ui.components.ReportRow
import com.example.smetracker.utils.CurrencyUtils
import com.example.smetracker.utils.WindowSize
import com.example.smetracker.utils.rememberWindowSize
import com.example.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: SMEViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsState()
    val windowSize = rememberWindowSize()
    val isTablet = windowSize != WindowSize.COMPACT
    val horizontalPadding = if (isTablet) 32.dp else 16.dp

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SME Tracker", fontWeight = FontWeight.Bold, fontSize = if (isTablet) 24.sp else 20.sp)
                        Text("Business Overview", fontSize = if (isTablet) 14.sp else 12.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(Screen.AddSale.route) },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Sale")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { SummarySection(isTablet = isTablet, uiState = uiState) }
            item {
                QuickActionsSection(
                    isTablet = isTablet,
                    onAddSale = { navController.navigate(Screen.AddSale.route) },
                    onAddDebt = { navController.navigate(Screen.AddDebt.route) },
                    onAddCustomer = { navController.navigate(Screen.AddCustomer.route) },
                    onAddInventory = { navController.navigate(Screen.AddInventory.route) },
                    onViewCustomers = { navController.navigate(Screen.Customers.route) },
                    onViewInventory = { navController.navigate(Screen.Inventory.route) }
                )
            }
            item { ReportsSection(isTablet = isTablet, uiState = uiState, onViewInventory = { navController.navigate(Screen.Inventory.route) }) }
            item { Text("Recent Sales", fontWeight = FontWeight.SemiBold, fontSize = if (isTablet) 18.sp else 16.sp) }
            if (uiState.recentSales.isEmpty()) {
                item { EmptyStateCard("No sales recorded yet") }
            } else {
                if (isTablet) {
                    item { SalesGrid(sales = uiState.recentSales.take(10)) }
                } else {
                    items(items = uiState.recentSales.take(10), key = { it.id }) { sale ->
                        SaleItem(sale = sale)
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun SummarySection(isTablet: Boolean, uiState: DashboardUiState) {
    val hasLowStock = uiState.lowStockItems.isNotEmpty()
    Column {
        SectionTitle("Summary", isTablet)
        Spacer(Modifier.height(8.dp))
        if (isTablet) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard(Modifier.weight(1f), "Today's Revenue", CurrencyUtils.formatUgx(uiState.todayRevenue), Icons.Default.AttachMoney, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                SummaryCard(Modifier.weight(1f), "Total Revenue", CurrencyUtils.formatUgx(uiState.totalRevenue), Icons.Default.AttachMoney, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                SummaryCard(Modifier.weight(1f), "Outstanding Debt", CurrencyUtils.formatUgx(uiState.totalOutstandingDebt), Icons.Default.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                SummaryCard(Modifier.weight(1f), "Customers", "${uiState.customers.size}", Icons.Default.People, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard(Modifier.weight(1f), "Stock Value", CurrencyUtils.formatUgx(uiState.totalStockValue), Icons.Default.Inventory, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                SummaryCard(Modifier.weight(1f), "Low Stock", "${uiState.lowStockItems.size} items", Icons.Default.WarningAmber, if (hasLowStock) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, if (hasLowStock) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(2f))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard(Modifier.weight(1f), "Today's Revenue", CurrencyUtils.formatUgx(uiState.todayRevenue), Icons.Default.AttachMoney, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                SummaryCard(Modifier.weight(1f), "Total Revenue", CurrencyUtils.formatUgx(uiState.totalRevenue), Icons.Default.AttachMoney, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard(Modifier.weight(1f), "Outstanding Debt", CurrencyUtils.formatUgx(uiState.totalOutstandingDebt), Icons.Default.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                SummaryCard(Modifier.weight(1f), "Customers", "${uiState.customers.size}", Icons.Default.People, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard(Modifier.weight(1f), "Stock Value", CurrencyUtils.formatUgx(uiState.totalStockValue), Icons.Default.Inventory, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                SummaryCard(Modifier.weight(1f), "Low Stock", "${uiState.lowStockItems.size} items", Icons.Default.WarningAmber, if (hasLowStock) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, if (hasLowStock) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuickActionsSection(isTablet: Boolean, onAddSale: () -> Unit, onAddDebt: () -> Unit, onAddCustomer: () -> Unit, onAddInventory: () -> Unit, onViewCustomers: () -> Unit, onViewInventory: () -> Unit) {
    Column {
        SectionTitle("Quick Actions", isTablet)
        Spacer(Modifier.height(8.dp))
        if (isTablet) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAddSale, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("+ Sale", fontSize = 14.sp) }
                Button(onClick = onAddDebt, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("+ Debt", fontSize = 14.sp) }
                Button(onClick = onAddCustomer, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) { Text("+ Customer", fontSize = 14.sp) }
                Button(onClick = onAddInventory, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text("+ Inventory", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onViewCustomers, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("View Customers", fontSize = 14.sp) }
                OutlinedButton(onClick = onViewInventory, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("View Inventory", fontSize = 14.sp) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAddSale, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("+ Sale", fontSize = 12.sp) }
                Button(onClick = onAddDebt, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("+ Debt", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAddCustomer, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) { Text("+ Customer", fontSize = 12.sp) }
                Button(onClick = onAddInventory, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text("+ Inventory", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onViewCustomers, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Customers", fontSize = 12.sp) }
                OutlinedButton(onClick = onViewInventory, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Inventory", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ReportsSection(isTablet: Boolean, uiState: DashboardUiState, onViewInventory: () -> Unit) {
    Column {
        SectionTitle("Reports", isTablet)
        Spacer(Modifier.height(8.dp))
        if (isTablet) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SalesReportCard(uiState.analytics)
                    TopCustomersCard(uiState.analytics.topCustomers)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DebtReportCard(uiState.analytics)
                    InventoryReportCard(
                        totalItems = uiState.inventoryItems.size,
                        totalUnits = uiState.analytics.totalStockUnits,
                        stockValue = uiState.inventoryItems.sumOf { it.quantity * it.sellingPrice },
                        lowStockCount = uiState.lowStockItems.size,
                        outOfStockCount = uiState.analytics.outOfStockCount,
                        categoryBreakdown = uiState.analytics.categoryBreakdown,
                        topSellingProducts = uiState.analytics.topSellingProducts
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (uiState.lowStockItems.isNotEmpty()) { LowStockBanner(uiState.lowStockItems, onViewInventory) }
                SalesReportCard(uiState.analytics)
                InventoryReportCard(
                    totalItems = uiState.inventoryItems.size,
                    totalUnits = uiState.analytics.totalStockUnits,
                    stockValue = uiState.inventoryItems.sumOf { it.quantity * it.sellingPrice },
                    lowStockCount = uiState.lowStockItems.size,
                    outOfStockCount = uiState.analytics.outOfStockCount,
                    categoryBreakdown = uiState.analytics.categoryBreakdown,
                    topSellingProducts = uiState.analytics.topSellingProducts
                )
                DebtReportCard(uiState.analytics)
                TopCustomersCard(uiState.analytics.topCustomers)
            }
        }
    }
}

@Composable
private fun SalesGrid(sales: List<Sale>) {
    val chunked = sales.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chunked.forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { sale -> SaleItem(sale = sale, modifier = Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, isTablet: Boolean) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = if (isTablet) 18.sp else 16.sp)
}

@Composable
private fun EmptyStateCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun SummaryCard(modifier: Modifier = Modifier, label: String, value: String, icon: ImageVector, containerColor: androidx.compose.ui.graphics.Color, contentColor: androidx.compose.ui.graphics.Color) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 11.sp, color = contentColor.copy(alpha = 0.8f))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = contentColor)
        }
    }
}

@Composable
private fun SaleItem(sale: Sale, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sale.customerName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(sale.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(CurrencyUtils.formatUgx(sale.amount), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SalesReportCard(analytics: DashboardAnalytics) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sales Report", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            ReportRow("Today", "${analytics.dailySales.count} sales", CurrencyUtils.formatUgx(analytics.dailySales.revenue))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("This Week", "${analytics.weeklySales.count} sales", CurrencyUtils.formatUgx(analytics.weeklySales.revenue))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("This Month", "${analytics.monthlySales.count} sales", CurrencyUtils.formatUgx(analytics.monthlySales.revenue))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("All Time", "${analytics.allTimeSales.count} sales", CurrencyUtils.formatUgx(analytics.allTimeSales.revenue), isBold = true)
        }
    }
}

@Composable
private fun DebtReportCard(analytics: DashboardAnalytics) {
    val totalDebt = analytics.paidDebtTotal + analytics.unpaidDebtTotal
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Debt Report", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            ReportRow("Total Debts", "${analytics.paidDebts.size + analytics.unpaidDebts.size} records", CurrencyUtils.formatUgx(totalDebt))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("Paid", "${analytics.paidDebts.size} debts", CurrencyUtils.formatUgx(analytics.paidDebtTotal), valueColor = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("Unpaid", "${analytics.unpaidDebts.size} debts", CurrencyUtils.formatUgx(analytics.unpaidDebtTotal), valueColor = MaterialTheme.colorScheme.error)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("Overdue", "${analytics.overdueDebts.size} debts", "", valueColor = MaterialTheme.colorScheme.error, isBold = true)
        }
    }
}

@Composable
private fun InventoryReportCard(totalItems: Int, totalUnits: Int, stockValue: Double, lowStockCount: Int, outOfStockCount: Int, categoryBreakdown: Map<String, Int>, topSellingProducts: List<ProductRanking>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Inventory Report", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            ReportRow("Total Products", "$totalItems SKUs", CurrencyUtils.formatUgx(stockValue))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("Total Units in Stock", "$totalUnits units", "")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("Low Stock", "$lowStockCount items", "", valueColor = if (lowStockCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReportRow("Out of Stock", "$outOfStockCount items", "", valueColor = if (outOfStockCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, isBold = outOfStockCount > 0)

            if (categoryBreakdown.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("By Category", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(6.dp))
                categoryBreakdown.entries.take(4).forEach { (cat, qty) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(cat, fontSize = 13.sp)
                        Text("$qty units", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (topSellingProducts.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Top Selling Products", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(6.dp))
                topSellingProducts.take(5).forEachIndexed { i, product ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(22.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Column {
                                Text(product.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${product.unitsSold} sold", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                        Text(CurrencyUtils.formatUgx(product.revenue), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopCustomersCard(topCustomers: List<CustomerRanking>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Top Customers", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            if (topCustomers.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("No sales data yet", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else {
                topCustomers.forEachIndexed { index, customer ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Text(customer.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(CurrencyUtils.formatUgx(customer.totalSpent), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    if (index < topCustomers.lastIndex) { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LowStockBanner(items: List<InventoryItem>, onViewAll: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text("Low Stock Alert", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(8.dp))
            items.take(3).forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("${item.quantity} left", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (items.size > 3) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onViewAll, contentPadding = PaddingValues(0.dp)) {
                    Text("View all ${items.size} items →", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
