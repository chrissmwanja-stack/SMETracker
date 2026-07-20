package com.vestateck.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

data class Business(
    @DocumentId val id: String = "",
    val name: String = "",
    val ownerPhone: String = "",
    // Optional — shown on sale receipts alongside name/ownerPhone. Blank
    // until an owner sets it via the Business Settings screen; blank is a
    // valid state (receipt just omits the address line), not an error.
    val address: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    // Firestore requires a no-arg constructor for deserialization;
    // the all-default-values constructor above already satisfies that.
}