package com.vestateck.smetracker.fakes

import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.StockAdjustment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Hand-rolled in-memory fake of InventoryDao - same rationale as
 * FakeSMEDao. applyStockAdjustment is reimplemented directly (rather than
 * inherited as a default `@Transaction` method) since Room generates that
 * implementation at compile time; a plain Kotlin override here just calls
 * adjustStock + insertStockAdjustment the same way.
 */
class FakeInventoryDao : InventoryDao {

    val itemsFlow = MutableStateFlow<List<InventoryItem>>(emptyList())
    val adjustmentsFlow = MutableStateFlow<List<StockAdjustment>>(emptyList())

    override fun getAllItems(): Flow<List<InventoryItem>> =
        itemsFlow.map { it.filter { i -> !i.isDeleted }.sortedBy { i -> i.name } }

    override suspend fun getItemById(itemId: String): InventoryItem? =
        itemsFlow.value.find { it.id == itemId && !it.isDeleted }

    override fun getLowStockItems(threshold: Int): Flow<List<InventoryItem>> =
        itemsFlow.map { list -> list.filter { !it.isDeleted && it.quantity <= threshold } }

    override fun getLowStockCount(threshold: Int): Flow<Long> =
        itemsFlow.map { list -> list.count { !it.isDeleted && it.quantity <= threshold }.toLong() }

    override fun getTotalItemCount(): Flow<Long> = itemsFlow.map { it.count { i -> !i.isDeleted }.toLong() }
    override fun getTotalStockValue(): Flow<Double> =
        itemsFlow.map { list -> list.filter { !it.isDeleted }.sumOf { it.quantity * it.costPrice } }

    override suspend fun insert(item: InventoryItem) {
        itemsFlow.update { list -> list.filterNot { it.id == item.id } + item }
    }

    override suspend fun insertAll(items: List<InventoryItem>) {
        itemsFlow.update { list ->
            val newIds = items.map { it.id }.toSet()
            list.filterNot { it.id in newIds } + items
        }
    }

    override suspend fun update(item: InventoryItem) {
        itemsFlow.update { list -> list.map { if (it.id == item.id) item else it } }
    }

    override suspend fun delete(item: InventoryItem) {
        itemsFlow.update { list -> list.filterNot { it.id == item.id } }
    }

    override suspend fun markItemAsDeleted(itemId: String) {
        itemsFlow.update { list ->
            list.map {
                if (it.id == itemId) it.copy(isDeleted = true, pendingSync = true)
                else it
            }
        }
    }

    override suspend fun adjustStock(itemId: String, amount: Int, timestamp: Long) {
        itemsFlow.update { list ->
            list.map {
                if (it.id == itemId) it.copy(quantity = it.quantity + amount, updatedAt = timestamp, pendingSync = true)
                else it
            }
        }
    }

    override suspend fun insertStockAdjustment(adjustment: StockAdjustment) {
        adjustmentsFlow.update { list -> list + adjustment }
    }

    override fun getAdjustmentsForItem(itemId: String): Flow<List<StockAdjustment>> =
        adjustmentsFlow.map { list -> list.filter { it.itemId == itemId }.sortedByDescending { it.createdAt } }

    override suspend fun applyStockAdjustment(adjustment: StockAdjustment) {
        adjustStock(adjustment.itemId, adjustment.delta, adjustment.createdAt)
        insertStockAdjustment(adjustment)
    }

    override suspend fun getPendingSyncAdjustments(): List<StockAdjustment> = adjustmentsFlow.value.filter { it.pendingSync }
    override suspend fun clearAdjustmentPendingSync(adjustmentId: String) {
        adjustmentsFlow.update { list -> list.map { if (it.id == adjustmentId) it.copy(pendingSync = false) else it } }
    }

    override suspend fun deleteSyncedAdjustments() {
        adjustmentsFlow.update { list -> list.filter { it.pendingSync } }
    }

    override suspend fun insertAdjustmentFromRemote(adjustment: StockAdjustment) {
        adjustmentsFlow.update { list -> list.filterNot { it.id == adjustment.id } + adjustment }
    }

    // -- Reconciliation (InventoryItem) --------------------------------
    override fun getUnreconciledItems(): Flow<List<InventoryItem>> =
        itemsFlow.map { list -> list.filter { !it.isDeleted && !it.costReconciled } }

    override fun getUnreconciledItemsCount(): Flow<Long> =
        itemsFlow.map { list -> list.count { !it.isDeleted && !it.costReconciled }.toLong() }

    override suspend fun reconcileItemCost(itemId: String, costPrice: Double) {
        itemsFlow.update { list ->
            list.map {
                if (it.id == itemId) it.copy(costPrice = costPrice, costReconciled = true, pendingSync = true)
                else it
            }
        }
    }

    // -- Sync (InventoryItem) -------------------------------------------
    override suspend fun getPendingSyncItems(): List<InventoryItem> = itemsFlow.value.filter { it.pendingSync }
    override suspend fun clearItemPendingSync(itemId: String) {
        itemsFlow.update { list -> list.map { if (it.id == itemId) it.copy(pendingSync = false) else it } }
    }

    override suspend fun deleteSyncedItems() {
        itemsFlow.update { list -> list.filter { it.pendingSync } }
    }

    override suspend fun updateItemCostPrice(itemId: String, costPrice: Double) {
        itemsFlow.update { list -> list.map { if (it.id == itemId) it.copy(costPrice = costPrice) else it } }
    }

    // Called by InventorySync after a picked photo finishes uploading to
    // Firebase Storage - records the resulting download URL and clears the
    // upload-pending flag so pushPending() doesn't re-upload the same file
    // on the item's next unrelated edit.
    override suspend fun markImageUploaded(itemId: String, imageUrl: String) {
        itemsFlow.update { list ->
            list.map {
                if (it.id == itemId) it.copy(imageUrl = imageUrl, imagePendingUpload = false)
                else it
            }
        }
    }
}