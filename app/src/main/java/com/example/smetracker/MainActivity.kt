// MainActivity.kt
package com.example.smetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smetracker.data.database.SMEDatabase
import com.example.smetracker.data.remote.auth.AuthRepository
import com.example.smetracker.data.remote.auth.AuthViewModel
import com.example.smetracker.data.remote.auth.BusinessRepository
import com.example.smetracker.data.remote.auth.MemberRole
import com.example.smetracker.data.remote.auth.SessionManager
import com.example.smetracker.data.remote.sync.SyncEngine
import com.example.smetracker.navigation.Screen
import com.example.smetracker.repository.SMERepository
import com.example.smetracker.screens.*
import com.example.smetracker.ui.auth.AuthNavGate
import com.example.smetracker.ui.auth.AuthViewModelFactory
import com.example.smetracker.ui.components.OwnerOnlyGate
import com.example.smetracker.ui.theme.SMETrackerTheme
import com.example.smetracker.viewmodel.SMEViewModel
import com.example.smetracker.viewmodel.SMEViewModelFactory

class MainActivity : ComponentActivity() {
    private val database by lazy { SMEDatabase.getDatabase(this) }

    // Phase 2 auth dependencies — manual DI, matching the rest of the app.
    private val authRepository by lazy { AuthRepository() }
    private val sessionManager by lazy { SessionManager(applicationContext) }
    private val businessRepository by lazy { BusinessRepository() }
    private val authViewModelFactory by lazy {
        AuthViewModelFactory(authRepository, sessionManager)
    }

    // Phase 3 sync — Customer-only proof for now (see SyncEngine's class doc).
    // Scoped to lifecycleScope: cancelled automatically on Activity destroy,
    // matching the rest of this app's "no long-lived process-level DI" pattern.
    // start() is safe to call multiple times (no-ops if already listening) and
    // internally waits for sessionManager.sessionState to report a business
    // before attaching anything, so it's fine to construct this eagerly.
    private val syncEngine by lazy { SyncEngine(database.smeDao(), database.inventoryDao(), sessionManager, lifecycleScope) }

    private val viewModelFactory by lazy {
        SMEViewModelFactory(SMERepository(database.smeDao(), database.inventoryDao()), syncEngine)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SMETrackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

                    // Null until AuthNavGate hands off a resolved business + role.
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
                                // Idempotent: SyncEngine.start() no-ops if a listener is
                                // already attached, so this is safe to call on every
                                // login, including re-login after sign-out.
                                syncEngine.start()
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
                                        authViewModel.signOut()
                                        syncEngine.stop()
                                        entered = null
                                    },
                                    isOwner = currentEntry.second == MemberRole.OWNER,
                                    onAddWorker = { navController.navigate(Screen.AddWorker.route) }
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
                            composable(Screen.Expenses.route) { ExpensesScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.Tasks.route) { TasksScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.AddWorker.route) {
                                com.example.smetracker.ui.auth.AddWorkerScreen(
                                    businessId = currentEntry.first,
                                    businessRepository = businessRepository,
                                    onWorkerAdded = { navController.popBackStack() },
                                    onCancel = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}