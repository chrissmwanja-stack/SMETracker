package com.example.smetracker.data.entities

import androidx.room.ColumnInfo
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
    indices = [
        Index(value = ["customerId"]),
        Index(value = ["inventoryItemId"])
    ]
)
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long? = null,
    val customerName: String,
    val description: String,
    val amount: Double,
    val profit: Double = 0.0,
    val inventoryItemId: Long? = null,
    @ColumnInfo(defaultValue = "1") val quantity: Int = 1,
    val date: Long = System.currentTimeMillis(),
    val paymentMethod: PaymentMethod = PaymentMethod.CASH
)