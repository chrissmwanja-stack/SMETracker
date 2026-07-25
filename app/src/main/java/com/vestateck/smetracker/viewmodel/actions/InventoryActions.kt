// viewmodel/actions/InventoryActions.kt
package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.BulkInventoryRow
import com.vestateck.smetracker.utils.IdGenerator

/**
 * Inventory-domain mutations extracted out of SMEViewModel (Option A
 * restructuring). No behavior change from the original upsertInventoryItem/
 * addInventoryItem/addInventoryItemsBulk/deleteInventoryItem/
 * getAdjustmentsForItem/receiveStock/recountStock functions - same
 * repository calls, same owner/worker cost-reconciliation gating, same
 * requestPush() timing.
 *
 * currentSession is passed in as a function reference (SMEViewModel's own
 * private currentSession()) rather than duplicated here, since it depends
 * on SessionManager plumbing that stays owned by the ViewModel.
 */
class InventoryActions(
    private val repository: SMERepository,
    private val syncEngine: SyncEngine?,
    private val currentSession: suspend () -> Pair<String, Boolean>
) {
    // InventoryItemDialog is used for both Add and Edit; it passes a blank id
    // for a brand-new item (mirroring the old id == 0L convention), so a fresh
    // id is only generated here, at the moment we know it's really an insert.
    suspend fun upsertInventoryItem(item: InventoryItem) {
        if (item.id.isBlank()) {
            val (myPhone, isOwner) = currentSession()
            // A worker's Add dialog never shows a cost field (see
            // InventoryItemDialog), so costPrice here is always the unset 0.0
            // default for a worker - that's exactly the case that needs an
            // owner's review. An owner creating the item already entered a
            // real cost, so it's reconciled immediately.
            val newItem = item.copy(id = IdGenerator.newId(), recordedBy = myPhone, costReconciled = isOwner)
            repository.insertInventoryItem(newItem)
            repository.logInitialStock(newItem.id, newItem.quantity, myPhone)
        } else {
            // Editing an existing item always goes through the owner-only
            // cost field when isOwner (see InventoryItemDialog) - if this
            // save came from an owner, treat it as having reviewed the cost.
            val (_, isOwner) = currentSession()
            repository.updateInventoryItem(if (isOwner) item.copy(costReconciled = true) else item)
        }
        syncEngine?.requestPush()
    }

    suspend fun addInventoryItem(
        name: String,
        quantity: Int,
        sellingPrice: Double,
        category: String = "",
        costPrice: Double = 0.0,
        reorderLevel: Int = 5,
        // Mirrors InventoryItemDialog's photo handling (see that file's doc
        // comment on InventoryItem.localImagePath) - this quick-add screen
        // now offers the same picker, so a photo taken here needs the same
        // two fields to make it into InventorySync.pushPending's upload step.
        localImagePath: String? = null,
        imagePendingUpload: Boolean = false,
        sku: String? = null
    ) {
        val (myPhone, isOwner) = currentSession()
        val newItem = InventoryItem(
            name = name,
            quantity = quantity,
            sellingPrice = sellingPrice,
            category = category,
            costPrice = costPrice,
            reorderLevel = reorderLevel,
            recordedBy = myPhone,
            costReconciled = isOwner,
            localImagePath = localImagePath,
            imagePendingUpload = imagePendingUpload,
            sku = sku
        )
        repository.insertInventoryItem(newItem)
        repository.logInitialStock(newItem.id, newItem.quantity, myPhone)
        syncEngine?.requestPush()
    }

    // Bulk counterpart to addInventoryItem, for CSV import (see
    // BulkAddInventoryScreen / InventoryCsvImporter). One currentSession()
    // call covers the whole batch, then a single insertAll + single
    // requestPush - but each row still gets the exact same owner/cost
    // gating addInventoryItem applies per item, so a bulk-imported item
    // behaves identically to one added by hand:
    //   - a worker's costPrice is never trusted, CSV cell or not (matches
    //     AddInventoryScreen/InventoryItemDialog never showing that field
    //     to a worker in the first place)
    //   - an owner's row with no costPrice cell falls into the same
    //     Reconciliation queue a worker-created item would.
    suspend fun addInventoryItemsBulk(rows: List<BulkInventoryRow>) {
        val (myPhone, isOwner) = currentSession()
        val items = rows.map { row ->
            val cost = if (isOwner) (row.costPrice ?: 0.0) else 0.0
            val reconciled = isOwner && row.costPrice != null
            InventoryItem(
                name = row.name,
                category = row.category,
                quantity = row.quantity,
                sellingPrice = row.sellingPrice,
                costPrice = cost,
                reorderLevel = row.reorderLevel,
                recordedBy = myPhone,
                costReconciled = reconciled,
                sku = row.sku
            )
        }
        repository.insertInventoryItems(items)
        items.forEach { repository.logInitialStock(it.id, it.quantity, myPhone) }
        syncEngine?.requestPush()
    }

    suspend fun deleteInventoryItem(item: InventoryItem) {
        repository.deleteInventoryItem(item)
        syncEngine?.requestPush()
    }

    fun getAdjustmentsForItem(itemId: String) = repository.getAdjustmentsForItem(itemId)

    // Incoming Stock - additive-only, available to workers and owners alike.
    suspend fun receiveStock(itemId: String, quantity: Int, note: String? = null) {
        repository.receiveStock(itemId, quantity, note)
        syncEngine?.requestPush()
    }

    // Recount - owner-only correction after a physical count. The screen is
    // responsible for only exposing this to an owner and for requiring a note;
    // this function trusts its caller on both, same as the rest of this class.
    suspend fun recountStock(itemId: String, newQuantity: Int, note: String) {
        repository.recountStock(itemId, newQuantity, note)
        syncEngine?.requestPush()
    }
}