package com.vestateck.smetracker.repository

import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.*
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

    // ── Reconciliation ───────────────────────────────────────────
    val unreconciledSales: Flow<List<Sale>> = smeDao.getUnreconciledSales()
    val unreconciledSalesCount: Flow<Long> = smeDao.getUnreconciledSalesCount()
    suspend fun reconcileSaleFinancials(saleId: String, costPriceSnapshot: Double, profit: Double) =
        smeDao.reconcileSaleFinancials(saleId, costPriceSnapshot, profit)

    val unreconciledInventoryItems: Flow<List<InventoryItem>> = inventoryDao.getUnreconciledItems()
    val unreconciledInventoryCount: Flow<Long> = inventoryDao.getUnreconciledItemsCount()
    suspend fun reconcileItemCost(itemId: String, costPrice: Double) =
        inventoryDao.reconcileItemCost(itemId, costPrice)

    // ── Customers ─────────────────────────────────────────────────
    val allCustomers: Flow<List<Customer>> = smeDao.getAllCustomers()

    fun searchCustomers(query: String): Flow<List<Customer>> = smeDao.searchCustomers(query)
    suspend fun insertCustomer(customer: Customer) = smeDao.insertCustomer(customer)
    suspend fun updateCustomer(customer: Customer) = smeDao.updateCustomer(customer)
    suspend fun deleteCustomer(customer: Customer) = smeDao.deleteCustomer(customer)

    // ── Debts ────────────────────────────────────────────────────
    val allDebts: Flow<List<Debt>> = smeDao.getAllDebts()
    val unpaidDebts: Flow<List<Debt>> = smeDao.getUnpaidDebts()
    val totalOutstandingDebt: Flow<Double?> = smeDao.getTotalOutstandingDebt()

    suspend fun insertDebt(debt: Debt) = smeDao.insertDebt(debt)
    suspend fun updateDebt(debt: Debt) = smeDao.insertDebt(debt)
    suspend fun deleteDebt(debt: Debt) = smeDao.deleteDebt(debt)
    suspend fun markDebtPaid(debtId: String) = smeDao.markDebtAsPaid(debtId)

    // ── Inventory ────────────────────────────────────────────────
    val allInventoryItems: Flow<List<InventoryItem>> = inventoryDao.getAllItems()
    val lowStockItems: Flow<List<InventoryItem>> = inventoryDao.getLowStockItems(5)
    val totalStockValue: Flow<Double> = inventoryDao.getTotalStockValue()

    fun getLowStockCount(threshold: Int = 5): Flow<Long> = inventoryDao.getLowStockCount(threshold)
    fun getTotalItemCount(): Flow<Long> = inventoryDao.getTotalItemCount()

    suspend fun insertInventoryItem(item: InventoryItem) = inventoryDao.insert(item)
    suspend fun updateInventoryItem(item: InventoryItem) = inventoryDao.update(item)
    suspend fun deleteInventoryItem(item: InventoryItem) = inventoryDao.delete(item)
    suspend fun adjustStock(itemId: String, amount: Int) = inventoryDao.adjustStock(itemId, amount, System.currentTimeMillis())

    fun getAdjustmentsForItem(itemId: String) = inventoryDao.getAdjustmentsForItem(itemId)

    suspend fun receiveStock(itemId: String, quantity: Int, note: String? = null) {
        inventoryDao.applyStockAdjustment(
            StockAdjustment(
                itemId = itemId,
                delta = quantity,
                reason = StockAdjustmentReason.INCOMING,
                note = note
            )
        )
    }

    suspend fun recountStock(itemId: String, newQuantity: Int, note: String) {
        val currentItem = inventoryDao.getItemById(itemId) ?: return
        val delta = newQuantity - currentItem.quantity
        if (delta == 0) return

        inventoryDao.applyStockAdjustment(
            StockAdjustment(
                itemId = itemId,
                delta = delta,
                reason = StockAdjustmentReason.RECOUNT,
                note = note
            )
        )
    }

    suspend fun recordSaleStockAdjustment(itemId: String, quantity: Int) {
        inventoryDao.applyStockAdjustment(
            StockAdjustment(
                itemId = itemId,
                delta = -quantity,
                reason = StockAdjustmentReason.SALE,
                note = "Sale"
            )
        )
    }

    // ── Expenses ─────────────────────────────────────────────────
    fun getAllExpenses(): Flow<List<Expense>> = smeDao.getAllExpenses()
    fun getTotalExpenses(): Flow<Double?> = smeDao.getTotalExpenses()
    suspend fun addExpense(expense: Expense) = smeDao.insertExpense(expense)
    suspend fun deleteExpense(expense: Expense) = smeDao.deleteExpense(expense)

    // ── Tasks ────────────────────────────────────────────────────
    fun getPendingTasks(): Flow<List<Task>> = smeDao.getPendingTasks()
    fun getPendingTaskCount(): Flow<Long> = smeDao.getPendingTaskCount()
    suspend fun addTask(task: Task) = smeDao.insertTask(task)
    suspend fun completeTask(taskId: String) = smeDao.markTaskAsCompleted(taskId, System.currentTimeMillis())
    suspend fun deleteTask(task: Task) = smeDao.deleteTask(task)

    private fun getStartOfDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}