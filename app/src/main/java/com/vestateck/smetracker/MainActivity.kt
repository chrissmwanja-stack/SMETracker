package com.vestateck.smetracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.remote.auth.AuthRepository
import com.vestateck.smetracker.data.remote.auth.AuthViewModel
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.data.remote.sync.SyncWorker
import com.vestateck.smetracker.navigation.Screen
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.screens.*
import com.vestateck.smetracker.ui.auth.AuthNavGate
import com.vestateck.smetracker.ui.auth.AuthViewModelFactory
import com.vestateck.smetracker.ui.components.OwnerOnlyGate
import com.vestateck.smetracker.ui.theme.SMETrackerTheme
import com.vestateck.smetracker.utils.ReceiptNumberGenerator
import com.vestateck.smetracker.viewmodel.SMEViewModel
import com.vestateck.smetracker.viewmodel.SMEViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val database by lazy { SMEDatabase.getDatabase(this) }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationPermissionIfOwner(role: MemberRole) {
        if (role != MemberRole.OWNER) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val authRepository by lazy { AuthRepository() }
    private val sessionManager by lazy { SessionManager(applicationContext) }
    private val businessRepository by lazy { BusinessRepository() }
    private val authViewModelFactory by lazy {
        AuthViewModelFactory(authRepository, sessionManager)
    }

    private val syncEngine by lazy { SyncEngine(database.smeDao(), database.inventoryDao(), sessionManager, lifecycleScope, applicationContext) }
    private val receiptNumberGenerator by lazy { ReceiptNumberGenerator(applicationContext) }

    private val viewModelFactory by lazy {
        SMEViewModelFactory(SMERepository(database.smeDao(), database.inventoryDao()), syncEngine, sessionManager, businessRepository, receiptNumberGenerator)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SMETrackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

                    var entered by remember {
                        mutableStateOf<Pair<String, MemberRole>?>(null)
                    }
                    val authViewModel: AuthViewModel = viewModel(factory = authViewModelFactory)

                    val currentEntry = entered
                    if (currentEntry == null) {
                        AuthNavGate(
                            context = applicationContext,
                            authViewModel = authViewModel,
                            sessionManager = sessionManager,
                            businessRepository = businessRepository,
                            onEnterApp = { businessId, role ->
                                entered = businessId to role
                                syncEngine.start()
                                SyncWorker.schedulePeriodicSync(applicationContext)
                                requestNotificationPermissionIfOwner(role)
                            }
                        )
                    } else {
                        val navController = rememberNavController()
                        val viewModel: SMEViewModel = viewModel(factory = viewModelFactory)

                        NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
                            composable(Screen.Dashboard.route) {
                                DashboardScreen(
                                    viewModel = viewModel,
                                    navController = navController,
                                    onSignOut = {
                                        authViewModel.signOut {
                                            syncEngine.stop()
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                database.clearSyncedDataSuspending()
                                                withContext(Dispatchers.Main) {
                                                    entered = null
                                                }
                                            }
                                        }
                                    },
                                    isOwner = currentEntry.second == MemberRole.OWNER,
                                    onAddWorker = { navController.navigate(Screen.AddWorker.route) },
                                    onBusinessSettings = { navController.navigate(Screen.BusinessSettings.route) }
                                )
                            }
                            composable(Screen.AddSale.route) { AddSaleScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.AddDebt.route) { AddDebtScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.AddCustomer.route) { AddCustomerScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.AddInventory.route) { AddInventoryScreen(viewModel = viewModel, navController = navController, isOwner = currentEntry.second == MemberRole.OWNER) }
                            composable(Screen.Customers.route) { CustomersScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.Inventory.route) { InventoryScreen(viewModel = viewModel, navController = navController, isOwner = currentEntry.second == MemberRole.OWNER) }
                            composable(Screen.Reports.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    ReportsScreen(navController = navController)
                                }
                            }
                            composable(Screen.SalesReport.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    SalesReportScreen(viewModel = viewModel, navController = navController, isOwner = currentEntry.second == MemberRole.OWNER)
                                }
                            }
                            composable(Screen.DebtReport.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    DebtReportScreen(viewModel = viewModel, navController = navController)
                                }
                            }
                            composable(Screen.InventoryReport.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    InventoryReportScreen(viewModel = viewModel, navController = navController)
                                }
                            }
                            composable(Screen.TopCustomers.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    TopCustomersScreen(viewModel = viewModel, navController = navController)
                                }
                            }
                            composable(Screen.PaymentBreakdown.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    PaymentBreakdownScreen(viewModel = viewModel, navController = navController)
                                }
                            }
                            composable(Screen.Reconciliation.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    ReconciliationScreen(viewModel = viewModel, navController = navController)
                                }
                            }
                            composable(Screen.Expenses.route) { ExpensesScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.Tasks.route) { TasksScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.AddWorker.route) {
                                com.vestateck.smetracker.ui.auth.AddWorkerScreen(
                                    businessId = currentEntry.first,
                                    businessRepository = businessRepository,
                                    onWorkerAdded = { navController.popBackStack() },
                                    onCancel = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.BusinessSettings.route) {
                                com.vestateck.smetracker.ui.auth.BusinessSettingsScreen(
                                    businessId = currentEntry.first,
                                    businessRepository = businessRepository,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable(
                                Screen.SaleReceipt.route,
                                arguments = listOf(navArgument("saleIds") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val saleIds = backStackEntry.arguments?.getString("saleIds")
                                    ?.split(",")
                                    ?.filter { it.isNotBlank() }
                                    ?: emptyList()
                                SaleReceiptScreen(saleIds = saleIds, viewModel = viewModel, navController = navController)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        syncEngine.stop()
        super.onDestroy()
    }
}