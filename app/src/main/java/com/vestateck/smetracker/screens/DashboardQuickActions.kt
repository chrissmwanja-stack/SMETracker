package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vestateck.smetracker.utils.CurrencyUtils

// Dashboard's Quick Actions row + the Expenses/Tasks shortcut cards -
// split out of DashboardScreen.kt (was 705 lines). Called from
// DashboardScreen's main composable.

@Composable
internal fun QuickActionsSection(isTablet: Boolean, onAddSale: () -> Unit, onAddDebt: () -> Unit, onAddCustomer: () -> Unit, onAddInventory: () -> Unit, onViewCustomers: () -> Unit, onViewInventory: () -> Unit, onViewExpenses: () -> Unit, onViewTasks: () -> Unit) {
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
                OutlinedButton(onClick = onViewExpenses, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Expenses", fontSize = 14.sp) }
                OutlinedButton(onClick = onViewTasks, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Tasks", fontSize = 14.sp) }
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
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onViewExpenses, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Expenses", fontSize = 12.sp) }
                OutlinedButton(onClick = onViewTasks, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Tasks", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
internal fun ExpensesTasksSection(totalExpenses: Double, pendingTaskCount: Int, onViewExpenses: () -> Unit, onViewTasks: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), onClick = onViewExpenses) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Expenses", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(4.dp))
                Text(CurrencyUtils.formatUgx(totalExpenses), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), onClick = onViewTasks) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Pending Tasks", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(4.dp))
                Text("$pendingTaskCount", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
