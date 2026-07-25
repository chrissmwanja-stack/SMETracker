// viewmodel/SMEViewModel.kt
package com.vestateck.smetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vestateck.smetracker.data.DashboardAnalytics
import com.vestateck.smetracker.data.DashboardUiState
import com.vestateck.smetracker.data.entities.*
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.model.Business
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.BulkInventoryRow
import com.vestateck.smetracker.utils.TimeUtils
import com.vestateck.smetracker.viewmodel.actions.CustomerActions
import com.vestateck.smetracker.viewmodel.actions.DebtActions
import com.vestateck.smetracker.viewmodel.actions.ExpenseActions
import com.vestateck.smetracker.viewmodel.actions.InventoryActions
import com.vestateck.smetracker.viewmodel.actions.ReconciliationActions
import com.vestateck.smetracker.viewmodel.actions.SaleActions
import com.vestateck.smetracker.viewmodel.actions.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// One product line from AddSaleScreen's cart - the subset of SaleLineItem
// that's already been validated/resolved (amount and quantity parsed,
// description defaulted) and is ready to become its own Sale row.
data class SaleLineInput(
    val description: String,
    val amount: Double,
    val inventoryItemId: String? = null,
    val quantity: Int = 1
)

/**
 * Obtained via hiltViewModel() - see di/ package for where each dependency
 * below comes from. The nullable defaults stay in place even under Hilt:
 * Dagger always supplies real instances in production (nullability doesn't
 * change which binding it looks up), but the defaults still let
 * SMEViewModelReconciliationTest construct this directly with just a
 * repository via plain `SMEViewModel(repository)`, same as before.
 */
@HiltViewModel
class SMEViewModel @Inject constructor(
    private val repository: SMERepository,
    // Nullable so tests/previews that don't need sync can keep constructing
    // this ViewModel with just a repository. Every mutation below - including
    // delete*() - calls syncEngine?.requestPush() after its local write.
    // Deletes are soft (repository.delete*() sets isDeleted=1, pendingSync=1
    // on the Room row rather than removing it), and the isDeleted flag rides
    // along in the entity's normal RemoteX push/pull just like any other
    // field - see SaleSync.pushPending, etc. Without the requestPush() call
    // here, a delete would still eventually reach Firestore via SyncWorker's
    // periodic job, but with up to a 15-minute delay instead of the
    // immediate push every other mutation gets.
    private val syncEngine: SyncEngine? = null,
    // Nullable for the same reason as syncEngine. Used only to stamp
    // recordedBy and the reconciliation flags at creation time (see
    // addSale/addInventoryItem/upsertInventoryItem) - when null, new sales
    // and inventory items are treated as owner-recorded/already-reconciled,
    // matching this ViewModel's pre-reconciliation behavior.
    private val sessionManager: SessionManager? = null,
    // Nullable for the same reason as syncEngine/sessionManager. Used only
    // to load the business's display name for the dashboard header - when
    // null (or when there's no session/businessId yet), businessName just
    // stays blank and callers fall back to a default label.
    private val businessRepository: BusinessRepository? = null,
    // Nullable for the same reason as syncEngine/sessionManager. Used only
    // to assign Sale.provisionalReceiptNumber at creation time (see
    // addSale) - when null, provisionalReceiptNumber stays blank, matching
    // pre-receipt-feature behavior for tests/previews.
    private val receiptNumberGenerator: com.vestateck.smetracker.utils.ReceiptNumberGenerator? = null
) : ViewModel() {

    val sales: StateFlow<List<Sale>> = repository.allSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customers: StateFlow<List<Customer>> = repository.allCustomers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debts: StateFlow<List<Debt>> = repository.allDebts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventoryItems: StateFlow<List<InventoryItem>> = repository.allInventoryItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expenses: StateFlow<List<Expense>> = repository.getAllExpenses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingTasks: StateFlow<List<Task>> = repository.getPendingTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -- Reconciliation (owner-only; screen is responsible for gating) --
    val unreconciledSales: StateFlow<List<Sale>> = repository.unreconciledSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreconciledInventoryItems: StateFlow<List<InventoryItem>> = repository.unreconciledInventoryItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val oversoldItems: StateFlow<List<InventoryItem>> = repository.oversoldItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreconciledCount: StateFlow<Int> = combine(
        repository.unreconciledSalesCount, repository.unreconciledInventoryCount, repository.oversoldItemsCount
    ) { sales, items, oversold -> (sales + items + oversold).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Full business record (name/phone/address) for the dashboard header and
    // the sale receipt document (Chunk B), loaded once per businessId change
    // rather than folded into the uiState combine() chain above - the
    // business's own details essentially never change during a session, so
    // there's no need to re-fetch on every sales/customers/etc. update.
    // Stays null if there's no session, no businessId yet, or the fetch
    // fails; callers should fall back to a sensible default in that case.
    val business: StateFlow<Business?> = (sessionManager?.sessionState ?: flowOf(null))
        .map { it?.businessId }
        .distinctUntilChanged()
        .map { businessId ->
            if (businessId == null || businessRepository == null) {
                null
            } else {
                businessRepository.getBusiness(businessId).getOrNull()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val businessName: StateFlow<String> = business
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Snapshot of "who's recording this and is it trustworthy", read fresh at
    // each mutation rather than cached, since the session can change (e.g.
    // sign-out/sign-in) across the ViewModel's lifetime.
    private suspend fun currentSession(): Pair<String, Boolean> {
        val session = sessionManager?.sessionState?.first()
        return (session?.phoneNumberE164 ?: "") to (session?.isOwner ?: true)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        sales, customers, debts, inventoryItems, expenses
    ) { sales, customers, debts, inventory, expenseList ->
        DashboardUiState(
            totalRevenue = sales.sumOf { it.amount },
            todayRevenue = sales.filter { it.date >= TimeUtils.getStartOfDay() }.sumOf { it.amount },
            todayProfit = sales.filter { it.date >= TimeUtils.getStartOfDay() }.sumOf { it.profit },
            totalOutstandingDebt = debts.filter { !it.isPaid }.sumOf { it.amount },
            recentSales = sales.sortedByDescending { it.date },
            customers = customers,
            inventoryItems = inventory,
            lowStockItems = inventory.filter { it.quantity > 0 && it.quantity <= it.reorderLevel },
            totalStockValue = inventory.sumOf { it.quantity * it.sellingPrice },
            analytics = DashboardAnalytics.from(sales, debts, inventory, expenseList, customers)
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DashboardUiState()
        )

    // Customer Actions - delegated to CustomerActions (Option A restructuring).
    private val customerActions = CustomerActions(repository, syncEngine)

    fun insertCustomer(customer: Customer) = viewModelScope.launch {
        customerActions.insertCustomer(customer)
    }

    fun addCustomer(name: String, phone: String = "", email: String = "") = viewModelScope.launch {
        customerActions.addCustomer(name, phone, email)
    }

    fun upsertCustomer(customer: Customer) = viewModelScope.launch {
        customerActions.upsertCustomer(customer)
    }

    fun deleteCustomer(customer: Customer) = viewModelScope.launch {
        customerActions.deleteCustomer(customer)
    }

    // Debt Actions - delegated to DebtActions (Option A restructuring).
    private val debtActions = DebtActions(repository, syncEngine)

    fun insertDebt(debt: Debt) = viewModelScope.launch {
        debtActions.insertDebt(debt)
    }

    fun markDebtAsPaid(debtId: String) = viewModelScope.launch {
        debtActions.markDebtAsPaid(debtId)
    }

    // Inventory Actions - delegated to InventoryActions (Option A restructuring).
    private val inventoryActions = InventoryActions(repository, syncEngine, ::currentSession)

    fun upsertInventoryItem(item: InventoryItem) = viewModelScope.launch {
        inventoryActions.upsertInventoryItem(item)
    }

    fun addInventoryItem(
        name: String,
        quantity: Int,
        sellingPrice: Double,
        category: String = "",
        costPrice: Double = 0.0,
        reorderLevel: Int = 5,
        localImagePath: String? = null,
        imagePendingUpload: Boolean = false,
        sku: String? = null
    ) = viewModelScope.launch {
        inventoryActions.addInventoryItem(
            name, quantity, sellingPrice, category, costPrice, reorderLevel,
            localImagePath, imagePendingUpload, sku
        )
    }

    fun addInventoryItemsBulk(rows: List<BulkInventoryRow>) = viewModelScope.launch {
        inventoryActions.addInventoryItemsBulk(rows)
    }

    fun deleteInventoryItem(item: InventoryItem) = viewModelScope.launch {
        inventoryActions.deleteInventoryItem(item)
    }

    fun getAdjustmentsForItem(itemId: String) = inventoryActions.getAdjustmentsForItem(itemId)

    fun receiveStock(itemId: String, quantity: Int, note: String? = null) = viewModelScope.launch {
        inventoryActions.receiveStock(itemId, quantity, note)
    }

    fun recountStock(itemId: String, newQuantity: Int, note: String) = viewModelScope.launch {
        inventoryActions.recountStock(itemId, newQuantity, note)
    }

    // Sale Actions - delegated to SaleActions (Option A restructuring).
    private val saleActions = SaleActions(
        repository, syncEngine, ::currentSession, receiptNumberGenerator, inventoryItems
    )

    fun addSale(
        customerName: String,
        description: String,
        amount: Double,
        paymentMethod: PaymentMethod,
        inventoryItemId: String? = null,
        quantity: Int = 1,
        customerId: String? = null
    ) = viewModelScope.launch {
        saleActions.addSale(customerName, description, amount, paymentMethod, inventoryItemId, quantity, customerId)
    }

    fun addSaleLines(
        customerName: String,
        selectedCustomerId: String?,
        saveAsNewCustomer: Boolean,
        paymentMethod: PaymentMethod,
        lines: List<SaleLineInput>,
        onSalesCreated: (List<Sale>) -> Unit = {}
    ) = viewModelScope.launch {
        saleActions.addSaleLines(
            customerName, selectedCustomerId, saveAsNewCustomer, paymentMethod, lines, onSalesCreated
        )
    }

    fun deleteSale(sale: Sale) = viewModelScope.launch {
        saleActions.deleteSale(sale)
    }

    // Expense Actions - delegated to ExpenseActions (Option A restructuring).
    private val expenseActions = ExpenseActions(repository, syncEngine)

    fun addExpense(
        description: String,
        amount: Double,
        category: String = "General",
        receiptNumber: String? = null,
        localReceiptPath: String? = null
    ) = viewModelScope.launch {
        expenseActions.addExpense(description, amount, category, receiptNumber, localReceiptPath)
    }

    fun deleteExpense(expense: Expense) = viewModelScope.launch {
        expenseActions.deleteExpense(expense)
    }

    // Task Actions - delegated to TaskActions (Option A restructuring).
    // Thin forwarding wrappers preserve the existing public API, so no
    // screen call site changes.
    private val taskActions = TaskActions(repository, syncEngine)

    fun addTask(title: String, description: String? = null, priority: String = "Medium", dueDate: Long? = null) = viewModelScope.launch {
        taskActions.addTask(title, description, priority, dueDate)
    }

    fun completeTask(taskId: String) = viewModelScope.launch {
        taskActions.completeTask(taskId)
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        taskActions.deleteTask(task)
    }

    // Reconciliation Actions - delegated to ReconciliationActions (Option A restructuring).
    private val reconciliationActions = ReconciliationActions(
        repository, syncEngine, unreconciledSales, sales
    )

    fun reconcileSale(saleId: String, costPricePerUnit: Double) = viewModelScope.launch {
        reconciliationActions.reconcileSale(saleId, costPricePerUnit)
    }

    fun reconcileInventoryCost(itemId: String, costPrice: Double) = viewModelScope.launch {
        reconciliationActions.reconcileInventoryCost(itemId, costPrice)
    }

    fun editSaleCost(saleId: String, costPricePerUnit: Double) = viewModelScope.launch {
        reconciliationActions.editSaleCost(saleId, costPricePerUnit)
    }
}