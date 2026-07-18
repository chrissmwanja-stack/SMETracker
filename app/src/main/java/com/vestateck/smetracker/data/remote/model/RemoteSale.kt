package com.example.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

// Firestore path: businesses/{businessId}/sales/{saleId}
//
// This is the WORKER-VISIBLE half of a sale. It deliberately excludes costPrice
// and profit — those live in the linked SaleFinancials doc (same saleId), which
// Firestore Security Rules restrict to role == "owner" only.
//
// `amount` stays here because it's the selling price / what the customer paid,
// which a worker needs to see to do their job (confirm what was charged, etc.).
// It's profit margin that's sensitive, not the sale total itself.
data class RemoteSale(
    @DocumentId val id: String = "",
    val customerId: String? = null,
    val customerName: String = "",
    val description: String = "",
    val amount: Double = 0.0,
    val inventoryItemId: String? = null,
    val quantity: Int = 1,
    val date: Long = System.currentTimeMillis(),
    val paymentMethod: String = "CASH",
    // Phone number (E.164) of whoever recorded this sale — owner or worker.
    val recordedBy: String = ""
)