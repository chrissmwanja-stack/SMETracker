// viewmodel/SMEViewModel.kt
package com.example.smetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smetracker.data.DashboardAnalytics
import com.example.smetracker.data.DashboardUiState
import com.example.smetracker.data.entities.*
import com.example.smetracker.repository.SMERepository
import com.example.smetracker.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SMEViewModel(private val repository: SMERepository) : ViewModel() {

    val sales: StateFlow<List<Sale>> = repository.allSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customers: StateFlow<List<Customer>> = repository.allCustomers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debts: StateFlow<List<Debt>> = repository.allDebts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventoryItems: StateFlow<List<InventoryItem>> = repository.allInventoryItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<DashboardUiState> = combine(
        sales, customers, debts, inventoryItems
    ) { sales, customers, debts, inventory ->
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
            analytics = DashboardAnalytics.from(sales, debts, inventory)
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
    }

    fun addCustomer(name: String, phone: String = "", email: String = "") = viewModelScope.launch {
        repository.insertCustomer(Customer(name = name, phone = phone, email = email))
    }

    fun deleteCustomer(customer: Customer) = viewModelScope.launch {
        repository.deleteCustomer(customer)
    }

    // Debt Actions
    fun insertDebt(debt: Debt) = viewModelScope.launch {
        repository.insertDebt(debt)
    }

    fun markDebtAsPaid(debtId: Long) = viewModelScope.launch {
        repository.markDebtPaid(debtId)
    }

    // Inventory Actions
    fun upsertInventoryItem(item: InventoryItem) = viewModelScope.launch {
        if (item.id == 0L) {
            repository.insertInventoryItem(item)
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

    fun adjustStock(itemId: Long, amount: Int) = viewModelScope.launch {
        repository.adjustStock(itemId, amount)
    }

    // Sale Actions
    fun addSale(customerName: String, description: String, amount: Double, paymentMethod: PaymentMethod) = viewModelScope.launch {
        repository.insertSale(
            Sale(
                customerName = customerName,
                description = description,
                amount = amount,
                paymentMethod = paymentMethod,
                date = System.currentTimeMillis()
            )
        )
    }
    
    fun deleteSale(sale: Sale) = viewModelScope.launch {
        repository.deleteSale(sale)
    }
}
