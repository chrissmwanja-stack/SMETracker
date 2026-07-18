package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.data.remote.model.RemoteSale
import com.vestateck.smetracker.data.remote.model.SaleFinancials
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Sales (+ SaleFinancials). Extracted from SyncEngine — see that class's
 * doc comment for the owner/worker split this follows, which mirrors
 * firestore.rules exactly:
 *
 * - saleFinancials is owner-write-only. A worker's push never attempts it
 *   (it would always be denied); the financials half of a worker-recorded
 *   sale simply doesn't exist in Firestore until an owner reconciles it via
 *   the Reconciliation screen (SMEViewModel.reconcileSale).
 * - Pulling RemoteSale never touches the locally-merged cost/profit columns
 *   (updateSaleFinancials does that separately), so the two listeners for a
 *   sale can arrive in either order without clobbering each other.
 */
class SaleSync(
    private val smeDao: SMEDao,
    private val firestore: FirebaseFirestore,
    private val externalScope: CoroutineScope
) {
    fun attachSaleListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("sales")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        try {
                            val remote = change.document.toObject(RemoteSale::class.java)
                            // RemoteSale never carries costPrice/profit — preserve whatever
                            // the saleFinancials listener already merged in (or hasn't yet).
                            val existing = smeDao.getSaleById(remote.id)
                            // existing == null means this sale is new to this device, i.e.
                            // it came from somewhere else (another team member). If that's
                            // true and it's tied to a tracked inventory item, its financials
                            // are unreviewed until an owner explicitly reconciles them (see
                            // the Reconciliation screen) — a device that creates its own
                            // sale already has it in Room before this listener ever fires,
                            // so `existing` won't be null for that case, and this branch is
                            // never reached for sales you recorded yourself.
                            val financialsReconciled = existing?.financialsReconciled
                                ?: (remote.inventoryItemId == null)
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
                                    recordedBy = remote.recordedBy,
                                    financialsReconciled = financialsReconciled,
                                    pendingSync = false
                                )
                            )
                        } catch (e: Exception) {
                            // customerId is a real FK to customers — if this sale's
                            // snapshot arrives before its linked customer's does (no
                            // ordering guarantee between two independent listeners),
                            // the insert throws. Skip this doc rather than take down
                            // the rest of the batch; it's retried on the next snapshot
                            // event for this document.
                        }
                    }
                }
            }
    }

    fun attachSaleFinancialsListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("saleFinancials")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        try {
                            val remote = change.document.toObject(SaleFinancials::class.java)
                            // Document ID (== the linked sale's ID) is the source of truth
                            // here, not remote.saleId, for the same reason RemoteSale/
                            // RemoteInventoryItem use @DocumentId rather than trusting a
                            // plain field.
                            smeDao.updateSaleFinancials(change.document.id, remote.costPrice, remote.profit)
                        } catch (e: Exception) {
                            // Same defensive pattern as the sale listener above.
                        }
                    }
                }
            }
    }

    suspend fun pushPending(businessId: String, myPhone: String, role: MemberRole) {
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
                            // A blank local recordedBy means this row was just created
                            // on this device and never round-tripped yet — fill in
                            // myPhone. A non-blank value (e.g. after an owner
                            // reconciles a worker's sale, which re-marks it
                            // pendingSync) is the ORIGINAL creator and must be kept,
                            // not overwritten with whoever is pushing right now.
                            recordedBy = sale.recordedBy.ifBlank { myPhone }
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
}