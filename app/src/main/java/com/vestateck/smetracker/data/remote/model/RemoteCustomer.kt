package com.vestateck.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

// Firestore path: businesses/{businessId}/customers/{customerId}
// No worker/owner split — both roles need full customer info to do their job.
data class RemoteCustomer(
    @DocumentId val id: String = "",
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)