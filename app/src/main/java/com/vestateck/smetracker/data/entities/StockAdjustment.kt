package com.vestateck.smetracker.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vestateck.smetracker.utils.IdGenerator

enum class StockAdjustmentReason {
    INCOMING, // Adding stock (purchase/restock)
    RECOUNT,  // Manual adjustment after physical count
    SALE      // Automatic reduction on sale
}

@Entity(
    tableName = "stock_adjustments",
    foreignKeys = [
        ForeignKey(
            entity = InventoryItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["itemId"])]
)
data class StockAdjustment(
    @PrimaryKey val id: String = IdGenerator.newId(),
    val itemId: String,
    val delta: Int,
    val reason: StockAdjustmentReason,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val recordedBy: String? = null,
    val pendingSync: Boolean = true
)
