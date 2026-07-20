package com.vestateck.smetracker.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vestateck.smetracker.utils.IdGenerator

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
    // sale time - mirrors SaleFinancials.costPrice on the remote side, which
    // is deliberately NOT looked up live from inventory so historical profit
    // stays accurate even if the item's cost price changes later.
    val costPriceSnapshot: Double = 0.0,
    val inventoryItemId: String? = null,
    @ColumnInfo(defaultValue = "1") val quantity: Int = 1,
    val date: Long = System.currentTimeMillis(),
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    // Phone number (E.164) of whoever recorded this sale - mirrors
    // Expense.recordedBy. Blank until the first sync push fills it in from
    // the current session (see SyncEngine), same as Expense.
    @ColumnInfo(defaultValue = "''") val recordedBy: String = "",
    // False means this sale's costPriceSnapshot/profit are not yet trustworthy
    // and need an owner's review - see the Reconciliation screen. Defaults to
    // true so existing pre-migration rows (and any sale with no linked
    // inventory item, whose profit is legitimately 0/unknown) don't suddenly
    // appear in the reconciliation queue. Set to false only when a worker
    // records a sale against a tracked inventory item, since a worker's
    // device never has real cost data to compute profit from - see
    // SMEViewModel.addSale and SyncEngine's class doc.
    @ColumnInfo(defaultValue = "1") val financialsReconciled: Boolean = true,
    // Receipt numbering, provisional-now/reconciled-later - see
    // ReceiptNumberGenerator and SaleSync.pushPending's class docs.
    //   - provisionalReceiptNumber: assigned locally the instant this Sale
    //     is created (SMEViewModel.addSale), works fully offline, always
    //     non-null. Device-scoped format, never collides across devices.
    //   - finalReceiptNumber: the authoritative global sequence number
    //     (e.g. "INV-0001"), claimed via a Firestore transaction against
    //     businesses/{businessId}/counters/receiptSequence once this
    //     device is online. Null until that claim succeeds. The receipt
    //     document should display this when present, falling back to
    //     provisionalReceiptNumber otherwise.
    @ColumnInfo(defaultValue = "''") val provisionalReceiptNumber: String = "",
    val finalReceiptNumber: String? = null,
    val pendingSync: Boolean = true
)