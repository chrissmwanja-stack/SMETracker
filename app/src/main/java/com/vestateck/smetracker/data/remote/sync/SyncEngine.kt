package com.vestateck.smetracker.data.remote.sync

import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.sync.entities.CustomerSync
import com.vestateck.smetracker.data.remote.sync.entities.DebtSync
import com.vestateck.smetracker.data.remote.sync.entities.ExpenseSync
import com.vestateck.smetracker.data.remote.sync.entities.InventorySync
import com.vestateck.smetracker.data.remote.sync.entities.SaleSync
import com.vestateck.smetracker.data.remote.sync.entities.StockAdjustmentSync
import com.vestateck.smetracker.data.remote.sync.entities.TaskSync
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import android.content.Context
import com.vestateck.smetracker.notifications.ReconciliationNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
 * This class is the orchestrator only: session/lifecycle handling and the
 * reconciliation-pending notifier live here. The actual per-entity read/write
 * logic lives in data/remote/sync/entities/ — one class per entity, each
 * documenting its own owner/worker access rules (which mirror
 * firestore.rules exactly). See each *Sync class's doc comment for details
 * specific to that entity; the shared themes are:
 *
 * - saleFinancials and inventoryCosts are owner-write-only (SaleSync,
 *   InventorySync). The financials/cost half of a worker-recorded sale or
 *   inventory item simply doesn't exist in Firestore until an owner
 *   reconciles it via the Reconciliation screen.
 * - A worker's expenses listener is scoped with .whereEqualTo("recordedBy",
 *   myPhone) (ExpenseSync), since Firestore requires list queries to be
 *   provably restricted the same way the security rule restricts them.
 *
 * Known limitations, carried over from the original proof and still true
 * for every entity here:
 * - Deletions are not synced in either direction.
 * - No conflict resolution — last write wins, whichever side writes last.
 * - Push only runs when requested, not on a timer or connectivity change.
 * - The reconciliation-pending notification (see ReconciliationNotifier) is
 *   local only — it requires this SyncEngine's process to be alive and
 *   collecting. It does NOT wake the app if killed; that would need FCM plus
 *   a Cloud Function watching the sales/inventory collections server-side,
 *   which doesn't exist yet.
 */
class SyncEngine(
    private val smeDao: SMEDao,
    private val inventoryDao: InventoryDao,
    private val sessionManager: SessionManager,
    private val externalScope: CoroutineScope,
    // Application context only — used solely to post/cancel the local
    // reconciliation-pending notification (see ReconciliationNotifier).
    private val context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private var listenerJob: Job? = null
    private var notifierJob: Job? = null
    private val activeListeners = mutableListOf<ListenerRegistration>()

    private val customerSync = CustomerSync(smeDao, firestore, externalScope)
    private val saleSync = SaleSync(smeDao, inventoryDao, firestore, externalScope)
    private val debtSync = DebtSync(smeDao, firestore, externalScope)
    private val inventorySync = InventorySync(inventoryDao, firestore, externalScope)
    private val stockAdjustmentSync = StockAdjustmentSync(inventoryDao, firestore, externalScope)
    private val expenseSync = ExpenseSync(smeDao, firestore, externalScope)
    private val taskSync = TaskSync(smeDao, firestore, externalScope)

    /** Call once, e.g. from MainActivity, after the user has entered a business. */
    fun start() {
        if (listenerJob != null) return
        ReconciliationNotifier.ensureChannel(context)
        listenerJob = externalScope.launch(Dispatchers.IO) {
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
        notifierJob?.cancel()
        notifierJob = null
        ReconciliationNotifier.clear(context)
    }

    /** Fire-and-forget from the ViewModel after any local mutation, any entity. */
    fun requestPush() {
        externalScope.launch(Dispatchers.IO) { pushAllPending() }
    }

    private fun attachListeners(businessId: String, role: MemberRole?, myPhone: String?) {
        activeListeners.forEach { it.remove() }
        activeListeners.clear()

        val businessRef: DocumentReference = firestore.collection("businesses").document(businessId)

        activeListeners += customerSync.attachListener(businessRef)
        activeListeners += saleSync.attachSaleListener(businessRef)
        activeListeners += debtSync.attachListener(businessRef)
        activeListeners += inventorySync.attachInventoryListener(businessRef)
        activeListeners += stockAdjustmentSync.attachListener(businessRef)
        activeListeners += expenseSync.attachListener(businessRef, role, myPhone)
        activeListeners += taskSync.attachListener(businessRef)

        // Owner-only collections — a worker's device never attaches these at
        // all, matching what the security rules already deny them.
        if (role == MemberRole.OWNER) {
            activeListeners += saleSync.attachSaleFinancialsListener(businessRef)
            activeListeners += inventorySync.attachInventoryCostListener(businessRef)
        }

        // Owner-only local notification for the reconciliation queue — see
        // ReconciliationNotifier's class doc. Re-attaching listeners (e.g. on
        // re-login) restarts this cleanly rather than stacking collectors.
        notifierJob?.cancel()
        notifierJob = if (role == MemberRole.OWNER) {
            externalScope.launch(Dispatchers.IO) {
                combine(
                    smeDao.getUnreconciledSalesCount(),
                    inventoryDao.getUnreconciledItemsCount()
                ) { sales, items -> (sales + items).toInt() }
                    .distinctUntilChanged()
                    .collect { count -> ReconciliationNotifier.notifyPending(context, count) }
            }
        } else null

        // Initial catch-up push in case there are locally-pending writes from
        // before this business was attached (e.g. offline signup/edits).
        externalScope.launch(Dispatchers.IO) { pushAllPending() }
    }

    private suspend fun pushAllPending() {
        val session = sessionManager.sessionState.first()
        val businessId = session.businessId ?: return
        val myPhone = session.phoneNumberE164 ?: return
        val role = session.role ?: return

        customerSync.pushPending(businessId)
        saleSync.pushPending(businessId, myPhone, role)
        debtSync.pushPending(businessId, myPhone)
        inventorySync.pushPending(businessId, myPhone, role)
        stockAdjustmentSync.pushPending(businessId, myPhone)
        expenseSync.pushPending(businessId, myPhone, role)
        taskSync.pushPending(businessId)
    }
}