// viewmodel/actions/DebtActions.kt
package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository

/**
 * Debt-domain mutations extracted out of SMEViewModel (Option A
 * restructuring). No behavior change from the original insertDebt/
 * markDebtAsPaid functions - same repository calls, same requestPush()
 * timing.
 */
class DebtActions(
    private val repository: SMERepository,
    private val syncEngine: SyncEngine?
) {
    suspend fun insertDebt(debt: Debt) {
        repository.insertDebt(debt)
        syncEngine?.requestPush()
    }

    suspend fun markDebtAsPaid(debtId: String) {
        repository.markDebtPaid(debtId)
        syncEngine?.requestPush()
    }
}