package com.example.smetracker.data.dao

import androidx.room.*
import com.example.smetracker.data.entities.InventoryItem
import com.example.smetracker.data.entities.StockAdjustment
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory_items ORDER BY name ASC")
    fun getAllItems(): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory_items WHERE id = :itemId")
    suspend fun getItemById(itemId: String): InventoryItem?

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

    @Query("UPDATE inventory_items SET quantity = quantity + :amount, updatedAt = :timestamp, pendingSync = 1 WHERE id = :itemId")
    suspend fun adjustStock(itemId: String, amount: Int, timestamp: Long)

    @Insert
    suspend fun insertStockAdjustment(adjustment: StockAdjustment)

    @Query("SELECT * FROM stock_adjustments WHERE itemId = :itemId ORDER BY createdAt DESC")
    fun getAdjustmentsForItem(itemId: String): Flow<List<StockAdjustment>>

    // Single entry point for every quantity change (incoming stock, sales,
    // recounts). Wrapping the quantity update and the log write in one
    // @Transaction means a process death between the two can't leave the
    // quantity changed with no matching log entry, or vice versa.
    @Transaction
    suspend fun applyStockAdjustment(adjustment: StockAdjustment) {
        adjustStock(adjustment.itemId, adjustment.delta, adjustment.createdAt)
        insertStockAdjustment(adjustment)
    }

    // ── Sync (StockAdjustment) ────────────────────────────────────
    @Query("SELECT * FROM stock_adjustments WHERE pendingSync = 1")
    suspend fun getPendingSyncAdjustments(): List<StockAdjustment>

    @Query("UPDATE stock_adjustments SET pendingSync = 0 WHERE id = :adjustmentId")
    suspend fun clearAdjustmentPendingSync(adjustmentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdjustmentFromRemote(adjustment: StockAdjustment)

    // ── Sync (InventoryItem) ─────────────────────────────────────
    @Query("SELECT * FROM inventory_items WHERE pendingSync = 1")
    suspend fun getPendingSyncItems(): List<InventoryItem>

    @Query("UPDATE inventory_items SET pendingSync = 0 WHERE id = :itemId")
    suspend fun clearItemPendingSync(itemId: String)

    // Sync pull, cost half only: costPrice comes from the OWNER-ONLY
    // inventoryCosts collection, separate from the worker-visible inventory
    // collection. Patches just this column so it never clobbers the rest of
    // the row, and is only ever called for an owner session (see SyncEngine
    // — a worker's device never attaches the inventoryCosts listener at all,
    // matching what the security rules already deny them).
    @Query("UPDATE inventory_items SET costPrice = :costPrice WHERE id = :itemId")
    suspend fun updateItemCostPrice(itemId: String, costPrice: Double)
}