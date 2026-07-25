// viewmodel/actions/TaskActions.kt
package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Task
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository

/**
 * Task-domain mutations extracted out of SMEViewModel (Option A restructuring,
 * first of the planned per-domain delegate classes). No behavior change from
 * the original addTask/completeTask/deleteTask functions - same repository
 * calls, same requestPush() timing. SMEViewModel still owns pendingTasks
 * (the exposed StateFlow) and simply forwards these three calls here.
 */
class TaskActions(
    private val repository: SMERepository,
    private val syncEngine: SyncEngine?
) {
    suspend fun addTask(
        title: String,
        description: String? = null,
        priority: String = "Medium",
        dueDate: Long? = null
    ) {
        repository.addTask(
            Task(
                title = title,
                description = description,
                priority = priority,
                dueDate = dueDate
            )
        )
        syncEngine?.requestPush()
    }

    suspend fun completeTask(taskId: String) {
        repository.completeTask(taskId)
        syncEngine?.requestPush()
    }

    suspend fun deleteTask(task: Task) {
        repository.deleteTask(task)
        syncEngine?.requestPush()
    }
}