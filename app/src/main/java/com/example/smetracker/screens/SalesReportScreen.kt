// screens/SalesReportScreen.kt
package com.example.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.smetracker.ui.components.ReportRow
import com.example.smetracker.utils.CurrencyUtils
import com.example.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesReportScreen(viewModel: SMEViewModel, navController: NavController) {
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Profit Summary", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Today: ${CurrencyUtils.formatUgx(analytics.dailySales.profit)}")
                    Text("This Week: ${CurrencyUtils.formatUgx(analytics.weeklySales.profit)}")
                    Text("This Month: ${CurrencyUtils.formatUgx(analytics.monthlySales.profit)}")
                }
            }
        }
    }
}