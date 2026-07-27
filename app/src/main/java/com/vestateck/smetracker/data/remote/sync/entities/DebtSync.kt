package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.remote.model.RemoteDebt
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * No worker/owner split (see RemoteDebt.kt). recordedBy is filled in at
 * push time only — nothing in the UI needs it locally yet, so it's not
 * persisted to the Debt entity. Extracted from SyncEngine.
 */
class DebtSync(
    private val smeDao: SMEDao,
    private val firestore: FirebaseFirestore,
    private val externalScope: CoroutineScope
) {
    fun attachListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("debts")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        try {
                            val remote = change.document.toObject(RemoteDebt::class.java)

                            // Skip if the local row has an unsynced change (e.g. a
                            // debt just marked paid offline) - see TaskSync's
                            // attachListener for why this matters.
                            val local = smeDao.getDebtById(remote.id)
                            if (local != null && local.pendingSync) continue

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
                                    pendingSync = false,
                                    isDeleted = remote.isDeleted
                                )
                            )
                        } catch (e: Exception) {
                            // customerId is a real FK to customers (see Debt.kt) - if
                            // this debt's snapshot arrives before its linked
                            // customer's does (no ordering guarantee between two
                            // independent listeners), the insert throws. Same race
                            // SaleSync/StockAdjustmentSync already guard against for
                            // their own FKs - this listener just didn't yet. Skip
                            // this doc rather than take down the rest of the batch
                            // (or crash the sync coroutine outright); it's retried on
                            // the next snapshot event for this document.
                        }
                    }
                }
            }
    }

    suspend fun pushPending(businessId: String, myPhone: String) {
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
                            recordedBy = myPhone,
                            isDeleted = debt.isDeleted
                        )
                    ).await()
                smeDao.clearDebtPendingSync(debt.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }
}