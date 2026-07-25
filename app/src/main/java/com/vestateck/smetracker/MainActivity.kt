// MainActivity.kt
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.remote.auth.AuthViewModel
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.data.remote.sync.SyncWorker
import com.vestateck.smetracker.navigation.Screen
import com.vestateck.smetracker.screens.*
import com.vestateck.smetracker.ui.auth.AuthNavGate
import com.vestateck.smetracker.ui.components.OwnerOnlyGate
import com.vestateck.smetracker.ui.theme.SMETrackerTheme
import com.vestateck.smetracker.viewmodel.SMEViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // All provided by di/DatabaseModule.kt, di/RepositoryModule.kt, and
    // di/SyncModule.kt - see those for what constructs each one and why.
    // Field injection (not constructor injection) because ComponentActivity
    // doesn't offer a Hilt-friendly constructor hook; Hilt populates these
    // between super.onCreate() and onCreate()'s body running.
    @Inject lateinit var database: SMEDatabase
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var businessRepository: BusinessRepository
    @Inject lateinit var syncEngine: SyncEngine

    // Registered eagerly (not `by lazy`) since ActivityResultLauncher must be
    // registered before the Activity reaches STARTED - registering it lazily
    // on first use from inside onEnterApp (called after setContent, i.e.
    // after STARTED) would throw. The callback itself is a no-op: whether the
    // owner grants this or not, the in-app badge on DashboardScreen still
    // works either way, this permission only gates the system notification.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Owner-only, matches ReconciliationNotifier's scope. No-ops below API 33
    // (permission didn't exist yet, notifications just worked) and if
    // already granted.
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
                    val authViewModel: AuthViewModel = hiltViewModel()

                    val currentEntry = entered
                    if (currentEntry == null) {
                        AuthNavGate(
                            authViewModel = authViewModel,
                            sessionManager = sessionManager,
                            businessRepository = businessRepository,
                            onEnterApp = { businessId, role ->
                                entered = businessId to role
                                // Idempotent: SyncEngine.start() no-ops if a listener is
                                // already attached, so this is safe to call on every
                                // login, including re-login after sign-out.
                                syncEngine.start()
                                SyncWorker.schedulePeriodicSync(applicationContext)
                                requestNotificationPermissionIfOwner(role)
                            }
                        )
                    } else {
                        val navController = rememberNavController()
                        val viewModel: SMEViewModel = hiltViewModel()

                        NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
                            composable(Screen.Dashboard.route) {
                                DashboardScreen(
                                    viewModel = viewModel,
                                    navController = navController,
                                    onSignOut = {
                                        // AuthNavGate re-collects sessionManager.sessionState
                                        // fresh the instant `entered` goes null. clearSession()
                                        // is an async DataStore write (real disk I/O) - flipping
                                        // `entered` before it lands meant the first read AuthNavGate
                                        // did could still see the old logged-in session and bounce
                                        // straight back into the app, so sign-out silently no-op'd
                                        // on the first tap and only "worked" the second time, once
                                        // the first attempt's write had actually landed. Waiting for
                                        // signOut()'s completion callback closes that race.
                                        authViewModel.signOut {
                                            syncEngine.stop()
                                            // Local Room storage has no businessId scoping on any
                                            // entity (see SMEDatabase.clearSyncedDataSuspending() doc) -
                                            // without clearing it here, the next sign-in (same device,
                                            // any business, including a freshly created one) starts
                                            // from whatever the previous business left behind. Only the
                                            // already-synced cache is cleared - any row still
                                            // pendingSync = true (recorded offline, not yet pushed) is
                                            // left in place and syncs automatically on next login, so
                                            // signing out mid-offline-work can't silently lose data.
                                            // Awaited - not fire-and-forget - and `entered` only flips
                                            // to null once the clear finishes, so AuthNavGate can't
                                            // route into a new sign-in/business-create flow, and
                                            // SyncEngine can't start re-populating tables for the next
                                            // business, until the previous business's synced data is
                                            // actually gone.
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
                                    onBusinessSettings = { navController.navigate(Screen.BusinessSettings.route) },
                                    onAbout = { navController.navigate(Screen.About.route) }
                                )
                            }
                            composable(Screen.AddSale.route) { AddSaleScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.AddDebt.route) { AddDebtScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.AddCustomer.route) { AddCustomerScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.AddInventory.route) { AddInventoryScreen(viewModel = viewModel, navController = navController, isOwner = currentEntry.second == MemberRole.OWNER) }
                            composable(Screen.BulkAddInventory.route) { BulkAddInventoryScreen(viewModel = viewModel, navController = navController, isOwner = currentEntry.second == MemberRole.OWNER) }
                            composable(Screen.Customers.route) { CustomersScreen(viewModel = viewModel, navController = navController) }
                            composable(Screen.Inventory.route) { InventoryScreen(viewModel = viewModel, navController = navController, isOwner = currentEntry.second == MemberRole.OWNER) }
                            composable(Screen.Reports.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    ReportsScreen(navController = navController)
                                }
                            }
                            composable(Screen.SalesReceipts.route) {
                                OwnerOnlyGate(isOwner = currentEntry.second == MemberRole.OWNER, navController = navController) {
                                    SalesReceiptsListScreen(viewModel = viewModel, navController = navController)
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
                            composable(Screen.About.route) {
                                AboutScreen(onBack = { navController.popBackStack() })
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