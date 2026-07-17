package com.example.smetracker.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.smetracker.utils.IdGenerator

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
    @PrimaryKey val id: String = IdGenerator.newId(),
    val customerId: String? = null,
    val customerName: String,
    val description: String,
    val amount: Double,
    val profit: Double = 0.0,
    // Total cost basis for this sale (costPrice * quantity), snapshotted at
    // sale time — mirrors SaleFinancials.costPrice on the remote side, which
    // is deliberately NOT looked up live from inventory so historical profit
    // stays accurate even if the item's cost price changes later.
    val costPriceSnapshot: Double = 0.0,
    val inventoryItemId: String? = null,
    @ColumnInfo(defaultValue = "1") val quantity: Int = 1,
    val date: Long = System.currentTimeMillis(),
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val pendingSync: Boolean = true
)