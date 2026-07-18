package com.vestateck.smetracker.data.remote.model

// Firestore path: businesses/{businessId}/inventoryCosts/{itemId}
// Same document ID as the linked RemoteInventoryItem. Owner-only read access.
data class InventoryCost(
    val itemId: String = "",
    val costPrice: Double = 0.0
)