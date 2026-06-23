package com.example.smetracker.data.dao

import androidx.room.*
import com.example.smetracker.data.entities.InventoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory_items ORDER BY name ASC")
    fun getAllItems(): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory_items WHERE quantity <= :threshold ORDER BY quantity ASC")
    fun getLowStockItems(threshold: Int): Flow<List<InventoryItem>>

    @Query("SELECT COUNT(*) FROM inventory_items WHERE quantity <= :threshold")
    fun getLowStockCount(threshold: Int): Flow<Long>

    @Query("SELECT COUNT(*) FROM inventory_items")
    fun getTotalItemCount(): Flow<Long>

    @Query("SELECT IFNULL(SUM(quantity * costPrice), 0.0) FROM inventory_items")
    fun getTotalStockValue(): Flow<Double>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InventoryItem)

    @Update
    suspend fun update(item: InventoryItem)

    @Delete
    suspend fun delete(item: InventoryItem)

    @Query("UPDATE inventory_items SET quantity = quantity + :amount, updatedAt = :timestamp WHERE id = :itemId")
    suspend fun adjustStock(itemId: Long, amount: Int, timestamp: Long)
}