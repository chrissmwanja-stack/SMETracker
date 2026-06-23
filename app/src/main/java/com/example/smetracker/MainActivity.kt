// MainActivity.kt
package com.example.smetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smetracker.data.database.SMEDatabase
import com.example.smetracker.navigation.Screen
import com.example.smetracker.repository.SMERepository
import com.example.smetracker.screens.*
import com.example.smetracker.ui.theme.SMETrackerTheme
import com.example.smetracker.viewmodel.SMEViewModel
import com.example.smetracker.viewmodel.SMEViewModelFactory

class MainActivity : ComponentActivity() {
    private val database by lazy { SMEDatabase.getDatabase(this) }
    private val viewModelFactory by lazy {
        SMEViewModelFactory(SMERepository(database.smeDao(), database.inventoryDao()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SMETrackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    val viewModel: SMEViewModel = viewModel(factory = viewModelFactory)

                    NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
                        composable(Screen.Dashboard.route) { DashboardScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.AddSale.route) { AddSaleScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.AddDebt.route) { AddDebtScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.AddCustomer.route) { AddCustomerScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.AddInventory.route) { AddInventoryScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.Customers.route) { CustomersScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.Inventory.route) { InventoryScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.Reports.route) { ReportsScreen(navController = navController) }
                        composable(Screen.SalesReport.route) { SalesReportScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.DebtReport.route) { DebtReportScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.InventoryReport.route) { InventoryReportScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.TopCustomers.route) { TopCustomersScreen(viewModel = viewModel, navController = navController) }
                        composable(Screen.PaymentBreakdown.route) { PaymentBreakdownScreen(viewModel = viewModel, navController = navController) }
                    }
                }
            }
        }
    }
}