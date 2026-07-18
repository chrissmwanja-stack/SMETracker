package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.remote.model.RemoteCustomer
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * No worker/owner split (see RemoteCustomer.kt) — unchanged from the
 * original Phase 3 proof. Extracted from SyncEngine, same read/write pattern
 * documented there.
 */
class CustomerSync(
    private val smeDao: SMEDao,
    private val firestore: FirebaseFirestore,
    private val externalScope: CoroutineScope
) {
    fun attachListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("customers")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
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

    suspend fun pushPending(businessId: String) {
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
}