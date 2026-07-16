package com.example.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

// Firestore path: businesses/{businessId}/debts/{debtId}
// No worker/owner split — a worker following up on a customer debt needs full visibility.
data class RemoteDebt(
    @DocumentId val id: String = "",
    val customerId: String? = null,
    val customerName: String = "",
    val description: String = "",
    val amount: Double = 0.0,
    val isPaid: Boolean = false,
    val dueDate: Long? = null,
    val date: Long = System.currentTimeMillis(),
    val recordedBy: String = ""
)