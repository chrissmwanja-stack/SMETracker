package com.vestateck.smetracker.data.dao

import androidx.room.*
import com.vestateck.smetracker.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SMEDao {
    // -- Sales -----------------------------------------------------
    @Query("SELECT * FROM sales WHERE isDeleted = 0 ORDER BY date DESC")
    fun getAllSales(): Flow<List<Sale>>

    @Query("SELECT * FROM sales WHERE id = :saleId")
    suspend fun getSaleById(saleId: String): Sale?

    @Query("SELECT SUM(amount) FROM sales WHERE isDeleted = 0")
    fun getTotalRevenue(): Flow<Double?>

    @Query("SELECT SUM(amount) FROM sales WHERE isDeleted = 0 AND date >= :startOfDay")
    fun getTodayRevenue(startOfDay: Long): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(sale: Sale): Long

    @Query(
        "UPDATE sales SET costPriceSnapshot = :costPriceSnapshot, profit = :profit, " +
                "financialsReconciled = 1 WHERE id = :saleId"
    )
    suspend fun updateSaleFinancials(saleId: String, costPriceSnapshot: Double, profit: Double)

    @Delete
    suspend fun deleteSale(sale: Sale)

    @Query("UPDATE sales SET isDeleted = 1, pendingSync = 1 WHERE id = :saleId")
    suspend fun markSaleAsDeleted(saleId: String)

    // -- Reconciliation (Sale) -------------------------------------
    @Query("SELECT * FROM sales WHERE isDeleted = 0 AND financialsReconciled = 0 ORDER BY date DESC")
    fun getUnreconciledSales(): Flow<List<Sale>>

    @Query("SELECT COUNT(*) FROM sales WHERE isDeleted = 0 AND financialsReconciled = 0")
    fun getUnreconciledSalesCount(): Flow<Long>

    @Query(
        "UPDATE sales SET costPriceSnapshot = :costPriceSnapshot, profit = :profit, " +
                "financialsReconciled = 1, pendingSync = 1 WHERE id = :saleId"
    )
    suspend fun reconcileSaleFinancials(saleId: String, costPriceSnapshot: Double, profit: Double)

    // -- Sync (Sale) -------------------------------------------------
    @Query("SELECT * FROM sales WHERE pendingSync = 1")
    suspend fun getPendingSyncSales(): List<Sale>

    @Query("UPDATE sales SET pendingSync = 0 WHERE id = :saleId")
    suspend fun clearSalePendingSync(saleId: String)

    @Query("DELETE FROM sales WHERE pendingSync = 0")
    suspend fun deleteSyncedSales()

    @Query("UPDATE sales SET finalReceiptNumber = :finalReceiptNumber WHERE id = :saleId")
    suspend fun markSaleReceiptFinalized(saleId: String, finalReceiptNumber: String)

    // -- Customers ---------------------------------------------------
    @Query("SELECT * FROM customers WHERE isDeleted = 0 ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<Customer>>

    @Query("SELECT * FROM customers WHERE isDeleted = 0 AND (name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%') ORDER BY name ASC")
    fun searchCustomers(query: String): Flow<List<Customer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: Customer): Long

    // Used by CustomerSync's listener to check for an unsynced local change
    // before applying an incoming remote write.
    @Query("SELECT * FROM customers WHERE id = :customerId LIMIT 1")
    suspend fun getCustomerById(customerId: String): Customer?

    @Update
    suspend fun updateCustomer(customer: Customer)

    @Delete
    suspend fun deleteCustomer(customer: Customer)

    @Query("UPDATE customers SET isDeleted = 1, pendingSync = 1 WHERE id = :customerId")
    suspend fun markCustomerAsDeleted(customerId: String)

    // -- Sync (Customer) ----------------------------------------------
    @Query("SELECT * FROM customers WHERE pendingSync = 1")
    suspend fun getPendingSyncCustomers(): List<Customer>

    @Query("UPDATE customers SET pendingSync = 0 WHERE id = :customerId")
    suspend fun clearCustomerPendingSync(customerId: String)

    @Query("DELETE FROM customers WHERE pendingSync = 0")
    suspend fun deleteSyncedCustomers()

    // -- Debts ---------------------------------------------------------
    @Query("SELECT * FROM debts WHERE isDeleted = 0 ORDER BY date DESC")
    fun getAllDebts(): Flow<List<Debt>>

    @Query("SELECT * FROM debts WHERE isDeleted = 0 AND isPaid = 0 ORDER BY dueDate ASC")
    fun getUnpaidDebts(): Flow<List<Debt>>

    @Query("SELECT SUM(amount) FROM debts WHERE isDeleted = 0 AND isPaid = 0")
    fun getTotalOutstandingDebt(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebt(debt: Debt): Long

    // Used by DebtSync's listener to check for an unsynced local change
    // before applying an incoming remote write.
    @Query("SELECT * FROM debts WHERE id = :debtId LIMIT 1")
    suspend fun getDebtById(debtId: String): Debt?

    @Query("UPDATE debts SET isPaid = 1, pendingSync = 1 WHERE id = :debtId")
    suspend fun markDebtAsPaid(debtId: String)

    @Delete
    suspend fun deleteDebt(debt: Debt)

    @Query("UPDATE debts SET isDeleted = 1, pendingSync = 1 WHERE id = :debtId")
    suspend fun markDebtAsDeleted(debtId: String)

    // -- Sync (Debt) ---------------------------------------------------
    @Query("SELECT * FROM debts WHERE pendingSync = 1")
    suspend fun getPendingSyncDebts(): List<Debt>

    @Query("UPDATE debts SET pendingSync = 0 WHERE id = :debtId")
    suspend fun clearDebtPendingSync(debtId: String)

    @Query("DELETE FROM debts WHERE pendingSync = 0")
    suspend fun deleteSyncedDebts()

    // -- Expenses --------------------------------------------------
    @Query("SELECT * FROM expenses WHERE isDeleted = 0 ORDER BY date DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE isDeleted = 0 AND id = :expenseId")
    suspend fun getExpenseById(expenseId: String): Expense?

    @Query("SELECT SUM(amount) FROM expenses WHERE isDeleted = 0")
    fun getTotalExpenses(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense): Long

    @Delete
    suspend fun deleteExpense(expense: Expense)

    @Query("UPDATE expenses SET isDeleted = 1, pendingSync = 1 WHERE id = :expenseId")
    suspend fun markExpenseAsDeleted(expenseId: String)

    // -- Sync (Expense) ----------------------------------------------
    @Query("SELECT * FROM expenses WHERE pendingSync = 1")
    suspend fun getPendingSyncExpenses(): List<Expense>

    @Query("UPDATE expenses SET pendingSync = 0 WHERE id = :expenseId")
    suspend fun clearExpensePendingSync(expenseId: String)

    @Query("DELETE FROM expenses WHERE pendingSync = 0")
    suspend fun deleteSyncedExpenses()

    @Query(
        "UPDATE expenses SET receiptUrl = :receiptUrl, receiptPendingUpload = 0 " +
                "WHERE id = :expenseId"
    )
    suspend fun markReceiptUploaded(expenseId: String, receiptUrl: String)

    // -- Tasks -----------------------------------------------------
    @Query("SELECT * FROM tasks WHERE isDeleted = 0 AND isCompleted = 0 ORDER BY dueDate ASC")
    fun getPendingTasks(): Flow<List<Task>>

    // Used by TaskSync's listener to check for an unsynced local change
    // before applying an incoming remote write, so a stale snapshot can't
    // clobber a completion/deletion that hasn't been pushed yet.
    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): Task?

    @Query("SELECT COUNT(*) FROM tasks WHERE isDeleted = 0 AND isCompleted = 0")
    fun getPendingTaskCount(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Query("UPDATE tasks SET isCompleted = 1, completedDate = :completedDate, pendingSync = 1 WHERE id = :taskId")
    suspend fun markTaskAsCompleted(taskId: String, completedDate: Long)

    @Delete
    suspend fun deleteTask(task: Task)

    @Query("UPDATE tasks SET isDeleted = 1, pendingSync = 1 WHERE id = :taskId")
    suspend fun markTaskAsDeleted(taskId: String)

    // -- Sync (Task) -------------------------------------------------
    @Query("SELECT * FROM tasks WHERE pendingSync = 1")
    suspend fun getPendingSyncTasks(): List<Task>

    @Query("UPDATE tasks SET pendingSync = 0 WHERE id = :taskId")
    suspend fun clearTaskPendingSync(taskId: String)

    @Query("DELETE FROM tasks WHERE pendingSync = 0")
    suspend fun deleteSyncedTasks()
}