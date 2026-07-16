package com.example.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smetracker.utils.IdGenerator

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey
    val id: String = IdGenerator.newId(),
    val description: String,
    val amount: Double,
    val category: String = "General",
    val date: Long = System.currentTimeMillis(),
    val receiptNumber: String? = null,
    val pendingSync: Boolean = true
)