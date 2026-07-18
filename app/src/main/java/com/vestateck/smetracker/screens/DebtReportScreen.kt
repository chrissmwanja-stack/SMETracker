// screens/DebtReportScreen.kt
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
fun DebtReportScreen(viewModel: SMEViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsState()
    val analytics = uiState.analytics

    val totalDebt = analytics.paidDebtTotal + analytics.unpaidDebtTotal

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debt Report") },
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
            ReportRow("Total Debts", "${analytics.paidDebts.size + analytics.unpaidDebts.size} records", CurrencyUtils.formatUgx(totalDebt))
            HorizontalDivider()
            ReportRow("Paid", "${analytics.paidDebts.size} debts", CurrencyUtils.formatUgx(analytics.paidDebtTotal), color = MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            ReportRow("Unpaid", "${analytics.unpaidDebts.size} debts", CurrencyUtils.formatUgx(analytics.unpaidDebtTotal), color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            ReportRow("Overdue", "${analytics.overdueDebts.size} debts", "", color = MaterialTheme.colorScheme.error, isBold = true)
        }
    }
}