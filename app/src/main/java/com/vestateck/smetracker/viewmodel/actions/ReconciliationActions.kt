// viewmodel/actions/ReconciliationActions.kt
package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Reconciliation-domain mutations extracted out of SMEViewModel (Option A
 * restructuring - the seventh and final section from the original audit,
 * not in the five-item rollout list but folded in to fully complete the
 * split). No behavior change from the original reconcileSale/
 * reconcileInventoryCost/editSaleCost functions - same repository calls,
 * same requestPush() timing.
 *
 * unreconciledSales and sales are passed in as the ViewModel's own
 * StateFlows - reconcileSale looks a sale up in the reconciliation queue
 * specifically (unreconciledSales), while editSaleCost deliberately looks
 * in the full sales list (see its own doc comment for why that's not a
 * relaxation of reconcileSale's scoping).
 */
class ReconciliationActions(
    private val repository: SMERepository,
    private val syncEngine: SyncEngine?,
    private val unreconciledSales: StateFlow<List<Sale>>,
    private val sales: StateFlow<List<Sale>>
) {
    // costPricePerUnit is what the owner enters (matches how InventoryItem.
    // costPrice is entered everywhere else). Sale.costPriceSnapshot is
    // documented as the TOTAL cost basis (costPrice * quantity), and
    // Sale.amount is the total the customer paid for the whole line - so
    // profit is amount minus total cost, not a per-unit difference.
    suspend fun reconcileSale(saleId: String, costPricePerUnit: Double) {
        val sale = unreconciledSales.value.find { it.id == saleId } ?: return
        val totalCost = costPricePerUnit * sale.quantity
        val profit = sale.amount - totalCost
        repository.reconcileSaleFinancials(saleId, totalCost, profit)
        syncEngine?.requestPush()
    }

    suspend fun reconcileInventoryCost(itemId: String, costPrice: Double) {
        repository.reconcileItemCost(itemId, costPrice)
        syncEngine?.requestPush()
    }

    // Owner-only correction for a sale that's ALREADY reconciled (auto or
    // manual) but turned out to need a different cost - e.g. this
    // particular unit was actually bought at a one-off price different from
    // the item's normal cost. Deliberately looks the sale up in `sales`
    // (every sale), not `unreconciledSales` - reconcileSale above is scoped
    // to the queue on purpose (see its test coverage), so this is a
    // separate, explicit "I want to revise this" action rather than a
    // relaxation of that one. Reuses the same repository write as
    // reconcileSale since the end state is identical: a reconciled sale
    // with a specific costPriceSnapshot/profit, pendingSync so it re-pushes.
    suspend fun editSaleCost(saleId: String, costPricePerUnit: Double) {
        val sale = sales.value.find { it.id == saleId } ?: return
        val totalCost = costPricePerUnit * sale.quantity
        val profit = sale.amount - totalCost
        repository.reconcileSaleFinancials(saleId, totalCost, profit)
        syncEngine?.requestPush()
    }
}