package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.remote.model.RemoteSale

/**
 * Pure logic behind SaleSync.attachSaleListener's merge decision - split
 * into its own file so it's unit-testable without a FirebaseFirestore
 * instance (see SaleMergeTest.kt).
 */
fun mergeIncomingSale(
    remote: RemoteSale,
    existing: Sale?,
    linkedItem: InventoryItem?
): Sale {
    val paymentMethod = try {
        PaymentMethod.valueOf(remote.paymentMethod)
    } catch (e: Exception) {
        PaymentMethod.CASH
    }

    return if (existing != null) {
        // existing != null: this device's own values always win for fields that
        // are only ever modified/derived locally (reconciliation state,
        // provisional receipt number).
        Sale(
            id = remote.id,
            customerId = remote.customerId,
            customerName = remote.customerName,
            description = remote.description,
            amount = remote.amount,
            profit = existing.profit,
            costPriceSnapshot = existing.costPriceSnapshot,
            inventoryItemId = remote.inventoryItemId,
            quantity = remote.quantity,
            date = remote.date,
            paymentMethod = paymentMethod,
            recordedBy = remote.recordedBy,
            financialsReconciled = existing.financialsReconciled,
            provisionalReceiptNumber = existing.provisionalReceiptNumber,
            finalReceiptNumber = remote.finalReceiptNumber,
            pendingSync = existing.pendingSync,
            isDeleted = remote.isDeleted
        )
    } else {
        // existing == null: new sale from another device.
        val financialsReconciled: Boolean
        val profit: Double
        val costPriceSnapshot: Double
        val pendingSync: Boolean

        if (remote.inventoryItemId == null) {
            // No item linked -> no cost to track -> by definition reconciled
            // with zero profit/cost.
            financialsReconciled = true
            profit = 0.0
            costPriceSnapshot = 0.0
            pendingSync = false
        } else if (linkedItem != null && linkedItem.costReconciled && linkedItem.costPrice > 0.0) {
            // This device already knows the item's cost price -> derive
            // reconciled financials locally.
            costPriceSnapshot = linkedItem.costPrice * remote.quantity
            profit = remote.amount - costPriceSnapshot
            financialsReconciled = true
            // Derived locally but never pushed as saleFinancials by the sender -
            // this device must push it itself if it's the owner (see pushPending).
            pendingSync = true
        } else {
            // Item exists but its cost is unknown, or we haven't synced the
            // item yet (linkedItem == null) -> leave unreconciled.
            financialsReconciled = false
            profit = 0.0
            costPriceSnapshot = 0.0
            pendingSync = false
        }

        Sale(
            id = remote.id,
            customerId = remote.customerId,
            customerName = remote.customerName,
            description = remote.description,
            amount = remote.amount,
            profit = profit,
            costPriceSnapshot = costPriceSnapshot,
            inventoryItemId = remote.inventoryItemId,
            quantity = remote.quantity,
            date = remote.date,
            paymentMethod = paymentMethod,
            recordedBy = remote.recordedBy,
            financialsReconciled = financialsReconciled,
            // A brand-new sale from another device doesn't have a
            // provisionalReceiptNumber from THIS device's generator - fall
            // back to its authoritative final number if it has one already,
            // so things like SaleReceiptScreen don't show a blank header.
            provisionalReceiptNumber = remote.finalReceiptNumber ?: "",
            finalReceiptNumber = remote.finalReceiptNumber,
            pendingSync = pendingSync,
            isDeleted = remote.isDeleted
        )
    }
}
