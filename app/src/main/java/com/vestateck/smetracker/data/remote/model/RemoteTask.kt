package com.example.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

// Firestore path: businesses/{businessId}/tasks/{taskId}
// No worker/owner split — shared task list, both roles read/write freely.
data class RemoteTask(
    @DocumentId val id: String = "",
    val title: String = "",
    val description: String? = null,
    val priority: String = "Medium",
    val dueDate: Long? = null,
    val isCompleted: Boolean = false,
    val completedDate: Long? = null,
    val createdDate: Long = System.currentTimeMillis()
)