// screens/DebtReportScreen.kt
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
fun DebtReportScreen(viewModel: SMEViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsState()
    val business by viewModel.business.collectAsState()
    val analytics = uiState.analytics

    val totalDebt = analytics.paidDebtTotal + analytics.unpaidDebtTotal

    val pdfExport = rememberReportPdfExportState()
    val pdfData = remember(business, analytics) { ReportPdfMappers.debtReport(business, analytics) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debt Report") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ReportPdfExportActions(pdfExport, pdfData, "debt_report")
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