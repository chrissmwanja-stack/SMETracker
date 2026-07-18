package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.entities.Task
import com.vestateck.smetracker.data.remote.model.RemoteTask
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * No worker/owner split — shared task list, same as Customer. Extracted
 * from SyncEngine.
 */
class TaskSync(
    private val smeDao: SMEDao,
    private val firestore: FirebaseFirestore,
    private val externalScope: CoroutineScope
) {
    fun attachListener(businessRef: DocumentReference): ListenerRegistration {
        return businessRef.collection("tasks")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                externalScope.launch(Dispatchers.IO) {
                    for (change in snapshot.documentChanges) {
                        if (change.type == DocumentChange.Type.REMOVED) continue
                        try {
                            val remote = change.document.toObject(RemoteTask::class.java)
                            smeDao.insertTask(
                                Task(
                                    id = remote.id,
                                    title = remote.title,
                                    description = remote.description,
                                    priority = remote.priority,
                                    dueDate = remote.dueDate,
                                    isCompleted = remote.isCompleted,
                                    completedDate = remote.completedDate,
                                    createdDate = remote.createdDate,
                                    pendingSync = false
                                )
                            )
                        } catch (e: Exception) {
                            // No FK on Task today, but same defensive pattern as the
                            // other *Sync listeners.
                        }
                    }
                }
            }
    }

    suspend fun pushPending(businessId: String) {
        val businessRef = firestore.collection("businesses").document(businessId)
        val pending = smeDao.getPendingSyncTasks()
        for (task in pending) {
            try {
                businessRef.collection("tasks").document(task.id)
                    .set(
                        RemoteTask(
                            id = task.id,
                            title = task.title,
                            description = task.description,
                            priority = task.priority,
                            dueDate = task.dueDate,
                            isCompleted = task.isCompleted,
                            completedDate = task.completedDate,
                            createdDate = task.createdDate
                        )
                    ).await()

                smeDao.clearTaskPendingSync(task.id)
            } catch (e: Exception) {
                // Left as pendingSync = true — picked up again on the next requestPush().
            }
        }
    }
}