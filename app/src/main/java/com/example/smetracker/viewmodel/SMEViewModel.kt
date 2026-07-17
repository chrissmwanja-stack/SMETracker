// viewmodel/SMEViewModel.kt
package com.example.smetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smetracker.data.DashboardAnalytics
import com.example.smetracker.data.DashboardUiState
import com.example.smetracker.data.entities.*
import com.example.smetracker.data.remote.auth.SessionManager
import com.example.smetracker.data.remote.sync.SyncEngine
import com.example.smetracker.repository.SMERepository
import com.example.smetracker.utils.IdGenerator
import com.example.smetracker.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SMEViewModel(
    private val repository: SMERepository,
    // Nullable so tests/previews that don't need sync can keep constructing
    // this ViewModel with just a repository. Every mutation below calls
    // syncEngine?.requestPush() after its local write — deletions are the
    // one exception, since SyncEngine doesn't sync deletes in either
    // direction yet (a known limitation, not an oversight here).
    private val syncEngine: SyncEngine? = null,
    // Nullable for the same reason as syncEngine. Used only to stamp
    // recordedBy and the reconciliation flags at creation time (see
    // addSale/addInventoryItem/upsertInventoryItem) — when null, new sales
    // and inventory items are treated as owner-recorded/already-reconciled,
    // matching this ViewModel's pre-reconciliation behavior.
    private val sessionManager: SessionManager? = null
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

    // ── Reconciliation (owner-only; screen is responsible for gating) ──
    val unreconciledSales: StateFlow<List<Sale>> = repository.unreconciledSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreconciledInventoryItems: StateFlow<List<InventoryItem>> = repository.unreconciledInventoryItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreconciledCount: StateFlow<Int> = combine(
        repository.unreconciledSalesCount, repository.unreconciledInventoryCount
    ) { sales, items -> (sales + items).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
            analytics = DashboardAnalytics.from(sales, debts, inventory, expenseList)
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DashboardUiState()
        )

    // Customer Actions
    fun insertCustomer(customer: Customer) = viewModelScope.launch {
        repository.insertCustomer(customer)
        syncEngine?.requestPush()
    }

    fun addCustomer(name: String, phone: String = "", email: String = "") = viewModelScope.launch {
        repository.insertCustomer(Customer(name = name, phone = phone, email = email))
        syncEngine?.requestPush()
    }

    // Callers that already have a persisted Customer (blank id would mean "not yet
    // saved") go to update; a blank id means the id needs generating on first insert.
    fun upsertCustomer(customer: Customer) = viewModelScope.launch {
        if (customer.id.isBlank()) {
            repository.insertCustomer(customer.copy(id = IdGenerator.newId()))
        } else {
            repository.updateCustomer(customer)
        }
        syncEngine?.requestPush()
    }

    fun deleteCustomer(customer: Customer) = viewModelScope.launch {
        repository.deleteCustomer(customer)
    }

    // Debt Actions
    fun insertDebt(debt: Debt) = viewModelScope.launch {
        repository.insertDebt(debt)
        syncEngine?.requestPush()
    }

    fun markDebtAsPaid(debtId: String) = viewModelScope.launch {
        repository.markDebtPaid(debtId)
        syncEngine?.requestPush()
    }

    // Inventory Actions
    // InventoryItemDialog is used for both Add and Edit; it passes a blank id
    // for a brand-new item (mirroring the old id == 0L convention), so a fresh
    // id is only generated here, at the moment we know it's really an insert.
    fun upsertInventoryItem(item: InventoryItem) = viewModelScope.launch {
        if (item.id.isBlank()) {
            val (myPhone, isOwner) = currentSession()
            // A worker's Add dialog never shows a cost field (see
            // InventoryItemDialog), so costPrice here is always the unset 0.0
            // default for a worker — that's exactly the case that needs an
            // owner's review. An owner creating the item already entered a
            // real cost, so it's reconciled immediately.
            repository.insertInventoryItem(
                item.copy(id = IdGenerator.newId(), recordedBy = myPhone, costReconciled = isOwner)
            )
        } else {
            // Editing an existing item always goes through the owner-only
            // cost field when isOwner (see InventoryItemDialog) — if this
            // save came from an owner, treat it as having reviewed the cost.
            val (_, isOwner) = currentSession()
            repository.updateInventoryItem(if (isOwner) item.copy(costReconciled = true) else item)
        }
        syncEngine?.requestPush()
    }

    fun addInventoryItem(name: String, quantity: Int, sellingPrice: Double, category: String = "", costPrice: Double = 0.0, reorderLevel: Int = 5) = viewModelScope.launch {
        val (myPhone, isOwner) = currentSession()
        repository.insertInventoryItem(
            InventoryItem(
                name = name,
                quantity = quantity,
                sellingPrice = sellingPrice,
                category = category,
                costPrice = costPrice,
                reorderLevel = reorderLevel,
                recordedBy = myPhone,
                costReconciled = isOwner
            )
        )
        syncEngine?.requestPush()
    }

    fun deleteInventoryItem(item: InventoryItem) = viewModelScope.launch {
        repository.deleteInventoryItem(item)
    }

    fun getAdjustmentsForItem(itemId: String) = repository.getAdjustmentsForItem(itemId)

    // Incoming Stock — additive-only, available to workers and owners alike.
    fun receiveStock(itemId: String, quantity: Int, note: String? = null) = viewModelScope.launch {
        repository.receiveStock(itemId, quantity, note)
        syncEngine?.requestPush()
    }

    // Recount — owner-only correction after a physical count. The screen is
    // responsible for only exposing this to an owner and for requiring a note;
    // this function trusts its caller on both, same as the rest of this class.
    fun recountStock(itemId: String, newQuantity: Int, note: String) = viewModelScope.launch {
        repository.recountStock(itemId, newQuantity, note)
        syncEngine?.requestPush()
    }

    // Sale Actions
    // For a custom/service sale not tied to tracked inventory (inventoryItemId = null), profit stays 0
    // since there's no known cost basis. Pass an inventoryItemId to compute real profit and decrement stock.
    fun addSale(
        customerName: String,
        description: String,
        amount: Double,
        paymentMethod: PaymentMethod,
        inventoryItemId: String? = null,
        quantity: Int = 1
    ) = viewModelScope.launch {
        val soldItem = inventoryItemId?.let { id -> inventoryItems.value.find { it.id == id } }
        val profit = soldItem?.let { (it.sellingPrice - it.costPrice) * quantity } ?: 0.0
        val (myPhone, isOwner) = currentSession()
        // A custom/service sale (no linked item) has no cost basis to review,
        // so it's reconciled by definition. A sale tied to a tracked item is
        // only trustworthy if an owner's device computed the profit — a
        // worker's device never has real cost data (see InventoryItemDialog),
        // so its profit here is always 0 and needs an owner's review.
        val financialsReconciled = inventoryItemId == null || isOwner

        repository.insertSale(
            Sale(
                customerName = customerName,
                description = description,
                amount = amount,
                profit = profit,
                inventoryItemId = inventoryItemId,
                quantity = quantity,
                paymentMethod = paymentMethod,
                date = System.currentTimeMillis(),
                recordedBy = myPhone,
                financialsReconciled = financialsReconciled
            )
        )

        if (soldItem != null) {
            repository.recordSaleStockAdjustment(soldItem.id, quantity)
        }
        syncEngine?.requestPush()
    }

    fun deleteSale(sale: Sale) = viewModelScope.launch {
        repository.deleteSale(sale)
    }

    // Expense Actions
    fun addExpense(description: String, amount: Double, category: String = "General", receiptNumber: String? = null) = viewModelScope.launch {
        repository.addExpense(
            Expense(
                description = description,
                amount = amount,
                category = category,
                receiptNumber = receiptNumber
            )
        )
        syncEngine?.requestPush()
    }

    fun deleteExpense(expense: Expense) = viewModelScope.launch {
        repository.deleteExpense(expense)
    }

    // Task Actions
    fun addTask(title: String, description: String? = null, priority: String = "Medium", dueDate: Long? = null) = viewModelScope.launch {
        repository.addTask(
            Task(
                title = title,
                description = description,
                priority = priority,
                dueDate = dueDate
            )
        )
        syncEngine?.requestPush()
    }

    fun completeTask(taskId: String) = viewModelScope.launch {
        repository.completeTask(taskId)
        syncEngine?.requestPush()
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        repository.deleteTask(task)
    }

    // Reconciliation Actions (owner-only; screen is responsible for gating)
    // costPricePerUnit is what the owner enters (matches how InventoryItem.
    // costPrice is entered everywhere else). Sale.costPriceSnapshot is
    // documented as the TOTAL cost basis (costPrice * quantity), and
    // Sale.amount is the total the customer paid for the whole line — so
    // profit is amount minus total cost, not a per-unit difference.
    fun reconcileSale(saleId: String, costPricePerUnit: Double) = viewModelScope.launch {
        val sale = unreconciledSales.value.find { it.id == saleId } ?: return@launch
        val totalCost = costPricePerUnit * sale.quantity
        val profit = sale.amount - totalCost
        repository.reconcileSaleFinancials(saleId, totalCost, profit)
        syncEngine?.requestPush()
    }

    fun reconcileInventoryCost(itemId: String, costPrice: Double) = viewModelScope.launch {
        repository.reconcileItemCost(itemId, costPrice)
        syncEngine?.requestPush()
    }
}