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
                            recordedBy = myPhone
                        )
                    ).await()
                smeDao.clearDebtPendingSync(debt.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }
}