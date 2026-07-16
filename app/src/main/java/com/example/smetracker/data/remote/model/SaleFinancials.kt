package com.example.smetracker.data.remote.model

// Firestore path: businesses/{businessId}/saleFinancials/{saleId}
//
// Uses the SAME document ID as the linked RemoteSale — a 1:1 relationship via shared ID,
// not a foreign key field. Firestore Security Rules block worker read access to this
// entire collection, which is what actually keeps profit hidden (not just the UI).
//
// costPrice is snapshotted at time of sale (not looked up live from inventory),
// so historical profit stays accurate even if the item's cost price changes later.
data class SaleFinancials(
    val saleId: String = "",
    val costPrice: Double = 0.0,
    val profit: Double = 0.0
)