package com.example.smetracker.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PaymentMethod {
    CASH,
    MTN_MOMO,
    AIRTEL_MONEY,
    DEBT
}

@Entity(
    tableName = "sales",
    foreignKeys = [ForeignKey(
        entity = Customer::class,
        parentColumns = ["id"],
        childColumns = ["customerId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index(value = ["customerId"])]
)
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long? = null,
    val customerName: String,        // denormalized for quick display
    val description: String,
    val amount: Double,
    val profit: Double = 0.0,  // Profit (amount - cost). Only non-zero when linked to an inventory item.
    val inventoryItemId: Long? = null,  // set when this sale was made against a tracked inventory item
    val quantity: Int = 1,
    val date: Long = System.currentTimeMillis(),
    val paymentMethod: PaymentMethod = PaymentMethod.CASH
)