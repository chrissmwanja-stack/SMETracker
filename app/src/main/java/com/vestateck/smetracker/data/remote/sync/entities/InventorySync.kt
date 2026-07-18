package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.remote.model.InventoryCost
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.data.remote.model.RemoteInventoryItem
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Inventory (+ InventoryCost). Extracted from SyncEngine — same owner/worker
 * split as SaleSync, applied to cost price instead of profit:
 *
 * - inventoryCosts is owner-write-only. A worker's locally-entered costPrice
 *   (if the UI even lets them enter one) stays local-only and gets
 *   overwritten back to whatever's authoritative remotely the next time
 *   this item is re-pulled.
 * - Pulling RemoteInventoryItem never touches the locally-merged costPrice
 *   column (updateItemCostPrice does that separately), so the two listeners
 *   can arrive in either order without clobbering each other.
 */
class InventorySync(
    private val inventoryDao: InventoryDao,
    private val firestore: FirebaseFirestore,
    private val externalScope: CoroutineScope
) {
    fun attachInventoryListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("inventory")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(RemoteInventoryItem::class.java)
                        // RemoteInventoryItem never carries costPrice — preserve
                        // whatever's locally known (see updateItemCostPrice).
                        val existing = inventoryDao.getItemById(remote.id)
                        // Same reasoning as SaleSync's financialsReconciled:
                        // existing == null means this item is new to this device (came
                        // from someone else's device), so its cost is still the unset
                        // default and needs an owner's review.
                        val costReconciled = existing?.costReconciled ?: false
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
                                recordedBy = remote.recordedBy,
                                costReconciled = costReconciled,
                                pendingSync = false
                            )
                        )
                    }
                }
            }
    }

    fun attachInventoryCostListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("inventoryCosts")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        val remote = change.document.toObject(InventoryCost::class.java)
                        inventoryDao.updateItemCostPrice(change.document.id, remote.costPrice)
                    }
                }
            }
    }

    suspend fun pushPending(businessId: String, myPhone: String, role: MemberRole) {
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
                            updatedAt = item.updatedAt,
                            // Same reasoning as SaleSync's recordedBy — keep the
                            // original creator across re-pushes (e.g. after an owner
                            // reconciles the cost), only fill in myPhone for a
                            // brand-new local-only row.
                            recordedBy = item.recordedBy.ifBlank { myPhone }
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
}