// screens/ReportsScreen.kt
package com.example.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.smetracker.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
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
            ReportCard(
                title = "Sales Report",
                subtitle = "Daily, weekly, monthly sales",
                icon = Icons.Default.AttachMoney,
                onClick = { navController.navigate(Screen.SalesReport.route) }
            )
            ReportCard(
                title = "Debt Report",
                subtitle = "Paid, unpaid, overdue debts",
                icon = Icons.Default.Warning,
                onClick = { navController.navigate(Screen.DebtReport.route) }
            )
            ReportCard(
                title = "Inventory Report",
                subtitle = "Stock levels, categories, top products",
                icon = Icons.Default.Inventory,
                onClick = { navController.navigate(Screen.InventoryReport.route) }
            )
            ReportCard(
                title = "Top Customers",
                subtitle = "Best customers by revenue",
                icon = Icons.Default.People,
                onClick = { navController.navigate(Screen.TopCustomers.route) }
            )
            ReportCard(
                title = "Payment Breakdown",
                subtitle = "Cash vs debt sales",
                icon = Icons.Default.PieChart,
                onClick = { navController.navigate(Screen.PaymentBreakdown.route) }
            )
        }
    }
}

@Composable
private fun ReportCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}