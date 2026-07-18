// data/entities/InventoryItem.kt
package com.example.smetracker.data.entities

import androidx.room.ColumnInfo
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
    // Phone number (E.164) of whoever created this item — mirrors
    // Expense.recordedBy / Sale.recordedBy.
    @ColumnInfo(defaultValue = "''") val recordedBy: String = "",
    // False means costPrice is not yet trustworthy and needs an owner's
    // review — see the Reconciliation screen. Defaults to true so existing
    // pre-migration rows don't suddenly appear in the reconciliation queue.
    // Set to false only when a worker creates a brand-new item, since the
    // Add/Edit dialog never lets a worker enter a cost price (see
    // InventoryScreen's InventoryItemDialog) — it's always 0 until an owner
    // sets a real one.
    @ColumnInfo(defaultValue = "1") val costReconciled: Boolean = true,
    val pendingSync: Boolean = true
)