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
 * FakeSMEDao. applyStockAdjustment and applyRemoteStockAdjustment are
 * reimplemented directly (rather than inherited as default `@Transaction`
 * methods) since Room generates that implementation at compile time; a
 * plain Kotlin override here just calls the same underlying steps.
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

    override suspend fun adjustStockFromRemote(itemId: String, amount: Int, timestamp: Long) {
        itemsFlow.update { list ->
            list.map {
                if (it.id == itemId) it.copy(quantity = it.quantity + amount, updatedAt = timestamp)
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

    override suspend fun adjustmentExists(id: String): Boolean =
        adjustmentsFlow.value.any { it.id == id }

    // Reimplemented directly rather than inherited (see class doc) - same
    // dedupe logic as the real @Transaction default method: a genuinely new
    // adjustment applies its delta and gets logged; one this fake already
    // has (this "device" created it, and the fake echoes it back) is a
    // no-op so the delta isn't counted twice.
    override suspend fun applyRemoteStockAdjustment(adjustment: StockAdjustment) {
        if (adjustmentExists(adjustment.id)) return
        adjustStockFromRemote(adjustment.itemId, adjustment.delta, adjustment.createdAt)
        insertAdjustmentFromRemote(adjustment)
    }

    // -- Oversold (InventoryItem) ----------------------------------------
    override fun getOversoldItems(): Flow<List<InventoryItem>> =
        itemsFlow.map { list -> list.filter { !it.isDeleted && it.quantity < 0 }.sortedBy { it.quantity } }

    override fun getOversoldItemsCount(): Flow<Long> =
        itemsFlow.map { list -> list.count { !it.isDeleted && it.quantity < 0 }.toLong() }

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