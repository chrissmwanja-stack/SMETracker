package com.example.smetracker.data.dao

import androidx.room.*
import com.example.smetracker.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SMEDao {
    // ── Sales ─────────────────────────────────────────────────────
    @Query("SELECT * FROM sales ORDER BY date DESC")
    fun getAllSales(): Flow<List<Sale>>

    @Query("SELECT SUM(amount) FROM sales")
    fun getTotalRevenue(): Flow<Double?>

    @Query("SELECT SUM(amount) FROM sales WHERE date >= :startOfDay")
    fun getTodayRevenue(startOfDay: Long): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(sale: Sale): Long

    @Delete
    suspend fun deleteSale(sale: Sale)

    // ── Customers ─────────────────────────────────────────────────
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<Customer>>

    @Query("SELECT * FROM customers WHERE name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchCustomers(query: String): Flow<List<Customer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: Customer): Long

    @Delete
    suspend fun deleteCustomer(customer: Customer)

    // ── Debts ────────────────────────────────────────────────────
    @Query("SELECT * FROM debts ORDER BY date DESC")
    fun getAllDebts(): Flow<List<Debt>>

    @Query("SELECT * FROM debts WHERE isPaid = 0 ORDER BY dueDate ASC")
    fun getUnpaidDebts(): Flow<List<Debt>>

    @Query("SELECT SUM(amount) FROM debts WHERE isPaid = 0")
    fun getTotalOutstandingDebt(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebt(debt: Debt): Long

    @Query("UPDATE debts SET isPaid = 1 WHERE id = :debtId")
    suspend fun markDebtAsPaid(debtId: Long)

    @Delete
    suspend fun deleteDebt(debt: Debt)

    // ── Expenses ─────────────────────────────────────────────────
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Query("SELECT SUM(amount) FROM expenses")
    fun getTotalExpenses(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense): Long

    // ── Tasks ────────────────────────────────────────────────────
    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY dueDate ASC")
    fun getPendingTasks(): Flow<List<Task>>

    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 0")
    fun getPendingTaskCount(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Query("UPDATE tasks SET isCompleted = 1, completedDate = :completedDate WHERE id = :taskId")
    suspend fun markTaskAsCompleted(taskId: Long, completedDate: Long)
}