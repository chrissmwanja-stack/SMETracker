package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.entities.StockAdjustment
import com.vestateck.smetracker.data.entities.StockAdjustmentReason
import com.vestateck.smetracker.data.remote.model.RemoteStockAdjustment
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * No worker/owner split on read — see RemoteStockAdjustment.kt's class doc.
 * This is the audit log behind Incoming Stock / Recount (InventoryScreen).
 * Extracted from SyncEngine.
 *
 * Same FK-ordering caveat as Sale/Customer elsewhere: StockAdjustment.itemId
 * is a foreign key to inventory_items, so if this listener's first batch
 * arrives before the inventory listener's does (no ordering guarantee
 * between two independent snapshot listeners), the insert throws and that
 * adjustment is silently dropped rather than retried — not new here, just
 * carried over from an existing gap.
 */
class StockAdjustmentSync(
    private val inventoryDao: InventoryDao,
    private val firestore: FirebaseFirestore,
    private val externalScope: CoroutineScope
) {
    fun attachListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("stockAdjustments")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
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

    suspend fun pushPending(businessId: String, myPhone: String) {
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
}