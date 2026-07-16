// data/entities/InventoryItem.kt
package com.example.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smetracker.utils.IdGenerator

@Entity(tableName = "inventory_items")
data class InventoryItem(
    @PrimaryKey val id: String = IdGenerator.newId(),
    val name: String,
    val category: String = "",
    val quantity: Int = 0,
    val reorderLevel: Int = 5,        // alert threshold
    val costPrice: Double = 0.0,
    val sellingPrice: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val pendingSync: Boolean = true
)
