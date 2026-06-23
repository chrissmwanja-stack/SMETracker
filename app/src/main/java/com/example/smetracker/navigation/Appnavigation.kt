package com.example.smetracker.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smetracker.data.database.SMEDatabase
import com.example.smetracker.repository.SMERepository
import com.example.smetracker.screens.*
import com.example.smetracker.viewmodel.SMEViewModel
import com.example.smetracker.viewmodel.SMEViewModelFactory

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val db = SMEDatabase.getDatabase(context)
    val repository = SMERepository(db.smeDao(), db.inventoryDao())
    val viewModel: SMEViewModel = viewModel(factory = SMEViewModelFactory(repository))

    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(viewModel = viewModel, navController = navController)
        }
        composable(Screen.AddSale.route) {
            AddSaleScreen(viewModel = viewModel, navController = navController)
        }
        composable(Screen.AddDebt.route) {
            AddDebtScreen(viewModel = viewModel, navController = navController)
        }
        composable(Screen.Customers.route) {
            CustomersScreen(viewModel = viewModel, navController = navController)
        }
        composable(Screen.Inventory.route) {
            InventoryScreen(viewModel = viewModel, navController = navController)
        }
    }
}
