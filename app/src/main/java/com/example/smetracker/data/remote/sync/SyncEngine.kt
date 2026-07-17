package com.example.smetracker.data.remote.sync

import com.example.smetracker.data.dao.InventoryDao
import com.example.smetracker.data.dao.SMEDao
import com.example.smetracker.data.entities.Customer
import com.example.smetracker.data.entities.Debt
import com.example.smetracker.data.entities.Expense
import com.example.smetracker.data.entities.InventoryItem
import com.example.smetracker.data.entities.PaymentMethod
import com.example.smetracker.data.entities.Sale
import com.example.smetracker.data.entities.StockAdjustment
import com.example.smetracker.data.entities.StockAdjustmentReason
import com.example.smetracker.data.entities.Task
import com.example.smetracker.data.remote.auth.MemberRole
import com.example.smetracker.data.remote.auth.SessionManager
import com.example.smetracker.data.remote.model.ExpenseStatus
import com.example.smetracker.data.remote.model.InventoryCost
import com.example.smetracker.data.remote.model.RemoteCustomer
import com.example.smetracker.data.remote.model.RemoteDebt
import com.example.smetracker.data.remote.model.RemoteExpense
import com.example.smetracker.data.remote.model.RemoteInventoryItem
import com.example.smetracker.data.remote.model.RemoteSale
import com.example.smetracker.data.remote.model.RemoteStockAdjustment
import com.example.smetracker.data.remote.model.RemoteTask
import com.example.smetracker.data.remote.model.SaleFinancials
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Syncs Customer, Sale (+ SaleFinancials), Debt, InventoryItem (+ InventoryCost),
 * Expense, and Task between Room and Firestore, in both directions. Extends the
 * original Phase 3 Customer-only proof to every entity, following the same
 * read/write pattern throughout:
 *
 * Read path: one Firestore snapshot listener per top-level collection this
 * session is allowed to read. Every added/modified doc is upserted into Room
 * with pendingSync = false (it just came FROM the server).
 *
 * Write path: requestPush() is called by the ViewModel after any local
 * mutation. It reads every locally-pending row across every entity and
 * pushes each to Firestore, clearing pendingSync on success. Still no
 * retry/backoff/WorkManager by design — a failed push just gets retried on
 * the next requestPush() (next edit, or next attachListeners() catch-up).
 *
 * The owner/worker split (Sale/SaleFinancials, InventoryItem/InventoryCost,
 * and Expense's approval fields) mirrors firestore.rules exactly:
 *
 * - saleFinancials and inventoryCosts are owner-write-only. A worker's push
 *   never attempts those writes (they'd always be denied); the financials
 *   half of a worker-recorded sale or inventory item simply doesn't exist in
 *   Firestore until an owner reconciles it. Nothing currently does that
 *   reconciliation — it's a real gap, not yet built.
 * - Pulling RemoteSale/RemoteInventoryItem never touches the locally-merged
 *   cost/profit columns (updateSaleFinancials/updateItemCostPrice do that
 *   separately), so the two listeners for a given entity can arrive in
 *   either order without clobbering each other.
 * - A worker's expenses listener is scoped with .whereEqualTo("recordedBy",
 *   myPhone) because Firestore requires list queries to be provably
 *   restricted the same way the security rule restricts them — an
 *   unfiltered query from a worker would be denied outright, not
 *   silently filtered. The phone number used for that scoping is threaded
 *   in from the session at attach time (see attachListeners) rather than
 *   read from a side-channel cached field, so the very first attach for a
 *   worker is scoped correctly instead of only being scoped correctly from
 *   the second attach onward.
 * - Expense push status: a worker's submission always pushes as PENDING
 *   with no approval fields (required by the create rule). An owner's own
 *   entry auto-pushes as APPROVED, since there's no approve/reject UI yet
 *   for an owner to review their own submissions against.
 *
 * Known limitations, carried over from the original proof and still true
 * for every entity here:
 * - Deletions are not synced in either direction.
 * - No conflict resolution — last write wins, whichever side writes last.
 * - Push only runs when requested, not on a timer or connectivity change.
 */
class SyncEngine(
    private val smeDao: SMEDao,
    private val inventoryDao: InventoryDao,
    private val sessionManager: SessionManager,
    private val externalScope: CoroutineScope,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private var listenerJob: Job? = null
    private val activeListeners = mutableListOf<ListenerRegistration>()

    /** Call once, e.g. from MainActivity, after the user has entered a business. */
    fun start() {
        if (listenerJob != null) return
        listenerJob = externalScope.launch {
            sessionManager.sessionState
                .filter { it.hasBusiness }
                .distinctUntilChangedBy { it.businessId }
                .collect { session ->
                    val businessId = session.businessId ?: return@collect
                    attachListeners(businessId, session.role, session.phoneNumberE164)
                }
        }
    }

    /** Call on sign-out so we stop listening on behalf of the wrong business. */
    fun stop() {
        activeListeners.forEach { it.remove() }
        activeListeners.clear()
        listenerJob?.cancel()
        listenerJob = null
    }

    /** Fire-and-forget from the ViewModel after any local mutation, any entity. */
    fun requestPush() {
        externalScope.launch { pushAllPending() }
    }

    private fun attachListeners(businessId: String, role: MemberRole?, myPhone: String?) {
        activeListeners.forEach { it.remove() }
        activeListeners.clear()

        val businessRef = firestore.collection("businesses").document(businessId)

        activeListeners += attachCustomerListener(businessRef)
        activeListeners += attachSaleListener(businessRef)
        activeListeners += attachDebtListener(businessRef)
        activeListeners += attachInventoryListener(businessRef)
        activeListeners += attachStockAdjustmentListener(businessRef)
        activeListeners += attachExpenseListener(businessRef, role, myPhone)
        activeListeners += attachTaskListener(businessRef)

        // Owner-only collections — a worker's device never attaches these at
        // all, matching what the security rules already deny them.
        if (role == MemberRole.OWNER) {
            activeListeners += attachSaleFinancialsListener(businessRef)
            activeListeners += attachInventoryCostListener(businessRef)
        }

        // Initial catch-up push in case there are locally-pending writes from
        // before this business was attached (e.g. offline signup/edits).
        externalScope.launch { pushAllPending() }
    }

    private suspend fun pushAllPending() {
        val session = sessionManager.sessionState.first()
        val businessId = session.businessId ?: return
        val myPhone = session.phoneNumberE164 ?: return
        val role = session.role ?: return

        pushPendingCustomers(businessId)
        pushPendingSales(businessId, myPhone, role)
        pushPendingDebts(businessId, myPhone)
        pushPendingInventoryItems(businessId, role)
        pushPendingStockAdjustments(businessId, myPhone)
        pushPendingExpenses(businessId, myPhone, role)
        pushPendingTasks(businessId)
    }

    // ───────────────────────── Customers ─────────────────────────
    // No worker/owner split (see RemoteCustomer.kt) — unchanged from the
    // original Phase 3 proof.

    private fun attachCustomerListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("customers")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(RemoteCustomer::class.java)
                        smeDao.insertCustomer(
                            Customer(
                                id = remote.id,
                                name = remote.name,
                                phone = remote.phone,
                                email = remote.email,
                                createdAt = remote.createdAt,
                                pendingSync = false
                            )
                        )
                    }
                }
            }
    }

    private suspend fun pushPendingCustomers(businessId: String) {
        val pending = smeDao.getPendingSyncCustomers()
        for (customer in pending) {
            try {
                firestore.collection("businesses").document(businessId)
                    .collection("customers").document(customer.id)
                    .set(
                        RemoteCustomer(
                            id = customer.id,
                            name = customer.name,
                            phone = customer.phone,
                            email = customer.email,
                            createdAt = customer.createdAt
                        )
                    )
                    .await()
                smeDao.clearCustomerPendingSync(customer.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }

    // ───────────────────────── Sales (+ SaleFinancials) ─────────────────────────

    private fun attachSaleListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("sales")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(RemoteSale::class.java)
                        // RemoteSale never carries costPrice/profit — preserve whatever
                        // the saleFinancials listener already merged in (or hasn't yet).
                        val existing = smeDao.getSaleById(remote.id)
                        smeDao.insertSale(
                            Sale(
                                id = remote.id,
                                customerId = remote.customerId,
                                customerName = remote.customerName,
                                description = remote.description,
                                amount = remote.amount,
                                profit = existing?.profit ?: 0.0,
                                costPriceSnapshot = existing?.costPriceSnapshot ?: 0.0,
                                inventoryItemId = remote.inventoryItemId,
                                quantity = remote.quantity,
                                date = remote.date,
                                paymentMethod = runCatching { PaymentMethod.valueOf(remote.paymentMethod) }
                                    .getOrDefault(PaymentMethod.CASH),
                                pendingSync = false
                            )
                        )
                    }
                }
            }
    }

    private fun attachSaleFinancialsListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("saleFinancials")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(SaleFinancials::class.java)
                        // Document ID (== the linked sale's ID) is the source of truth
                        // here, not remote.saleId, for the same reason RemoteSale/
                        // RemoteInventoryItem use @DocumentId rather than trusting a
                        // plain field.
                        smeDao.updateSaleFinancials(change.document.id, remote.costPrice, remote.profit)
                    }
                }
            }
    }

    private suspend fun pushPendingSales(businessId: String, myPhone: String, role: MemberRole) {
        val businessRef = firestore.collection("businesses").document(businessId)
        val pending = smeDao.getPendingSyncSales()
        for (sale in pending) {
            try {
                businessRef.collection("sales").document(sale.id)
                    .set(
                        RemoteSale(
                            id = sale.id,
                            customerId = sale.customerId,
                            customerName = sale.customerName,
                            description = sale.description,
                            amount = sale.amount,
                            inventoryItemId = sale.inventoryItemId,
                            quantity = sale.quantity,
                            date = sale.date,
                            paymentMethod = sale.paymentMethod.name,
                            recordedBy = myPhone
                        )
                    ).await()

                // saleFinancials is owner-write-only — see class doc. A worker's
                // push never attempts this (it would always be denied).
                if (role == MemberRole.OWNER) {
                    businessRef.collection("saleFinancials").document(sale.id)
                        .set(SaleFinancials(saleId = sale.id, costPrice = sale.costPriceSnapshot, profit = sale.profit))
                        .await()
                }

                smeDao.clearSalePendingSync(sale.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }

    // ───────────────────────── Debts ─────────────────────────
    // No worker/owner split (see RemoteDebt.kt). recordedBy is filled in at
    // push time only — nothing in the UI needs it locally yet, so it's not
    // persisted to the Debt entity.

    private fun attachDebtListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("debts")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(RemoteDebt::class.java)
                        smeDao.insertDebt(
                            Debt(
                                id = remote.id,
                                customerId = remote.customerId,
                                customerName = remote.customerName,
                                description = remote.description,
                                amount = remote.amount,
                                isPaid = remote.isPaid,
                                dueDate = remote.dueDate,
                                date = remote.date,
                                pendingSync = false
                            )
                        )
                    }
                }
            }
    }

    private suspend fun pushPendingDebts(businessId: String, myPhone: String) {
        val pending = smeDao.getPendingSyncDebts()
        for (debt in pending) {
            try {
                firestore.collection("businesses").document(businessId)
                    .collection("debts").document(debt.id)
                    .set(
                        RemoteDebt(
                            id = debt.id,
                            customerId = debt.customerId,
                            customerName = debt.customerName,
                            description = debt.description,
                            amount = debt.amount,
                            isPaid = debt.isPaid,
                            dueDate = debt.dueDate,
                            date = debt.date,
                            recordedBy = myPhone
                        )
                    ).await()
                smeDao.clearDebtPendingSync(debt.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }

    // ───────────────────────── Inventory (+ InventoryCost) ─────────────────────────

    private fun attachInventoryListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("inventory")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(RemoteInventoryItem::class.java)
                        // RemoteInventoryItem never carries costPrice — preserve
                        // whatever's locally known (see updateItemCostPrice).
                        val existing = inventoryDao.getItemById(remote.id)
                        inventoryDao.insert(
                            InventoryItem(
                                id = remote.id,
                                name = remote.name,
                                category = remote.category,
                                quantity = remote.quantity,
                                reorderLevel = remote.reorderLevel,
                                costPrice = existing?.costPrice ?: 0.0,
                                sellingPrice = remote.sellingPrice,
                                updatedAt = remote.updatedAt,
                                pendingSync = false
                            )
                        )
                    }
                }
            }
    }

    private fun attachInventoryCostListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("inventoryCosts")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(InventoryCost::class.java)
                        inventoryDao.updateItemCostPrice(change.document.id, remote.costPrice)
                    }
                }
            }
    }

    private suspend fun pushPendingInventoryItems(businessId: String, role: MemberRole) {
        val businessRef = firestore.collection("businesses").document(businessId)
        val pending = inventoryDao.getPendingSyncItems()
        for (item in pending) {
            try {
                businessRef.collection("inventory").document(item.id)
                    .set(
                        RemoteInventoryItem(
                            id = item.id,
                            name = item.name,
                            category = item.category,
                            quantity = item.quantity,
                            reorderLevel = item.reorderLevel,
                            sellingPrice = item.sellingPrice,
                            updatedAt = item.updatedAt
                        )
                    ).await()

                // inventoryCosts is owner-write-only — see class doc. A worker's
                // locally-entered costPrice (if the UI even lets them enter one —
                // Phase 5 should eventually prevent that) stays local-only and
                // gets overwritten back to whatever's authoritative remotely the
                // next time this item is re-pulled.
                if (role == MemberRole.OWNER) {
                    businessRef.collection("inventoryCosts").document(item.id)
                        .set(InventoryCost(itemId = item.id, costPrice = item.costPrice))
                        .await()
                }

                inventoryDao.clearItemPendingSync(item.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }

    // ───────────────────────── Stock Adjustments ─────────────────────────
    // No worker/owner split on read — see RemoteStockAdjustment.kt's class doc.
    // This is the audit log behind Incoming Stock / Recount (InventoryScreen).

    // Same FK-ordering caveat as Sale/Customer elsewhere in this file:
    // StockAdjustment.itemId is a foreign key to inventory_items, so if this
    // listener's first batch arrives before the inventory listener's does (no
    // ordering guarantee between two independent snapshot listeners), the
    // insert throws and that adjustment is silently dropped rather than
    // retried — not new here, just carried over from an existing gap.
    private fun attachStockAdjustmentListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("stockAdjustments")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(RemoteStockAdjustment::class.java)
                        inventoryDao.insertAdjustmentFromRemote(
                            StockAdjustment(
                                id = remote.id,
                                itemId = remote.itemId,
                                delta = remote.delta,
                                reason = runCatching { StockAdjustmentReason.valueOf(remote.reason) }
                                    .getOrDefault(StockAdjustmentReason.INCOMING),
                                note = remote.note,
                                createdAt = remote.createdAt,
                                recordedBy = remote.recordedBy,
                                pendingSync = false
                            )
                        )
                    }
                }
            }
    }

    private suspend fun pushPendingStockAdjustments(businessId: String, myPhone: String) {
        val businessRef = firestore.collection("businesses").document(businessId)
        val pending = inventoryDao.getPendingSyncAdjustments()
        for (adjustment in pending) {
            try {
                businessRef.collection("stockAdjustments").document(adjustment.id)
                    .set(
                        RemoteStockAdjustment(
                            id = adjustment.id,
                            itemId = adjustment.itemId,
                            delta = adjustment.delta,
                            reason = adjustment.reason.name,
                            note = adjustment.note,
                            createdAt = adjustment.createdAt,
                            recordedBy = myPhone
                        )
                    ).await()

                inventoryDao.clearAdjustmentPendingSync(adjustment.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }

    // ───────────────────────── Expenses ─────────────────────────
    // Role-aware on both read and write — see RemoteExpense.kt's access model.

    private fun attachExpenseListener(
        businessRef: DocumentReference,
        role: MemberRole?,
        myPhone: String?
    ): ListenerRegistration {
        // A worker's read access is scoped to recordedBy == their own phone at
        // the rules level, and Firestore requires the QUERY itself to be
        // provably scoped the same way or it denies the whole listener — an
        // unfiltered query from a worker isn't silently filtered, it's rejected.
        // myPhone is threaded in from the session at attach time (see
        // attachListeners/start), so this is scoped correctly on the very
        // first attach, not just from the second attach onward.
        val query = if (role == MemberRole.OWNER || myPhone == null) {
            businessRef.collection("expenses")
        } else {
            businessRef.collection("expenses").whereEqualTo("recordedBy", myPhone)
        }
        return query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            externalScope.launch {
                for (change in snapshot.documentChanges) {
                    if (change.type == DocumentChange.Type.REMOVED) continue
                    val remote = change.document.toObject(RemoteExpense::class.java)
                    smeDao.insertExpense(
                        Expense(
                            id = remote.id,
                            description = remote.description,
                            amount = remote.amount,
                            category = remote.category,
                            date = remote.date,
                            receiptNumber = remote.receiptNumber,
                            recordedBy = remote.recordedBy,
                            status = remote.status.name,
                            approvedBy = remote.approvedBy,
                            approvedAt = remote.approvedAt,
                            pendingSync = false
                        )
                    )
                }
            }
        }
    }

    private suspend fun pushPendingExpenses(businessId: String, myPhone: String, role: MemberRole) {
        val businessRef = firestore.collection("businesses").document(businessId)
        val pending = smeDao.getPendingSyncExpenses()
        for (expense in pending) {
            try {
                // Worker submissions must start PENDING with no approval fields
                // (required by the create rule). Owner entries auto-approve —
                // see class doc.
                val status: ExpenseStatus
                val approvedBy: String?
                val approvedAt: Long?
                if (role == MemberRole.OWNER) {
                    status = ExpenseStatus.APPROVED
                    approvedBy = myPhone
                    approvedAt = System.currentTimeMillis()
                } else {
                    status = ExpenseStatus.PENDING
                    approvedBy = null
                    approvedAt = null
                }

                businessRef.collection("expenses").document(expense.id)
                    .set(
                        RemoteExpense(
                            id = expense.id,
                            description = expense.description,
                            amount = expense.amount,
                            category = expense.category,
                            date = expense.date,
                            receiptNumber = expense.receiptNumber,
                            recordedBy = myPhone,
                            status = status,
                            approvedBy = approvedBy,
                            approvedAt = approvedAt
                        )
                    ).await()

                smeDao.clearExpensePendingSync(expense.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }

    // ───────────────────────── Tasks ─────────────────────────
    // No worker/owner split — shared task list, same as Customer.

    private fun attachTaskListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("tasks")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(RemoteTask::class.java)
                        smeDao.insertTask(
                            Task(
                                id = remote.id,
                                title = remote.title,
                                description = remote.description,
                                priority = remote.priority,
                                dueDate = remote.dueDate,
                                isCompleted = remote.isCompleted,
                                completedDate = remote.completedDate,
                                createdDate = remote.createdDate,
                                pendingSync = false
                            )
                        )
                    }
                }
            }
    }

    private suspend fun pushPendingTasks(businessId: String) {
        val businessRef = firestore.collection("businesses").document(businessId)
        val pending = smeDao.getPendingSyncTasks()
        for (task in pending) {
            try {
                businessRef.collection("tasks").document(task.id)
                    .set(
                        RemoteTask(
                            id = task.id,
                            title = task.title,
                            description = task.description,
                            priority = task.priority,
                            dueDate = task.dueDate,
                            isCompleted = task.isCompleted,
                            completedDate = task.completedDate,
                            createdDate = task.createdDate
                        )
                    ).await()

                smeDao.clearTaskPendingSync(task.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }
}