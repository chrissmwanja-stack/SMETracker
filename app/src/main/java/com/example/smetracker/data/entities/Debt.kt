package com.example.smetracker.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.smetracker.utils.IdGenerator

@Entity(
    tableName = "debts",
    foreignKeys = [ForeignKey(
        entity = Customer::class,
        parentColumns = ["id"],
        childColumns = ["customerId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index(value = ["customerId"])]
)
data class Debt(
    @PrimaryKey val id: String = IdGenerator.newId(),
    val customerId: String? = null,
    val customerName: String,        // denormalized for quick display
    val description: String = "",
    val amount: Double,
    val isPaid: Boolean = false,
    val dueDate: Long? = null,
    val date: Long = System.currentTimeMillis(),
    val pendingSync: Boolean = true
)