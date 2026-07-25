// viewmodel/actions/ExpenseActions.kt
package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository

/**
 * Expense-domain mutations extracted out of SMEViewModel (Option A
 * restructuring). No behavior change from the original addExpense/
 * deleteExpense functions - same repository calls, same requestPush()
 * timing.
 */
class ExpenseActions(
    private val repository: SMERepository,
    private val syncEngine: SyncEngine?
) {
    suspend fun addExpense(
        description: String,
        amount: Double,
        category: String = "General",
        receiptNumber: String? = null,
        localReceiptPath: String? = null
    ) {
        repository.addExpense(
            Expense(
                description = description,
                amount = amount,
                category = category,
                receiptNumber = receiptNumber,
                localReceiptPath = localReceiptPath,
                receiptPendingUpload = localReceiptPath != null
            )
        )
        syncEngine?.requestPush()
    }

    suspend fun deleteExpense(expense: Expense) {
        repository.deleteExpense(expense)
        syncEngine?.requestPush()
    }
}