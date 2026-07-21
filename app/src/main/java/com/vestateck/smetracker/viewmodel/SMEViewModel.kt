// viewmodel/SMEViewModel.kt
package com.vestateck.smetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vestateck.smetracker.data.DashboardAnalytics
import com.vestateck.smetracker.data.DashboardUiState
import com.vestateck.smetracker.data.entities.*
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.model.Business
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.IdGenerator
import com.vestateck.smetracker.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// One product line from AddSaleScreen's cart - the subset of SaleLineItem
// that's already been validated/resolved (amount and quantity parsed,
// description defaulted) and is ready to become its own Sale row.
data class SaleLineInput(
    val description: String,
    val amount: Double,
    val inventoryItemId: String? = null,
    val quantity: Int = 1
)

class SMEViewModel(
    private val repository: SMERepository,
    // Nullable so tests/previews that don't need sync can keep constructing
    // this ViewModel with just a repository. Every mutation below calls
    // syncEngine?.requestPush() after its local write - deletions are the
    // one exception, since SyncEngine doesn't sync deletes in either
    // direction yet (a known limitation, not an oversight here).
    private val syncEngine: SyncEngine? = null,
    // Nullable for the same reason as syncEngine. Used only to stamp
    // recordedBy and the reconciliation flags at creation time (see
    // addSale/addInventoryItem/upsertInventoryItem) - when null, new sales
    // and inventory items are treated as owner-recorded/already-reconciled,
    // matching this ViewModel's pre-reconciliation behavior.
    private val sessionManager: SessionManager? = null,
    // Nullable for the same reason as syncEngine/sessionManager. Used only
    // to load the business's display name for the dashboard header - when
    // null (or when there's no session/businessId yet), businessName just
    // stays blank and callers fall back to a default label.
    private val businessRepository: BusinessRepository? = null,
    // Nullable for the same reason as syncEngine/sessionManager. Used only
    // to assign Sale.provisionalReceiptNumber at creation time (see
    // addSale) - when null, provisionalReceiptNumber stays blank, matching
    // pre-receipt-feature behavior for tests/previews.
    private val receiptNumberGenerator: com.vestateck.smetracker.utils.ReceiptNumberGenerator? = null
) : ViewModel() {

    val sales: StateFlow<List<Sale>> = repository.allSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customers: StateFlow<List<Customer>> = repository.allCustomers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debts: StateFlow<List<Debt>> = repository.allDebts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventoryItems: StateFlow<List<InventoryItem>> = repository.allInventoryItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expenses: StateFlow<List<Expense>> = repository.getAllExpenses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingTasks: StateFlow<List<Task>> = repository.getPendingTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -- Reconciliation (owner-only; screen is responsible for gating) --
    val unreconciledSales: StateFlow<List<Sale>> = repository.unreconciledSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreconciledInventoryItems: StateFlow<List<InventoryItem>> = repository.unreconciledInventoryItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreconciledCount: StateFlow<Int> = combine(
        repository.unreconciledSalesCount, repository.unreconciledInventoryCount
    ) { sales, items -> (sales + items).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Full business record (name/phone/address) for the dashboard header and
    // the sale receipt document (Chunk B), loaded once per businessId change
    // rather than folded into the uiState combine() chain above - the
    // business's own details essentially never change during a session, so
    // there's no need to re-fetch on every sales/customers/etc. update.
    // Stays null if there's no session, no businessId yet, or the fetch
    // fails; callers should fall back to a sensible default in that case.
    val business: StateFlow<Business?> = (sessionManager?.sessionState ?: flowOf(null))
        .map { it?.businessId }
        .distinctUntilChanged()
        .map { businessId ->
            if (businessId == null || businessRepository == null) {
                null
            } else {
                businessRepository.getBusiness(businessId).getOrNull()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val businessName: StateFlow<String> = business
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Snapshot of "who's recording this and is it trustworthy", read fresh at
    // each mutation rather than cached, since the session can change (e.g.
    // sign-out/sign-in) across the ViewModel's lifetime.
    private suspend fun currentSession(): Pair<String, Boolean> {
        val session = sessionManager?.sessionState?.first()
        return (session?.phoneNumberE164 ?: "") to (session?.isOwner ?: true)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        sales, customers, debts, inventoryItems, expenses
    ) { sales, customers, debts, inventory, expenseList ->
        DashboardUiState(
            totalRevenue = sales.sumOf { it.amount },
            todayRevenue = sales.filter { it.date >= TimeUtils.getStartOfDay() }.sumOf { it.amount },
            todayProfit = sales.filter { it.date >= TimeUtils.getStartOfDay() }.sumOf { it.profit },
            totalOutstandingDebt = debts.filter { !it.isPaid }.sumOf { it.amount },
            recentSales = sales.sortedByDescending { it.date },
            customers = customers,
            inventoryItems = inventory,
            lowStockItems = inventory.filter { it.quantity > 0 && it.quantity <= it.reorderLevel },
            totalStockValue = inventory.sumOf { it.quantity * it.sellingPrice },
            analytics = DashboardAnalytics.from(sales, debts, inventory, expenseList, customers)
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DashboardUiState()
        )

    // Customer Actions
    fun insertCustomer(customer: Customer) = viewModelScope.launch {
        repository.insertCustomer(customer)
        syncEngine?.requestPush()
    }

    fun addCustomer(name: String, phone: String = "", email: String = "") = viewModelScope.launch {
        repository.insertCustomer(Customer(name = name, phone = phone, email = email))
        syncEngine?.requestPush()
    }

    // Callers that already have a persisted Customer (blank id would mean "not yet
    // saved") go to update; a blank id means the id needs generating on first insert.
    fun upsertCustomer(customer: Customer) = viewModelScope.launch {
        if (customer.id.isBlank()) {
            repository.insertCustomer(customer.copy(id = IdGenerator.newId()))
        } else {
            repository.updateCustomer(customer)
        }
        syncEngine?.requestPush()
    }

    fun deleteCustomer(customer: Customer) = viewModelScope.launch {
        repository.deleteCustomer(customer)
    }

    // Debt Actions
    fun insertDebt(debt: Debt) = viewModelScope.launch {
        repository.insertDebt(debt)
        syncEngine?.requestPush()
    }

    fun markDebtAsPaid(debtId: String) = viewModelScope.launch {
        repository.markDebtPaid(debtId)
        syncEngine?.requestPush()
    }

    // Inventory Actions
    // InventoryItemDialog is used for both Add and Edit; it passes a blank id
    // for a brand-new item (mirroring the old id == 0L convention), so a fresh
    // id is only generated here, at the moment we know it's really an insert.
    fun upsertInventoryItem(item: InventoryItem) = viewModelScope.launch {
        if (item.id.isBlank()) {
            val (myPhone, isOwner) = currentSession()
            // A worker's Add dialog never shows a cost field (see
            // InventoryItemDialog), so costPrice here is always the unset 0.0
            // default for a worker - that's exactly the case that needs an
            // owner's review. An owner creating the item already entered a
            // real cost, so it's reconciled immediately.
            repository.insertInventoryItem(
                item.copy(id = IdGenerator.newId(), recordedBy = myPhone, costReconciled = isOwner)
            )
        } else {
            // Editing an existing item always goes through the owner-only
            // cost field when isOwner (see InventoryItemDialog) - if this
            // save came from an owner, treat it as having reviewed the cost.
            val (_, isOwner) = currentSession()
            repository.updateInventoryItem(if (isOwner) item.copy(costReconciled = true) else item)
        }
        syncEngine?.requestPush()
    }

    fun addInventoryItem(
        name: String,
        quantity: Int,
        sellingPrice: Double,
        category: String = "",
        costPrice: Double = 0.0,
        reorderLevel: Int = 5,
        // Mirrors InventoryItemDialog's photo handling (see that file's doc
        // comment on InventoryItem.localImagePath) - this quick-add screen
        // now offers the same picker, so a photo taken here needs the same
        // two fields to make it into InventorySync.pushPending's upload step.
        localImagePath: String? = null,
        imagePendingUpload: Boolean = false
    ) = viewModelScope.launch {
        val (myPhone, isOwner) = currentSession()
        repository.insertInventoryItem(
            InventoryItem(
                name = name,
                quantity = quantity,
                sellingPrice = sellingPrice,
                category = category,
                costPrice = costPrice,
                reorderLevel = reorderLevel,
                recordedBy = myPhone,
                costReconciled = isOwner,
                localImagePath = localImagePath,
                imagePendingUpload = imagePendingUpload
            )
        )
        syncEngine?.requestPush()
    }

    fun deleteInventoryItem(item: InventoryItem) = viewModelScope.launch {
        repository.deleteInventoryItem(item)
    }

    fun getAdjustmentsForItem(itemId: String) = repository.getAdjustmentsForItem(itemId)

    // Incoming Stock - additive-only, available to workers and owners alike.
    fun receiveStock(itemId: String, quantity: Int, note: String? = null) = viewModelScope.launch {
        repository.receiveStock(itemId, quantity, note)
        syncEngine?.requestPush()
    }

    // Recount - owner-only correction after a physical count. The screen is
    // responsible for only exposing this to an owner and for requiring a note;
    // this function trusts its caller on both, same as the rest of this class.
    fun recountStock(itemId: String, newQuantity: Int, note: String) = viewModelScope.launch {
        repository.recountStock(itemId, newQuantity, note)
        syncEngine?.requestPush()
    }

    // Sale Actions
    // For a custom/service sale not tied to tracked inventory (inventoryItemId = null), profit stays 0
    // since there's no known cost basis. Pass an inventoryItemId to compute real profit and decrement stock.
    fun addSale(
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
    ) = viewModelScope.launch {
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
    fun addSaleLines(
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
    ) = viewModelScope.launch {
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

    fun deleteSale(sale: Sale) = viewModelScope.launch {
        repository.deleteSale(sale)
    }

    // Expense Actions
    fun addExpense(
        description: String,
        amount: Double,
        category: String = "General",
        receiptNumber: String? = null,
        localReceiptPath: String? = null
    ) = viewModelScope.launch {
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

    fun deleteExpense(expense: Expense) = viewModelScope.launch {
        repository.deleteExpense(expense)
    }

    // Task Actions
    fun addTask(title: String, description: String? = null, priority: String = "Medium", dueDate: Long? = null) = viewModelScope.launch {
        repository.addTask(
            Task(
                title = title,
                description = description,
                priority = priority,
                dueDate = dueDate
            )
        )
        syncEngine?.requestPush()
    }

    fun completeTask(taskId: String) = viewModelScope.launch {
        repository.completeTask(taskId)
        syncEngine?.requestPush()
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        repository.deleteTask(task)
    }

    // Reconciliation Actions (owner-only; screen is responsible for gating)
    // costPricePerUnit is what the owner enters (matches how InventoryItem.
    // costPrice is entered everywhere else). Sale.costPriceSnapshot is
    // documented as the TOTAL cost basis (costPrice * quantity), and
    // Sale.amount is the total the customer paid for the whole line - so
    // profit is amount minus total cost, not a per-unit difference.
    fun reconcileSale(saleId: String, costPricePerUnit: Double) = viewModelScope.launch {
        val sale = unreconciledSales.value.find { it.id == saleId } ?: return@launch
        val totalCost = costPricePerUnit * sale.quantity
        val profit = sale.amount - totalCost
        repository.reconcileSaleFinancials(saleId, totalCost, profit)
        syncEngine?.requestPush()
    }

    fun reconcileInventoryCost(itemId: String, costPrice: Double) = viewModelScope.launch {
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
    fun editSaleCost(saleId: String, costPricePerUnit: Double) = viewModelScope.launch {
        val sale = sales.value.find { it.id == saleId } ?: return@launch
        val totalCost = costPricePerUnit * sale.quantity
        val profit = sale.amount - totalCost
        repository.reconcileSaleFinancials(saleId, totalCost, profit)
        syncEngine?.requestPush()
    }
}