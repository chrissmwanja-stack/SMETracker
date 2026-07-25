// viewmodel/actions/SaleActions.kt
package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.ReceiptNumberGenerator
import com.vestateck.smetracker.viewmodel.SaleLineInput
import kotlinx.coroutines.flow.StateFlow

/**
 * Sale-domain mutations extracted out of SMEViewModel (Option A
 * restructuring, last of the five planned delegates - the most complex
 * domain, done once the pattern was well-proven on the simpler ones). No
 * behavior change from the original addSale/insertSale/addSaleLines/
 * deleteSale functions - same repository calls, same stock-check
 * defense-in-depth, same auto-reconciliation logic, same receipt-number
 * handling, same requestPush() timing.
 *
 * currentSession is passed in as a function reference (SMEViewModel's own
 * private currentSession()), same pattern as InventoryActions. inventoryItems
 * is passed in as the ViewModel's own StateFlow so soldItem lookups
 * (inventoryItems.value.find { ... }) read the same live data screens see -
 * no separate/duplicated inventory cache here.
 */
class SaleActions(
    private val repository: SMERepository,
    private val syncEngine: SyncEngine?,
    private val currentSession: suspend () -> Pair<String, Boolean>,
    private val receiptNumberGenerator: ReceiptNumberGenerator?,
    private val inventoryItems: StateFlow<List<InventoryItem>>
) {
    // For a custom/service sale not tied to tracked inventory (inventoryItemId = null), profit stays 0
    // since there's no known cost basis. Pass an inventoryItemId to compute real profit and decrement stock.
    suspend fun addSale(
        customerName: String,
        description: String,
        amount: Double,
        paymentMethod: PaymentMethod,
        inventoryItemId: String? = null,
        quantity: Int = 1,
        // Set when the customer was picked from the saved-customers dropdown
        // rather than typed as a one-off name - links this sale back to that
        // Customer record. Null is a legitimate, common case (ad-hoc/walk-in
        // sale with no saved customer), not an error.
        customerId: String? = null
    ) {
        insertSale(customerName, description, amount, paymentMethod, inventoryItemId, quantity, customerId)
        syncEngine?.requestPush()
    }

    // Core of addSale, factored out so addSaleLines (below) can await each
    // insert and hand the whole batch of created Sale rows back to its
    // caller (e.g. AddSaleScreen, to build the post-save receipt - see
    // ReceiptData.from). addSale's own public signature above stays
    // fire-and-forget, unchanged for existing callers.
    private suspend fun insertSale(
        customerName: String,
        description: String,
        amount: Double,
        paymentMethod: PaymentMethod,
        inventoryItemId: String? = null,
        quantity: Int = 1,
        customerId: String? = null,
        // One receipt number is meant to cover a whole checkout, not one
        // product on it - see addSaleLines below, which claims a single
        // number up front and passes the SAME string into every line's
        // insertSale call. Null (addSale's plain single-item path) means
        // "this call IS its own one-item checkout", so a fresh number is
        // generated here instead.
        provisionalReceiptNumber: String? = null
    ): Sale? {
        val soldItem = inventoryItemId?.let { id -> inventoryItems.value.find { it.id == id } }
        // Defense-in-depth behind AddSaleScreen's own stock check: reject
        // outright rather than silently clamping or partially applying if
        // the requested quantity exceeds what's on hand, or if the item id
        // no longer resolves (e.g. deleted between the screen reading it and
        // this coroutine running). adjustStock has no floor of its own (see
        // InventoryDao), so this is the only thing stopping a future caller
        // that skips screen-level validation from pushing quantity negative.
        if (inventoryItemId != null && (soldItem == null || quantity > soldItem.quantity)) {
            return null
        }
        // Auto-apply the item's own cost price whenever it's already known
        // (costReconciled && costPrice > 0) - that's the single source of
        // truth for what this item costs (set once via the Inventory
        // screen or the Reconciliation screen), so a sale against it
        // shouldn't need a second, separate manual confirmation just to
        // repeat the same number back. A genuine 0.0 doesn't count as
        // "known" (mirrors SaleReconciliationDialog's own suggestion logic)
        // since that's indistinguishable from the item's cost never having
        // been set.
        val itemCostKnown = soldItem != null && soldItem.costReconciled && soldItem.costPrice > 0.0
        val profit = soldItem?.let { (it.sellingPrice - it.costPrice) * quantity } ?: 0.0
        val costPriceSnapshot = soldItem?.let { it.costPrice * quantity } ?: 0.0
        val (myPhone, _) = currentSession()
        // A custom/service sale (no linked item) has no cost basis to review,
        // so it's reconciled by definition. A sale tied to a tracked item is
        // only trustworthy once that item's OWN cost price is known -
        // regardless of whether an owner's or a worker's device recorded the
        // sale (an owner can sell an item whose cost was never set, same as
        // a worker can) - so it still needs manual review via reconcileSale
        // until the item's cost is actually set. Once it is, every sale
        // created afterward just inherits it automatically from here.
        val financialsReconciled = inventoryItemId == null || itemCostKnown
        val resolvedProvisionalReceiptNumber = provisionalReceiptNumber
            ?: receiptNumberGenerator?.next(myPhone) ?: ""

        // Held in a val (not inlined into insertSale's argument) so the same
        // id/fields that get persisted are what's handed back to the caller
        // - Sale's id is generated client-side at construction (IdGenerator),
        // so this object already reflects exactly what's in the DB.
        val sale = Sale(
            customerId = customerId,
            customerName = customerName,
            description = description,
            amount = amount,
            profit = profit,
            costPriceSnapshot = costPriceSnapshot,
            inventoryItemId = inventoryItemId,
            quantity = quantity,
            paymentMethod = paymentMethod,
            date = System.currentTimeMillis(),
            recordedBy = myPhone,
            financialsReconciled = financialsReconciled,
            provisionalReceiptNumber = resolvedProvisionalReceiptNumber
        )
        repository.insertSale(sale)

        if (soldItem != null) {
            repository.recordSaleStockAdjustment(soldItem.id, quantity)
        }
        return sale
    }

    // Entry point for AddSaleScreen's "cart" of line items. Handles the one
    // thing that's genuinely different about that screen vs. calling addSale
    // directly per line: the customer may be a walk-in the user has chosen,
    // via the "Save as new customer" toggle, to promote into a real saved
    // Customer for this sale. That promotion has to happen once, up front,
    // and be awaited - every line item then shares the same resolved
    // customerId, rather than each line racing to create its own duplicate
    // Customer row.
    suspend fun addSaleLines(
        customerName: String,
        selectedCustomerId: String?,
        saveAsNewCustomer: Boolean,
        paymentMethod: PaymentMethod,
        lines: List<SaleLineInput>,
        // Called once, after every line has been attempted, with every Sale
        // row this checkout actually created (a line that failed the stock
        // check inside insertSale is simply omitted, not retried). Defaults
        // to a no-op so existing callers don't need to change.
        // AddSaleScreen uses this to navigate to the receipt screen with the
        // real, persisted Sale rows rather than re-deriving them by matching
        // timestamp/customer back out of the sales flow.
        onSalesCreated: (List<Sale>) -> Unit = {}
    ) {
        val resolvedCustomerId = when {
            selectedCustomerId != null -> selectedCustomerId
            saveAsNewCustomer && customerName.isNotBlank() -> {
                val newCustomer = Customer(name = customerName)
                repository.insertCustomer(newCustomer)
                newCustomer.id
            }
            else -> null
        }
        // One receipt number for this whole checkout, claimed once here and
        // reused for every line - not one per product. SaleSync.pushPending
        // mirrors this by claiming a single authoritative number per group
        // of sales that share a provisionalReceiptNumber, so the two stay
        // consistent whether the number is being read offline or after sync.
        val (myPhone, _) = currentSession()
        val checkoutReceiptNumber = receiptNumberGenerator?.next(myPhone) ?: ""

        val created = mutableListOf<Sale>()
        lines.forEach { line ->
            val sale = insertSale(
                customerName = customerName,
                customerId = resolvedCustomerId,
                description = line.description,
                amount = line.amount,
                paymentMethod = paymentMethod,
                inventoryItemId = line.inventoryItemId,
                quantity = line.quantity,
                provisionalReceiptNumber = checkoutReceiptNumber
            )
            if (sale != null) created.add(sale)
        }
        syncEngine?.requestPush()
        onSalesCreated(created)
    }

    suspend fun deleteSale(sale: Sale) {
        repository.deleteSale(sale)
        syncEngine?.requestPush()
    }
}