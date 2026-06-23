package com.example.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val priority: String = "Medium",
    val dueDate: Long? = null,
    val isCompleted: Boolean = false,
    val completedDate: Long? = null,
    val createdDate: Long = System.currentTimeMillis()
)