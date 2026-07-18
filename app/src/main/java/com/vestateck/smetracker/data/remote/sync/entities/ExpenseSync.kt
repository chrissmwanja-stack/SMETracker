package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.remote.model.ExpenseStatus
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.data.remote.model.RemoteExpense
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Role-aware on both read and write — see RemoteExpense.kt's access model.
 * Extracted from SyncEngine.
 */
class ExpenseSync(
    private val smeDao: SMEDao,
    private val firestore: FirebaseFirestore,
    private val externalScope: CoroutineScope
) {
    fun attachListener(
        businessRef: DocumentReference,
        role: MemberRole?,
        myPhone: String?
    ): ListenerRegistration {
        // A worker's read access is scoped to recordedBy == their own phone at
        // the rules level, and Firestore requires the QUERY itself to be
        // provably scoped the same way or it denies the whole listener — an
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
                // (required by the create rule). Owner entries auto-approve —
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
                            approvedAt = approvedAt
                        )
                    ).await()

                smeDao.clearExpensePendingSync(expense.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }
}