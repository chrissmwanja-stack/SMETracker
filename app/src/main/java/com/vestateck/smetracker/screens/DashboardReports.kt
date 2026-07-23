package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vestateck.smetracker.data.DashboardAnalytics
import com.vestateck.smetracker.data.DashboardUiState
import com.vestateck.smetracker.data.ProductRanking
import com.vestateck.smetracker.data.CustomerRanking
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.ui.components.ReportRow
import com.vestateck.smetracker.utils.CurrencyUtils

// The owner-only Reports tab: ReportsSection lays out the individual
// report cards below, plus the low-stock banner. Split out of
// DashboardScreen.kt (was 705 lines). ReportsSection is called from
// DashboardScreen's main composable; everything else here is only
// ever called by ReportsSection, so stays file-private.

@Composable
internal fun ReportsSection(isTablet: Boolean, uiState: DashboardUiState, onViewInventory: () -> Unit) {
    Column {
        SectionTitle("Reports", isTablet)
        Spacer(Modifier.height(8.dp))
        if (isTablet) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // This whole section is owner-only now — see the isOwner gate
                    // at its call site — so ProfitReportCard's costPrice/profit
                    // data (owner-only per the rules) is safe to show unconditionally.
                    ProfitReportCard(uiState.analytics)
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
                ProfitReportCard(uiState.analytics)
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
private fun ProfitReportCard(analytics: DashboardAnalytics) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Profit & Loss", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            ProfitPeriodRow("Today", analytics.dailySales)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ProfitPeriodRow("This Week", analytics.weeklySales)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ProfitPeriodRow("This Month", analytics.monthlySales)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ProfitPeriodRow("All Time", analytics.allTimeSales, isBold = true)
        }
    }
}

@Composable
private fun ProfitPeriodRow(label: String, data: com.vestateck.smetracker.data.SalesPeriodData, isBold: Boolean = false) {
    val netColor = if (data.netProfit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium)
            Text(
                "Net: ${CurrencyUtils.formatUgx(data.netProfit)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = netColor
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Rev ${CurrencyUtils.formatUgx(data.revenue)}  •  Gross ${CurrencyUtils.formatUgx(data.profit)}  •  Exp ${CurrencyUtils.formatUgx(data.expenses)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
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
