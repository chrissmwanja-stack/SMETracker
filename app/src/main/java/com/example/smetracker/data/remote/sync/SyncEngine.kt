package com.example.smetracker.data.remote.sync

import com.example.smetracker.data.dao.SMEDao
import com.example.smetracker.data.entities.Customer
import com.example.smetracker.data.remote.auth.SessionManager
import com.example.smetracker.data.remote.model.RemoteCustomer
import com.google.firebase.firestore.DocumentChange
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
 * Phase 3 proof: syncs the `customers` collection only, end-to-end, in both
 * directions. This is deliberately scoped to Customer first — simplest entity,
 * no owner/worker financial split, no role-based query branching — before the
 * same read/write pattern gets extended to Sale/Expense/InventoryItem/Debt/Task.
 *
 * Read path: one Firestore snapshot listener on the current business's
 * `customers` collection. Every added/modified doc is upserted into Room with
 * pendingSync = false (it just came FROM the server, so it's already synced).
 *
 * Write path: `requestPush()` is called by the ViewModel right after a local
 * mutation. It reads every locally-pending customer and pushes each to
 * Firestore, clearing pendingSync on success. Kept simple by design for this
 * proof — no retry/backoff, no WorkManager, no offline queueing beyond what
 * pendingSync already gives us (a failed push just gets retried on the next
 * requestPush() call, e.g. the next edit or app restart).
 *
 * Known limitations, left out of scope for this proof on purpose:
 * - Deletions are not synced in either direction yet.
 * - No conflict resolution — last write wins, whichever side writes last.
 * - Push only runs when requested, not on a timer or connectivity change.
 */
class SyncEngine(
    private val smeDao: SMEDao,
    private val sessionManager: SessionManager,
    private val externalScope: CoroutineScope,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private var listenerJob: Job? = null
    private var customerListener: ListenerRegistration? = null

    /** Call once, e.g. from MainActivity, after the user has entered a business. */
    fun start() {
        if (listenerJob != null) return
        listenerJob = externalScope.launch {
            sessionManager.sessionState
                .filter { it.hasBusiness }
                .distinctUntilChangedBy { it.businessId }
                .collect { session ->
                    session.businessId?.let { attachCustomerListener(it) }
                }
        }
    }

    /** Call on sign-out so we stop listening on behalf of the wrong business. */
    fun stop() {
        customerListener?.remove()
        customerListener = null
        listenerJob?.cancel()
        listenerJob = null
    }

    private fun attachCustomerListener(businessId: String) {
        customerListener?.remove()
        customerListener = firestore.collection("businesses").document(businessId)
            .collection("customers")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue // deletions: out of scope for now
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
        // Initial catch-up push in case there are locally-pending writes from
        // before this business was attached (e.g. offline signup).
        externalScope.launch { pushPendingCustomers() }
    }

    /** Fire-and-forget from the ViewModel after any local customer write. */
    fun requestPush() {
        externalScope.launch { pushPendingCustomers() }
    }

    private suspend fun pushPendingCustomers() {
        val businessId = sessionManager.sessionState.first().businessId ?: return
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
                // Left as pendingSync = true — picked up again on the next
                // requestPush() (next edit, or next attachCustomerListener catch-up).
            }
        }
    }
}