package com.vestateck.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vestateck.smetracker.utils.IdGenerator

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey
    val id: String = IdGenerator.newId(),
    val title: String,
    val description: String? = null,
    val priority: String = "Medium",
    val dueDate: Long? = null,
    val isCompleted: Boolean = false,
    val completedDate: Long? = null,
    val createdDate: Long = System.currentTimeMillis(),
    val pendingSync: Boolean = true,
    val isDeleted: Boolean = false
)