package com.vestateck.smetracker.data.remote.sync.entities

import android.net.Uri
import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.remote.model.ExpenseStatus
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.data.remote.model.RemoteExpense
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
 * Role-aware on both read and write - see RemoteExpense.kt's access model.
 * Extracted from SyncEngine. storage defaults to FirebaseStorage.getInstance()
 * so SyncEngine's existing instantiation (ExpenseSync(smeDao, firestore,
 * externalScope)) keeps compiling unchanged.
 */
class ExpenseSync(
    private val smeDao: SMEDao,
    private val firestore: FirebaseFirestore,
    private val externalScope: CoroutineScope,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    fun attachListener(
        businessRef: DocumentReference,
        role: MemberRole?,
        myPhone: String?
    ): ListenerRegistration {
        // A worker's read access is scoped to recordedBy == their own phone at
        // the rules level, and Firestore requires the QUERY itself to be
        // provably scoped the same way or it denies the whole listener - an
        // unfiltered query from a worker isn't silently filtered, it's rejected.
        // myPhone is threaded in from the session at attach time (see
        // SyncEngine.attachListeners/start), so this is scoped correctly on
        // the very first attach, not just from the second attach onward.
        val query = if (role == MemberRole.OWNER || myPhone == null) {
            businessRef.collection("expenses")
        } else {
            businessRef.collection("expenses").whereEqualTo("recordedBy", myPhone)
        }
        return query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            externalScope.launch(Dispatchers.IO) {
                for (change in snapshot.documentChanges) {
                    if (change.type == DocumentChange.Type.REMOVED) continue
                    val remote = change.document.toObject(RemoteExpense::class.java)
                    // Preserve this device's own local receipt file reference -
                    // a blind overwrite from the remote echo would wipe it the
                    // moment the uploading device receives its own write back
                    // through this same listener. receiptPendingUpload only
                    // clears once the remote doc actually carries a receiptUrl
                    // (i.e. the upload this device kicked off has landed);
                    // otherwise it stays true so pushPending() still retries it.
                    val existing = smeDao.getExpenseById(remote.id)
                    val stillPendingUpload = existing?.receiptPendingUpload == true && remote.receiptUrl == null
                    smeDao.insertExpense(
                        Expense(
                            id = remote.id,
                            description = remote.description,
                            amount = remote.amount,
                            category = remote.category,
                            date = remote.date,
                            receiptNumber = remote.receiptNumber,
                            recordedBy = remote.recordedBy,
                            status = remote.status.name,
                            approvedBy = remote.approvedBy,
                            approvedAt = remote.approvedAt,
                            localReceiptPath = existing?.localReceiptPath,
                            receiptUrl = remote.receiptUrl,
                            receiptPendingUpload = stillPendingUpload,
                            pendingSync = false
                        )
                    )
                }
            }
        }
    }

    suspend fun pushPending(businessId: String, myPhone: String, role: MemberRole) {
        val businessRef = firestore.collection("businesses").document(businessId)
        val pending = smeDao.getPendingSyncExpenses()
        for (expense in pending) {
            try {
                // Worker submissions must start PENDING with no approval fields
                // (required by the create rule). Owner entries auto-approve -
                // see class doc.
                val status: ExpenseStatus
                val approvedBy: String?
                val approvedAt: Long?
                if (role == MemberRole.OWNER) {
                    status = ExpenseStatus.APPROVED
                    approvedBy = myPhone
                    approvedAt = System.currentTimeMillis()
                } else {
                    status = ExpenseStatus.PENDING
                    approvedBy = null
                    approvedAt = null
                }

                // Upload a newly-picked receipt photo before writing the rest
                // of the expense, so the same Firestore write already carries
                // the final download URL instead of needing a second
                // round-trip through markReceiptUploaded afterward.
                var receiptUrl = expense.receiptUrl
                if (expense.receiptPendingUpload && expense.localReceiptPath != null) {
                    val file = File(expense.localReceiptPath)
                    if (file.exists()) {
                        val storageRef = storage.reference
                            .child("businesses/$businessId/expense_receipts/${expense.id}.jpg")
                        storageRef.putFile(Uri.fromFile(file)).await()
                        receiptUrl = storageRef.downloadUrl.await().toString()
                    }
                }

                businessRef.collection("expenses").document(expense.id)
                    .set(
                        RemoteExpense(
                            id = expense.id,
                            description = expense.description,
                            amount = expense.amount,
                            category = expense.category,
                            date = expense.date,
                            receiptNumber = expense.receiptNumber,
                            recordedBy = myPhone,
                            status = status,
                            approvedBy = approvedBy,
                            approvedAt = approvedAt,
                            receiptUrl = receiptUrl
                        )
                    ).await()

                if (receiptUrl != null && receiptUrl != expense.receiptUrl) {
                    smeDao.markReceiptUploaded(expense.id, receiptUrl)
                }
                smeDao.clearExpensePendingSync(expense.id)
            } catch (e: Exception) {
                // Left as pendingSync = true - picked up again on the next requestPush().
            }
        }
    }
}