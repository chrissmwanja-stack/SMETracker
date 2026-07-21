package com.vestateck.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

// Firestore path: businesses/{businessId}/expenses/{expenseId}
//
// Workers can record expenses (they're the ones actually spending - petty cash,
// supplies, etc.) but every expense starts as PENDING and needs owner approval.
// Access model:
//   - Worker: can CREATE (always status = PENDING), and can READ only expenses
//     where recordedBy == their own phone (so they can see the status of their
//     own submissions) - not the full expenses collection or its totals.
//   - Owner: full read/write, including changing status to APPROVED/REJECTED.
// Dashboard/report totals (e.g. the net profit calculation) should only count
// APPROVED expenses, not pending ones still awaiting a decision.
data class RemoteExpense(
    @DocumentId val id: String = "",
    val description: String = "",
    val amount: Double = 0.0,
    val category: String = "General",
    val date: Long = System.currentTimeMillis(),
    val receiptNumber: String? = null,
    val recordedBy: String = "",
    val status: ExpenseStatus = ExpenseStatus.PENDING,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val receiptUrl: String? = null,
    val isDeleted: Boolean = false
)