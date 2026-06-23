package com.example.smetracker.repository

import com.example.smetracker.data.dao.InventoryDao
import com.example.smetracker.data.dao.SMEDao
import com.example.smetracker.data.entities.*
import kotlinx.coroutines.flow.Flow
import java.util.*

class SMERepository(
    private val smeDao: SMEDao,
    private val inventoryDao: InventoryDao
) {
    // ── Sales ─────────────────────────────────────────────────────
    val allSales: Flow<List<Sale>> = smeDao.getAllSales()
    val totalRevenue: Flow<Double?> = smeDao.getTotalRevenue()
    
    val todayRevenue: Flow<Double?> 
        get() = smeDao.getTodayRevenue(getStartOfDay())

    fun getTodayRevenue(startOfDay: Long): Flow<Double?> = smeDao.getTodayRevenue(startOfDay)
    
    suspend fun insertSale(sale: Sale) = smeDao.insertSale(sale)
    suspend fun updateSale(sale: Sale) = smeDao.insertSale(sale)
    suspend fun deleteSale(sale: Sale) = smeDao.deleteSale(sale)

    // ── Customers ─────────────────────────────────────────────────
    val allCustomers: Flow<List<Customer>> = smeDao.getAllCustomers()
    
    fun searchCustomers(query: String): Flow<List<Customer>> = smeDao.searchCustomers(query)
    suspend fun insertCustomer(customer: Customer) = smeDao.insertCustomer(customer)
    suspend fun updateCustomer(customer: Customer) = smeDao.insertCustomer(customer)
    suspend fun deleteCustomer(customer: Customer) = smeDao.deleteCustomer(customer)

    // ── Debts ────────────────────────────────────────────────────
    val allDebts: Flow<List<Debt>> = smeDao.getAllDebts()
    val unpaidDebts: Flow<List<Debt>> = smeDao.getUnpaidDebts()
    val totalOutstandingDebt: Flow<Double?> = smeDao.getTotalOutstandingDebt()

    suspend fun insertDebt(debt: Debt) = smeDao.insertDebt(debt)
    suspend fun updateDebt(debt: Debt) = smeDao.insertDebt(debt)
    suspend fun deleteDebt(debt: Debt) = smeDao.deleteDebt(debt)
    suspend fun markDebtPaid(debtId: Long) = smeDao.markDebtAsPaid(debtId)

    // ── Inventory ────────────────────────────────────────────────
    val allInventoryItems: Flow<List<InventoryItem>> = inventoryDao.getAllItems()
    val lowStockItems: Flow<List<InventoryItem>> = inventoryDao.getLowStockItems(5)
    val totalStockValue: Flow<Double> = inventoryDao.getTotalStockValue()

    fun getLowStockCount(threshold: Int = 5): Flow<Long> = inventoryDao.getLowStockCount(threshold)
    fun getTotalItemCount(): Flow<Long> = inventoryDao.getTotalItemCount()
    
    suspend fun insertInventoryItem(item: InventoryItem) = inventoryDao.insert(item)
    suspend fun updateInventoryItem(item: InventoryItem) = inventoryDao.update(item)
    suspend fun deleteInventoryItem(item: InventoryItem) = inventoryDao.delete(item)
    suspend fun adjustStock(itemId: Long, amount: Int) = inventoryDao.adjustStock(itemId, amount, System.currentTimeMillis())

    // ── Expenses ─────────────────────────────────────────────────
    fun getAllExpenses(): Flow<List<Expense>> = smeDao.getAllExpenses()
    fun getTotalExpenses(): Flow<Double?> = smeDao.getTotalExpenses()
    suspend fun addExpense(expense: Expense) = smeDao.insertExpense(expense)

    // ── Tasks ────────────────────────────────────────────────────
    fun getPendingTasks(): Flow<List<Task>> = smeDao.getPendingTasks()
    fun getPendingTaskCount(): Flow<Long> = smeDao.getPendingTaskCount()
    suspend fun addTask(task: Task) = smeDao.insertTask(task)
    suspend fun completeTask(taskId: Long) = smeDao.markTaskAsCompleted(taskId, System.currentTimeMillis())

    private fun getStartOfDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
