package com.example.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val description: String,
    val amount: Double,
    val category: String = "General",
    val date: Long = System.currentTimeMillis(),
    val receiptNumber: String? = null
)