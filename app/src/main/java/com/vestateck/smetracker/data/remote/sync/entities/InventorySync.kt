package com.vestateck.smetracker.data.remote.sync.entities

import android.net.Uri
import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.remote.model.InventoryCost
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.data.remote.model.RemoteInventoryItem
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

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
    private val externalScope: CoroutineScope,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    fun attachInventoryListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("inventory")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        try {
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
                                    // localImagePath only ever means something on the
                                    // device that actually has that file — never carry
                                    // it over from the remote doc (it doesn't have one).
                                    // Preserve whatever this device already had.
                                    localImagePath = existing?.localImagePath,
                                    imageUrl = remote.imageUrl.ifBlank { null },
                                    // Preserve rather than reset to false: if this
                                    // device has a photo mid-upload, a pull racing in
                                    // ahead of that upload's push shouldn't make
                                    // pushPending think there's nothing left to do.
                                    imagePendingUpload = existing?.imagePendingUpload ?: false,
                                    pendingSync = false
                                )
                            )
                        } catch (e: Exception) {
                            // One bad/out-of-order document shouldn't take down the rest
                            // of this snapshot batch — skipped here, picked up again on
                            // the next snapshot event for this document.
                        }
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
                        try {
                            val remote = change.document.toObject(InventoryCost::class.java)
                            inventoryDao.updateItemCostPrice(change.document.id, remote.costPrice)
                        } catch (e: Exception) {
                            // Same defensive pattern as the item listener above — this
                            // is a plain UPDATE with no FK, so failure here is unlikely,
                            // but keep the batch resilient to a single bad document.
                        }
                    }
                }
            }
    }

    suspend fun pushPending(businessId: String, myPhone: String, role: MemberRole) {
        val businessRef = firestore.collection("businesses").document(businessId)
        val pending = inventoryDao.getPendingSyncItems()
        for (item in pending) {
            try {
                // Upload a newly-picked photo before writing the Firestore doc,
                // so imageUrl below is never stale. Guarded by
                // imagePendingUpload rather than "imageUrl is blank" so an
                // item's 50th unrelated price edit doesn't re-upload the same
                // file it already uploaded on edit #2 — see InventoryItem's
                // doc comment on this field.
                var imageUrl = item.imageUrl ?: ""
                if (item.imagePendingUpload && item.localImagePath != null) {
                    val file = File(item.localImagePath)
                    if (file.exists()) {
                        val photoRef = storage.reference.child("businesses/$businessId/inventory/${item.id}.jpg")
                        photoRef.putFile(Uri.fromFile(file)).await()
                        imageUrl = photoRef.downloadUrl.await().toString()
                        inventoryDao.markImageUploaded(item.id, imageUrl)
                    }
                    // else: the picked file is gone (e.g. cache cleared) —
                    // nothing to upload; falls through with whatever imageUrl
                    // (if any) this item already had, and imagePendingUpload
                    // is left set so this isn't silently swallowed — it'll
                    // just keep retrying (and keep skipping) until the user
                    // picks a new photo, same as any other pushPending retry.
                }

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
                            recordedBy = item.recordedBy.ifBlank { myPhone },
                            imageUrl = imageUrl
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