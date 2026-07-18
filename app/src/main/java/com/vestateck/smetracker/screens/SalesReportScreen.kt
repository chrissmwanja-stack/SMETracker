// screens/SalesReportScreen.kt
package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.vestateck.smetracker.ui.components.ReportRow
import com.vestateck.smetracker.utils.CurrencyUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesReportScreen(viewModel: SMEViewModel, navController: NavController, isOwner: Boolean = false) {
    val uiState by viewModel.uiState.collectAsState()
    val analytics = uiState.analytics
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales Report") },
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
                .padding(16.dp)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Revenue") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Units Sold") }
                )
            }

            Spacer(Modifier.height(16.dp))

            if (selectedTab == 0) {
                ReportRow("Today", "${analytics.dailySales.count} sales", CurrencyUtils.formatUgx(analytics.dailySales.revenue))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ReportRow("This Week", "${analytics.weeklySales.count} sales", CurrencyUtils.formatUgx(analytics.weeklySales.revenue))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ReportRow("This Month", "${analytics.monthlySales.count} sales", CurrencyUtils.formatUgx(analytics.monthlySales.revenue))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ReportRow("All Time", "${analytics.allTimeSales.count} sales", CurrencyUtils.formatUgx(analytics.allTimeSales.revenue), isBold = true)
            } else {
                ReportRow("Today", "${analytics.dailySales.count} sales", "${analytics.dailySales.count} units")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ReportRow("This Week", "${analytics.weeklySales.count} sales", "${analytics.weeklySales.count} units")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ReportRow("This Month", "${analytics.monthlySales.count} sales", "${analytics.monthlySales.count} units")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ReportRow("All Time", "${analytics.allTimeSales.count} sales", "${analytics.allTimeSales.count} units", isBold = true)
            }

            Spacer(Modifier.height(16.dp))

            // Profit & Loss is derived from costPrice/profit — owner-only
            // data. SyncEngine never pulls saleFinancials down for a worker
            // session, so a worker's local copy is always 0; hide the card
            // rather than show a confusing all-zero P&L.
            if (isOwner) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Profit & Loss", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        ReportRow("Today", "Gross ${CurrencyUtils.formatUgx(analytics.dailySales.profit)} − Exp ${CurrencyUtils.formatUgx(analytics.dailySales.expenses)}", CurrencyUtils.formatUgx(analytics.dailySales.netProfit))
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        ReportRow("This Week", "Gross ${CurrencyUtils.formatUgx(analytics.weeklySales.profit)} − Exp ${CurrencyUtils.formatUgx(analytics.weeklySales.expenses)}", CurrencyUtils.formatUgx(analytics.weeklySales.netProfit))
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        ReportRow("This Month", "Gross ${CurrencyUtils.formatUgx(analytics.monthlySales.profit)} − Exp ${CurrencyUtils.formatUgx(analytics.monthlySales.expenses)}", CurrencyUtils.formatUgx(analytics.monthlySales.netProfit))
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        ReportRow("All Time", "Gross ${CurrencyUtils.formatUgx(analytics.allTimeSales.profit)} − Exp ${CurrencyUtils.formatUgx(analytics.allTimeSales.expenses)}", CurrencyUtils.formatUgx(analytics.allTimeSales.netProfit), isBold = true)
                    }
                }
            }
        }
    }
}