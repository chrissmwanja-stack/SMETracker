package com.vestateck.smetracker.data.dao

import androidx.room.*
import com.vestateck.smetracker.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SMEDao {
    // -- Sales -----------------------------------------------------
    @Query("SELECT * FROM sales ORDER BY date DESC")
    fun getAllSales(): Flow<List<Sale>>

    @Query("SELECT * FROM sales WHERE id = :saleId")
    suspend fun getSaleById(saleId: String): Sale?

    @Query("SELECT SUM(amount) FROM sales")
    fun getTotalRevenue(): Flow<Double?>

    @Query("SELECT SUM(amount) FROM sales WHERE date >= :startOfDay")
    fun getTodayRevenue(startOfDay: Long): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(sale: Sale): Long

    // Sync pull, financials half only: costPriceSnapshot/profit come from a
    // SEPARATE Firestore listener (saleFinancials) than the core sale fields
    // (sales), which can arrive in either order. This patches just those two
    // columns (plus financialsReconciled - see below) so it never clobbers
    // whatever the sales listener already wrote (or will write) for the
    // rest of the row.
    //
    // financialsReconciled = 1 here is required, not cosmetic: a
    // saleFinancials document only ever exists in Firestore because an
    // owner already reconciled it (see SaleSync class doc - it's
    // owner-write-only and only ever written from reconcileSaleFinancials).
    // Without setting the flag here, a device that pulls this sale fresh
    // (e.g. after logout/login, or a first pull on another device) derives
    // financialsReconciled independently from its own local copy of the
    // linked inventory item's cost (see SaleSync.attachSaleListener) and
    // can land on false even though the real financials already arrived -
    // which put the sale right back in the Reconciliation queue for an
    // owner to redo work that was already done.
    @Query(
        "UPDATE sales SET costPriceSnapshot = :costPriceSnapshot, profit = :profit, " +
                "financialsReconciled = 1 WHERE id = :saleId"
    )
    suspend fun updateSaleFinancials(saleId: String, costPriceSnapshot: Double, profit: Double)

    @Delete
    suspend fun deleteSale(sale: Sale)

    // -- Reconciliation (Sale) -------------------------------------
    // Owner-only queries backing the Reconciliation screen - surfaces sales
    // (almost always worker-recorded, tied to a tracked inventory item)
    // whose costPriceSnapshot/profit an owner hasn't reviewed yet.
    @Query("SELECT * FROM sales WHERE financialsReconciled = 0 ORDER BY date DESC")
    fun getUnreconciledSales(): Flow<List<Sale>>

    @Query("SELECT COUNT(*) FROM sales WHERE financialsReconciled = 0")
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

    // Sign-out clear: deletes only the already-synced cache, leaves any
    // pendingSync = 1 rows in place so they aren't lost before they can
    // sync. See SMEDatabase.clearSyncedDataSuspending().
    @Query("DELETE FROM sales WHERE pendingSync = 0")
    suspend fun deleteSyncedSales()

    // Called by SaleSync.pushPending once a sale has successfully claimed
    // its authoritative number from businesses/{businessId}/counters/
    // receiptSequence via a Firestore transaction.
    @Query("UPDATE sales SET finalReceiptNumber = :finalReceiptNumber WHERE id = :saleId")
    suspend fun markSaleReceiptFinalized(saleId: String, finalReceiptNumber: String)

    // -- Customers ---------------------------------------------------
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<Customer>>

    @Query("SELECT * FROM customers WHERE name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchCustomers(query: String): Flow<List<Customer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: Customer): Long

    @Update
    suspend fun updateCustomer(customer: Customer)

    @Delete
    suspend fun deleteCustomer(customer: Customer)

    // -- Sync (Customer, Phase 3 proof) ------------------------------
    @Query("SELECT * FROM customers WHERE pendingSync = 1")
    suspend fun getPendingSyncCustomers(): List<Customer>

    @Query("UPDATE customers SET pendingSync = 0 WHERE id = :customerId")
    suspend fun clearCustomerPendingSync(customerId: String)

    @Query("DELETE FROM customers WHERE pendingSync = 0")
    suspend fun deleteSyncedCustomers()

    // -- Debts ---------------------------------------------------------
    @Query("SELECT * FROM debts ORDER BY date DESC")
    fun getAllDebts(): Flow<List<Debt>>

    @Query("SELECT * FROM debts WHERE isPaid = 0 ORDER BY dueDate ASC")
    fun getUnpaidDebts(): Flow<List<Debt>>

    @Query("SELECT SUM(amount) FROM debts WHERE isPaid = 0")
    fun getTotalOutstandingDebt(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebt(debt: Debt): Long

    @Query("UPDATE debts SET isPaid = 1, pendingSync = 1 WHERE id = :debtId")
    suspend fun markDebtAsPaid(debtId: String)

    @Delete
    suspend fun deleteDebt(debt: Debt)

    // -- Sync (Debt) ---------------------------------------------------
    @Query("SELECT * FROM debts WHERE pendingSync = 1")
    suspend fun getPendingSyncDebts(): List<Debt>

    @Query("UPDATE debts SET pendingSync = 0 WHERE id = :debtId")
    suspend fun clearDebtPendingSync(debtId: String)

    @Query("DELETE FROM debts WHERE pendingSync = 0")
    suspend fun deleteSyncedDebts()

    // -- Expenses --------------------------------------------------
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :expenseId")
    suspend fun getExpenseById(expenseId: String): Expense?

    @Query("SELECT SUM(amount) FROM expenses")
    fun getTotalExpenses(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense): Long

    @Delete
    suspend fun deleteExpense(expense: Expense)

    // -- Sync (Expense) ----------------------------------------------
    @Query("SELECT * FROM expenses WHERE pendingSync = 1")
    suspend fun getPendingSyncExpenses(): List<Expense>

    @Query("UPDATE expenses SET pendingSync = 0 WHERE id = :expenseId")
    suspend fun clearExpensePendingSync(expenseId: String)

    @Query("DELETE FROM expenses WHERE pendingSync = 0")
    suspend fun deleteSyncedExpenses()

    // Called by ExpenseSync after a picked receipt photo finishes uploading
    // to Firebase Storage - records the resulting download URL and clears
    // the upload-pending flag. Mirrors InventoryDao.markImageUploaded.
    @Query(
        "UPDATE expenses SET receiptUrl = :receiptUrl, receiptPendingUpload = 0 " +
                "WHERE id = :expenseId"
    )
    suspend fun markReceiptUploaded(expenseId: String, receiptUrl: String)

    // -- Tasks -----------------------------------------------------
    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY dueDate ASC")
    fun getPendingTasks(): Flow<List<Task>>

    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 0")
    fun getPendingTaskCount(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Query("UPDATE tasks SET isCompleted = 1, completedDate = :completedDate, pendingSync = 1 WHERE id = :taskId")
    suspend fun markTaskAsCompleted(taskId: String, completedDate: Long)

    @Delete
    suspend fun deleteTask(task: Task)

    // -- Sync (Task) -------------------------------------------------
    @Query("SELECT * FROM tasks WHERE pendingSync = 1")
    suspend fun getPendingSyncTasks(): List<Task>

    @Query("UPDATE tasks SET pendingSync = 0 WHERE id = :taskId")
    suspend fun clearTaskPendingSync(taskId: String)

    @Query("DELETE FROM tasks WHERE pendingSync = 0")
    suspend fun deleteSyncedTasks()
}