package com.example.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

// Firestore path: businesses/{businessId}
data class Business(
    @DocumentId val id: String = "",
    val name: String = "",
    val ownerPhone: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    // Firestore requires a no-arg constructor for deserialization;
    // the all-default-values constructor above already satisfies that.
}