package com.vestateck.smetracker.data.remote.sync

import android.content.Context
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
 * Orchestrates bidirectional synchronization between local Room DB and Cloud Firestore.
 * Delegated entity sync managers carry out the actual entity reads and writes.
 */
class SyncEngine(
    private val smeDao: SMEDao,
    private val inventoryDao: InventoryDao,
    private val sessionManager: SessionManager,
    private val externalScope: CoroutineScope,
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

    fun stop() {
        activeListeners.forEach { it.remove() }
        activeListeners.clear()
        listenerJob?.cancel()
        listenerJob = null
        notifierJob?.cancel()
        notifierJob = null
        ReconciliationNotifier.clear(context)
    }

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

        if (role == MemberRole.OWNER) {
            activeListeners += saleSync.attachSaleFinancialsListener(businessRef)
            activeListeners += inventorySync.attachInventoryCostListener(businessRef)
        }

        notifierJob?.cancel()
        notifierJob = if (role == MemberRole.OWNER) {
            externalScope.launch(Dispatchers.IO) {
                combine(
                    smeDao.getUnreconciledSalesCount(),
                    inventoryDao.getUnreconciledItemsCount(),
                    inventoryDao.getOversoldItemsCount()
                ) { sales, items, oversold -> (sales + items + oversold).toInt() }
                    .distinctUntilChanged()
                    .collect { count -> ReconciliationNotifier.notifyPending(context, count) }
            }
        } else null

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