// screens/DashboardScreen.kt
package com.vestateck.smetracker.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.vestateck.smetracker.data.DashboardAnalytics
import com.vestateck.smetracker.data.DashboardUiState
import com.vestateck.smetracker.data.ProductRanking
import com.vestateck.smetracker.data.CustomerRanking
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.navigation.Screen
import com.vestateck.smetracker.ui.components.ReportRow
import com.vestateck.smetracker.ui.components.SaleCostReviewDialog
import com.vestateck.smetracker.utils.CurrencyUtils
import com.vestateck.smetracker.utils.WindowSize
import com.vestateck.smetracker.utils.rememberWindowSize
import com.vestateck.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: SMEViewModel,
    navController: NavController,
    onSignOut: () -> Unit = {},
    isOwner: Boolean = false,
    onAddWorker: () -> Unit = {},
    onBusinessSettings: () -> Unit = {},
    onAbout: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val businessName by viewModel.businessName.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val pendingTasks by viewModel.pendingTasks.collectAsState()
    val windowSize = rememberWindowSize()
    val isTablet = windowSize != WindowSize.COMPACT
    val horizontalPadding = if (isTablet) 32.dp else 16.dp
    // Which sale (if any) the owner has tapped to revise its cost - see the
    // edit-cost dialog wired in below the LazyColumn.
    var saleToEditCost by remember { mutableStateOf<Sale?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            businessName.ifBlank { "SME Tracker" },
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isTablet) 24.sp else 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("Business Overview", fontSize = if (isTablet) 14.sp else 12.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    }
                },
                actions = {
                    if (isOwner) {
                        IconButton(onClick = { navController.navigate(Screen.Reports.route) }) {
                            Icon(
                                Icons.Default.Assessment,
                                contentDescription = "Reports",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    if (isOwner) {
                        val unreconciledCount by viewModel.unreconciledCount.collectAsState()
                        IconButton(onClick = { navController.navigate(Screen.Reconciliation.route) }) {
                            BadgedBox(badge = {
                                if (unreconciledCount > 0) {
                                    Badge { Text(unreconciledCount.toString()) }
                                }
                            }) {
                                Icon(
                                    Icons.Default.PriceCheck,
                                    contentDescription = "Reconciliation",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                    if (isOwner) {
                        IconButton(onClick = onAddWorker) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = "Add worker",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    if (isOwner) {
                        IconButton(onClick = onBusinessSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Business settings",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    // Not gated by isOwner, unlike the buttons above - a Worker's
                    // data is collected too, so the privacy policy link inside
                    // AboutScreen needs to be reachable by both roles, not just
                    // the owner-only BusinessSettingsScreen.
                    IconButton(onClick = onAbout) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "About",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(
                            Icons.Default.Logout,
                            contentDescription = "Sign out",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
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
            item { SummarySection(isTablet = isTablet, uiState = uiState, isOwner = isOwner) }
            item {
                QuickActionsSection(
                    isTablet = isTablet,
                    onAddSale = { navController.navigate(Screen.AddSale.route) },
                    onAddDebt = { navController.navigate(Screen.AddDebt.route) },
                    onAddCustomer = { navController.navigate(Screen.AddCustomer.route) },
                    onAddInventory = { navController.navigate(Screen.AddInventory.route) },
                    onViewCustomers = { navController.navigate(Screen.Customers.route) },
                    onViewInventory = { navController.navigate(Screen.Inventory.route) },
                    onViewExpenses = { navController.navigate(Screen.Expenses.route) },
                    onViewTasks = { navController.navigate(Screen.Tasks.route) }
                )
            }
            item {
                ExpensesTasksSection(
                    totalExpenses = expenses.sumOf { it.amount },
                    pendingTaskCount = pendingTasks.size,
                    onViewExpenses = { navController.navigate(Screen.Expenses.route) },
                    onViewTasks = { navController.navigate(Screen.Tasks.route) }
                )
            }

            if (isOwner) {
                item { ReportsSection(isTablet = isTablet, uiState = uiState, onViewInventory = { navController.navigate(Screen.Inventory.route) }) }
            }
            item { Text("Recent Sales", fontWeight = FontWeight.SemiBold, fontSize = if (isTablet) 18.sp else 16.sp) }
            if (uiState.recentSales.isEmpty()) {
                item { EmptyStateCard("No sales recorded yet") }
            } else {
                if (isTablet) {
                    item {
                        SalesGrid(
                            sales = uiState.recentSales.take(10),
                            isOwner = isOwner,
                            onEditCost = { sale -> saleToEditCost = sale }
                        )
                    }
                } else {
                    items(items = uiState.recentSales.take(10), key = { it.id }) { sale ->
                        SaleItem(
                            sale = sale,
                            editable = isOwner && sale.inventoryItemId != null,
                            onClick = { saleToEditCost = sale }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // Edit-cost flow for a sale that's already reconciled (auto or manual)
    // but turned out to need a different number - e.g. this particular unit
    // was actually bought at a one-off price. Only ever offered for
    // isOwner && sale.inventoryItemId != null (see SaleItem/SalesGrid
    // above); a sale still awaiting its first review belongs in the
    // Reconciliation screen, not here.
    saleToEditCost?.let { sale ->
        val linkedItem = uiState.inventoryItems.find { it.id == sale.inventoryItemId }
        val currentCostPerUnit = if (sale.quantity > 0) sale.costPriceSnapshot / sale.quantity else 0.0
        SaleCostReviewDialog(
            title = "Edit Sale Cost",
            sale = sale,
            initialCostPricePerUnit = currentCostPerUnit,
            supportingText = if (linkedItem != null) {
                "Currently recorded at ${CurrencyUtils.formatUgx(currentCostPerUnit)} per unit"
            } else null,
            confirmLabel = "Save",
            onDismiss = { saleToEditCost = null },
            onConfirm = { costPricePerUnit ->
                viewModel.editSaleCost(sale.id, costPricePerUnit)
                saleToEditCost = null
            }
        )
    }
}

@Composable
private fun SummarySection(isTablet: Boolean, uiState: DashboardUiState, isOwner: Boolean) {
    val hasLowStock = uiState.lowStockItems.isNotEmpty()
    Column {
        SectionTitle("Summary", isTablet)
        Spacer(Modifier.height(8.dp))
        // Net profit is derived from costPrice/profit, which SyncEngine never
        // pulls down for a worker session (see SyncEngine's owner-only
        // listener gating) — so a worker's local copy is always 0. Gating in
        // the UI too avoids showing a confusing "UGX 0" banner instead of
        // just not showing owner-only data at all.
        if (isOwner) {
            NetProfitBanner(
                todayNetProfit = uiState.analytics.dailySales.netProfit,
                monthNetProfit = uiState.analytics.monthlySales.netProfit,
                isTablet = isTablet
            )
            Spacer(Modifier.height(if (isTablet) 12.dp else 10.dp))
        }
        if (isTablet) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard(Modifier.weight(1f), "Today's Revenue", CurrencyUtils.formatUgx(uiState.todayRevenue), Icons.Default.AttachMoney, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                SummaryCard(Modifier.weight(1f), "Total Revenue", CurrencyUtils.formatUgx(uiState.totalRevenue), Icons.Default.AttachMoney, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                SummaryCard(Modifier.weight(1f), "Outstanding Debt", CurrencyUtils.formatUgx(uiState.totalOutstandingDebt), Icons.Default.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                SummaryCard(Modifier.weight(1f), "Customers", "${uiState.analytics.totalCustomerCount}", Icons.Default.People, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Stock Value is quantity × costPrice — owner-only data (see above).
                if (isOwner) {
                    SummaryCard(Modifier.weight(1f), "Stock Value", CurrencyUtils.formatUgx(uiState.totalStockValue), Icons.Default.Inventory, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                }
                SummaryCard(Modifier.weight(1f), "Low Stock", "${uiState.lowStockItems.size} items", Icons.Default.WarningAmber, if (hasLowStock) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, if (hasLowStock) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(if (isOwner) 2f else 1f))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard(Modifier.weight(1f), "Today's Revenue", CurrencyUtils.formatUgx(uiState.todayRevenue), Icons.Default.AttachMoney, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                SummaryCard(Modifier.weight(1f), "Total Revenue", CurrencyUtils.formatUgx(uiState.totalRevenue), Icons.Default.AttachMoney, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard(Modifier.weight(1f), "Outstanding Debt", CurrencyUtils.formatUgx(uiState.totalOutstandingDebt), Icons.Default.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                SummaryCard(Modifier.weight(1f), "Customers", "${uiState.analytics.totalCustomerCount}", Icons.Default.People, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            // Stock Value is quantity × costPrice — owner-only data (see above).
            // A worker still gets the Low Stock card, just full-width instead
            // of sharing a row with the hidden Stock Value card.
            if (isOwner) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryCard(Modifier.weight(1f), "Stock Value", CurrencyUtils.formatUgx(uiState.totalStockValue), Icons.Default.Inventory, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    SummaryCard(Modifier.weight(1f), "Low Stock", "${uiState.lowStockItems.size} items", Icons.Default.WarningAmber, if (hasLowStock) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, if (hasLowStock) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.height(10.dp))
                SummaryCard(Modifier.fillMaxWidth(), "Low Stock", "${uiState.lowStockItems.size} items", Icons.Default.WarningAmber, if (hasLowStock) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, if (hasLowStock) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NetProfitBanner(todayNetProfit: Double, monthNetProfit: Double, isTablet: Boolean) {
    // Net profit (gross margin minus expenses) is the number owners actually need at a glance,
    // so it gets a full-width, color-coded banner rather than being buried in a sub-report.
    val isPositive = todayNetProfit >= 0
    val bannerColor = if (isPositive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val contentColor = if (isPositive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bannerColor),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (isTablet) 20.dp else 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (isPositive) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Today's Net Profit", fontSize = 13.sp, color = contentColor.copy(alpha = 0.85f))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    CurrencyUtils.formatUgx(todayNetProfit),
                    fontSize = if (isTablet) 28.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("This Month", fontSize = 12.sp, color = contentColor.copy(alpha = 0.7f))
                Text(
                    CurrencyUtils.formatUgx(monthNetProfit),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
            }
        }
    }
}