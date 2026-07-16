package com.example.smetracker.data.remote.model

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
    val updatedAt: Long = System.currentTimeMillis()
)