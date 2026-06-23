// screens/PaymentBreakdownScreen.kt
package com.example.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.smetracker.utils.CurrencyUtils
import com.example.smetracker.viewmodel.SMEViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentBreakdownScreen(viewModel: SMEViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsState()
    val breakdown = uiState.analytics.paymentBreakdown

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment Breakdown") },
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
            // Total
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Sales", fontWeight = FontWeight.SemiBold)
                    Text(
                        CurrencyUtils.formatUgx(breakdown.cash + breakdown.mtnMoMo + breakdown.airtelMoney + breakdown.debt),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }

            // Cash
            PaymentMethodCard(
                label = "Cash",
                amount = breakdown.cash,
                icon = Icons.Default.Payments,
                color = MaterialTheme.colorScheme.secondaryContainer
            )

            // MTN MoMo
            PaymentMethodCard(
                label = "MTN MoMo",
                amount = breakdown.mtnMoMo,
                icon = Icons.Default.PhoneAndroid,
                color = MaterialTheme.colorScheme.tertiaryContainer
            )

            // Airtel Money
            PaymentMethodCard(
                label = "Airtel Money",
                amount = breakdown.airtelMoney,
                icon = Icons.Default.Smartphone,
                color = MaterialTheme.colorScheme.tertiaryContainer
            )

            // Debt
            PaymentMethodCard(
                label = "Debt / Credit",
                amount = breakdown.debt,
                icon = Icons.Default.AccountBalanceWallet,
                color = MaterialTheme.colorScheme.errorContainer
            )
        }
    }
}

@Composable
private fun PaymentMethodCard(
    label: String,
    amount: Double,
    icon: ImageVector,
    color: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                CurrencyUtils.formatUgx(amount),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}