package com.vestateck.smetracker.fakes

import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.entities.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Hand-rolled in-memory fake of SMEDao for unit tests - there's no mocking
 * library in this project's test dependencies (see build.gradle.kts), and
 * SMEDao is a plain interface, so a fake is simpler than adding one just for
 * this. Backed by MutableStateFlow per table so anything observing e.g.
 * getUnreconciledSales() sees writes immediately, same as Room would emit
 * them (minus the actual SQL).
 *
 * Only implements the querying/mutation semantics that SMERepository and
 * SMEViewModel actually rely on (e.g. reconcileSaleFinancials setting
 * financialsReconciled = true) - not a full SQL reimplementation.
 */
class FakeSMEDao : SMEDao {

    val salesFlow = MutableStateFlow<List<Sale>>(emptyList())
    val customersFlow = MutableStateFlow<List<Customer>>(emptyList())
    val debtsFlow = MutableStateFlow<List<Debt>>(emptyList())
    val expensesFlow = MutableStateFlow<List<Expense>>(emptyList())
    val tasksFlow = MutableStateFlow<List<Task>>(emptyList())

    // -- Sales -----------------------------------------------------
    override fun getAllSales(): Flow<List<Sale>> = salesFlow.map { list -> list.filter { !it.isDeleted } }
    override suspend fun getSaleById(saleId: String): Sale? = salesFlow.value.find { it.id == saleId }
    override fun getTotalRevenue(): Flow<Double?> = salesFlow.map { list -> list.filter { !it.isDeleted }.sumOf { it.amount } }
    override fun getTodayRevenue(startOfDay: Long): Flow<Double?> =
        salesFlow.map { list -> list.filter { !it.isDeleted && it.date >= startOfDay }.sumOf { it.amount } }

    override suspend fun insertSale(sale: Sale): Long {
        salesFlow.update { list -> list.filterNot { it.id == sale.id } + sale }
        return 0L
    }

    override suspend fun updateSaleFinancials(saleId: String, costPriceSnapshot: Double, profit: Double) {
        salesFlow.update { list ->
            list.map { if (it.id == saleId) it.copy(costPriceSnapshot = costPriceSnapshot, profit = profit) else it }
        }
    }

    override suspend fun deleteSale(sale: Sale) {
        salesFlow.update { list -> list.filterNot { it.id == sale.id } }
    }

    override suspend fun markSaleAsDeleted(saleId: String) {
        salesFlow.update { list ->
            list.map { if (it.id == saleId) it.copy(isDeleted = true, pendingSync = true) else it }
        }
    }

    // -- Reconciliation (Sale) -------------------------------------
    override fun getUnreconciledSales(): Flow<List<Sale>> =
        salesFlow.map { list -> list.filter { !it.isDeleted && !it.financialsReconciled } }

    override fun getUnreconciledSalesCount(): Flow<Long> =
        salesFlow.map { list -> list.count { !it.isDeleted && !it.financialsReconciled }.toLong() }

    override suspend fun reconcileSaleFinancials(saleId: String, costPriceSnapshot: Double, profit: Double) {
        salesFlow.update { list ->
            list.map {
                if (it.id == saleId) {
                    it.copy(
                        costPriceSnapshot = costPriceSnapshot,
                        profit = profit,
                        financialsReconciled = true,
                        pendingSync = true
                    )
                } else it
            }
        }
    }

    // -- Sync (Sale) -------------------------------------------------
    override suspend fun getPendingSyncSales(): List<Sale> = salesFlow.value.filter { it.pendingSync }
    override suspend fun clearSalePendingSync(saleId: String) {
        salesFlow.update { list -> list.map { if (it.id == saleId) it.copy(pendingSync = false) else it } }
    }

    override suspend fun markSaleReceiptFinalized(saleId: String, finalReceiptNumber: String) {
        salesFlow.update { list -> list.map { if (it.id == saleId) it.copy(finalReceiptNumber = finalReceiptNumber) else it } }
    }

    override suspend fun deleteSyncedSales() {
        salesFlow.update { list -> list.filter { it.pendingSync } }
    }

    // -- Customers ---------------------------------------------------
    override fun getAllCustomers(): Flow<List<Customer>> = customersFlow.map { list -> list.filter { !it.isDeleted } }
    override fun searchCustomers(query: String): Flow<List<Customer>> =
        customersFlow.map { list -> list.filter { !it.isDeleted && (it.name.contains(query, ignoreCase = true) || it.phone.contains(query)) } }

    override suspend fun insertCustomer(customer: Customer): Long {
        customersFlow.update { list -> list.filterNot { it.id == customer.id } + customer }
        return 0L
    }

    override suspend fun updateCustomer(customer: Customer) {
        customersFlow.update { list -> list.map { if (it.id == customer.id) customer else it } }
    }

    override suspend fun deleteCustomer(customer: Customer) {
        customersFlow.update { list -> list.filterNot { it.id == customer.id } }
    }

    override suspend fun markCustomerAsDeleted(customerId: String) {
        customersFlow.update { list ->
            list.map { if (it.id == customerId) it.copy(isDeleted = true, pendingSync = true) else it }
        }
    }

    override suspend fun getPendingSyncCustomers(): List<Customer> = customersFlow.value.filter { it.pendingSync }
    override suspend fun clearCustomerPendingSync(customerId: String) {
        customersFlow.update { list -> list.map { if (it.id == customerId) it.copy(pendingSync = false) else it } }
    }

    override suspend fun deleteSyncedCustomers() {
        customersFlow.update { list -> list.filter { it.pendingSync } }
    }

    // -- Debts ---------------------------------------------------------
    override fun getAllDebts(): Flow<List<Debt>> = debtsFlow.map { list -> list.filter { !it.isDeleted } }
    override fun getUnpaidDebts(): Flow<List<Debt>> = debtsFlow.map { list -> list.filter { !it.isDeleted && !it.isPaid } }
    override fun getTotalOutstandingDebt(): Flow<Double?> =
        debtsFlow.map { list -> list.filter { !it.isDeleted && !it.isPaid }.sumOf { it.amount } }

    override suspend fun insertDebt(debt: Debt): Long {
        debtsFlow.update { list -> list.filterNot { it.id == debt.id } + debt }
        return 0L
    }

    override suspend fun markDebtAsPaid(debtId: String) {
        debtsFlow.update { list -> list.map { if (it.id == debtId) it.copy(isPaid = true, pendingSync = true) else it } }
    }

    override suspend fun deleteDebt(debt: Debt) {
        debtsFlow.update { list -> list.filterNot { it.id == debt.id } }
    }

    override suspend fun markDebtAsDeleted(debtId: String) {
        debtsFlow.update { list ->
            list.map { if (it.id == debtId) it.copy(isDeleted = true, pendingSync = true) else it }
        }
    }

    override suspend fun getPendingSyncDebts(): List<Debt> = debtsFlow.value.filter { it.pendingSync }
    override suspend fun clearDebtPendingSync(debtId: String) {
        debtsFlow.update { list -> list.map { if (it.id == debtId) it.copy(pendingSync = false) else it } }
    }

    override suspend fun deleteSyncedDebts() {
        debtsFlow.update { list -> list.filter { it.pendingSync } }
    }

    // -- Expenses --------------------------------------------------
    override fun getAllExpenses(): Flow<List<Expense>> = expensesFlow.map { list -> list.filter { !it.isDeleted } }
    override suspend fun getExpenseById(expenseId: String): Expense? = expensesFlow.value.find { it.id == expenseId && !it.isDeleted }
    override fun getTotalExpenses(): Flow<Double?> = expensesFlow.map { list -> list.filter { !it.isDeleted }.sumOf { it.amount } }

    override suspend fun insertExpense(expense: Expense): Long {
        expensesFlow.update { list -> list.filterNot { it.id == expense.id } + expense }
        return 0L
    }

    override suspend fun deleteExpense(expense: Expense) {
        expensesFlow.update { list -> list.filterNot { it.id == expense.id } }
    }

    override suspend fun markExpenseAsDeleted(expenseId: String) {
        expensesFlow.update { list ->
            list.map { if (it.id == expenseId) it.copy(isDeleted = true, pendingSync = true) else it }
        }
    }

    override suspend fun getPendingSyncExpenses(): List<Expense> = expensesFlow.value.filter { it.pendingSync }
    override suspend fun clearExpensePendingSync(expenseId: String) {
        expensesFlow.update { list -> list.map { if (it.id == expenseId) it.copy(pendingSync = false) else it } }
    }

    override suspend fun markReceiptUploaded(expenseId: String, receiptUrl: String) {
        expensesFlow.update { list ->
            list.map {
                if (it.id == expenseId) it.copy(receiptUrl = receiptUrl, receiptPendingUpload = false)
                else it
            }
        }
    }

    override suspend fun deleteSyncedExpenses() {
        expensesFlow.update { list -> list.filter { it.pendingSync } }
    }

    // -- Tasks -----------------------------------------------------
    override fun getPendingTasks(): Flow<List<Task>> = tasksFlow.map { list -> list.filter { !it.isDeleted && !it.isCompleted } }
    override fun getPendingTaskCount(): Flow<Long> = tasksFlow.map { list -> list.count { !it.isDeleted && !it.isCompleted }.toLong() }

    override suspend fun insertTask(task: Task): Long {
        tasksFlow.update { list -> list.filterNot { it.id == task.id } + task }
        return 0L
    }

    override suspend fun markTaskAsCompleted(taskId: String, completedDate: Long) {
        tasksFlow.update { list ->
            list.map { if (it.id == taskId) it.copy(isCompleted = true, completedDate = completedDate, pendingSync = true) else it }
        }
    }

    override suspend fun deleteTask(task: Task) {
        tasksFlow.update { list -> list.filterNot { it.id == task.id } }
    }

    override suspend fun markTaskAsDeleted(taskId: String) {
        tasksFlow.update { list ->
            list.map { if (it.id == taskId) it.copy(isDeleted = true, pendingSync = true) else it }
        }
    }

    override suspend fun getPendingSyncTasks(): List<Task> = tasksFlow.value.filter { it.pendingSync }
    override suspend fun clearTaskPendingSync(taskId: String) {
        tasksFlow.update { list -> list.map { if (it.id == taskId) it.copy(pendingSync = false) else it } }
    }

    override suspend fun deleteSyncedTasks() {
        tasksFlow.update { list -> list.filter { it.pendingSync } }
    }
}