// viewmodel/SMEViewModel.kt
package com.example.smetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smetracker.data.DashboardAnalytics
import com.example.smetracker.data.DashboardUiState
import com.example.smetracker.data.entities.*
import com.example.smetracker.data.remote.sync.SyncEngine
import com.example.smetracker.repository.SMERepository
import com.example.smetracker.utils.IdGenerator
import com.example.smetracker.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SMEViewModel(
    private val repository: SMERepository,
    // Nullable for now — only Customer syncs in this Phase 3 proof, and tests /
    // previews that don't need sync can keep constructing this ViewModel with
    // just a repository.
    private val syncEngine: SyncEngine? = null
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
    }

    fun markDebtAsPaid(debtId: String) = viewModelScope.launch {
        repository.markDebtPaid(debtId)
    }

    // Inventory Actions
    // InventoryItemDialog is used for both Add and Edit; it passes a blank id
    // for a brand-new item (mirroring the old id == 0L convention), so a fresh
    // id is only generated here, at the moment we know it's really an insert.
    fun upsertInventoryItem(item: InventoryItem) = viewModelScope.launch {
        if (item.id.isBlank()) {
            repository.insertInventoryItem(item.copy(id = IdGenerator.newId()))
        } else {
            repository.updateInventoryItem(item)
        }
    }

    fun addInventoryItem(name: String, quantity: Int, sellingPrice: Double, category: String = "", costPrice: Double = 0.0, reorderLevel: Int = 5) = viewModelScope.launch {
        repository.insertInventoryItem(
            InventoryItem(
                name = name,
                quantity = quantity,
                sellingPrice = sellingPrice,
                category = category,
                costPrice = costPrice,
                reorderLevel = reorderLevel
            )
        )
    }

    fun deleteInventoryItem(item: InventoryItem) = viewModelScope.launch {
        repository.deleteInventoryItem(item)
    }

    fun adjustStock(itemId: String, amount: Int) = viewModelScope.launch {
        repository.adjustStock(itemId, amount)
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

        repository.insertSale(
            Sale(
                customerName = customerName,
                description = description,
                amount = amount,
                profit = profit,
                inventoryItemId = inventoryItemId,
                quantity = quantity,
                paymentMethod = paymentMethod,
                date = System.currentTimeMillis()
            )
        )

        if (soldItem != null) {
            repository.adjustStock(soldItem.id, -quantity)
        }
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
    }

    fun completeTask(taskId: String) = viewModelScope.launch {
        repository.completeTask(taskId)
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        repository.deleteTask(task)
    }
}