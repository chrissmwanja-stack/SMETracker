package com.example.smetracker.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.smetracker.data.entities.Sale
import kotlinx.coroutines.flow.Flow

// Helper data class for Room to map the grouped result
data class TopCustomerResult(val customerName: String, val totalAmount: Double)

@Dao
interface SaleDao {
    // ✅ OPTIMIZED: Let SQLite sort and limit, don't pull all sales into memory
    @Query("SELECT * FROM sales ORDER BY date DESC LIMIT :limit")
    fun getRecentSales(limit: Int = 10): Flow<List<Sale>>

    // ✅ OPTIMIZED: Let SQLite calculate the sum
    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM sales WHERE date >= :startOfDay")
    fun getTodayRevenue(startOfDay: Long): Flow<Double>

    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM sales")
    fun getTotalRevenue(): Flow<Double>

    // ✅ OPTIMIZED: Let SQLite group and find top customers
    @Query("""
        SELECT customerName, SUM(amount) as totalAmount 
        FROM sales 
        WHERE customerName IS NOT NULL AND customerName != ''
        GROUP BY customerName 
        ORDER BY totalAmount DESC 
        LIMIT 5
    """)
    fun getTopCustomers(): Flow<List<TopCustomerResult>>
}