package com.vestateck.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

// Firestore path: businesses/{businessId}/inventory/{itemId}
//
// Includes sellingPrice (a worker needs this to make a sale) but NOT costPrice
// (that would reveal margin). costPrice lives in the linked InventoryCost doc.
data class RemoteInventoryItem(
    @DocumentId val id: String = "",
    val name: String = "",
    val category: String = "",
    val quantity: Int = 0,
    val reorderLevel: Int = 5,
    val sellingPrice: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    // Phone number (E.164) of whoever created this item — mirrors
    // RemoteSale.recordedBy. Used by an owner's device to know whose items
    // need a cost review; see SyncEngine and the Reconciliation screen.
    val recordedBy: String = "",
    // Firebase Storage download URL — blank means no photo has been
    // uploaded for this item yet. See InventorySync.pushPending for the
    // upload step and InventoryItem's doc comment for why this is separate
    // from the local device's cached copy.
    val imageUrl: String = "",
    val isDeleted: Boolean = false
)