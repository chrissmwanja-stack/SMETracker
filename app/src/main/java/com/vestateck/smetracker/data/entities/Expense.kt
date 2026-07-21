package com.vestateck.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vestateck.smetracker.utils.IdGenerator

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey
    val id: String = IdGenerator.newId(),
    val description: String,
    val amount: Double,
    @androidx.room.ColumnInfo(defaultValue = "'General'") val category: String = "General",
    val date: Long = System.currentTimeMillis(),
    val receiptNumber: String? = null,
    // Phone number (E.164) of whoever recorded this expense. Blank until the
    // first sync push fills it in from the current session - see SyncEngine.
    @androidx.room.ColumnInfo(defaultValue = "''") val recordedBy: String = "",
    // Mirrors ExpenseStatus on the remote side, kept as a plain String here
    // (matching how `category` is already handled) rather than a Room-mapped
    // enum: "PENDING" | "APPROVED" | "REJECTED". A worker's expense always
    // pushes as PENDING (required by the security rules); an owner's own
    // entry auto-pushes as APPROVED, since there's no approve/reject UI yet
    // for an owner to review their own submissions against.
    val status: String = "PENDING",
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    // Photo of the physical receipt/invoice for this expense - proof for
    // tax/audit purposes. Same local-vs-remote split as InventoryItem's
    // photo fields (see that entity's doc comment for the full reasoning):
    //   - localReceiptPath: this device's own resized copy, internal
    //     storage. Preferred for display when present.
    //   - receiptUrl: Firebase Storage download URL, set once the local
    //     photo finishes uploading. What lets OTHER devices (e.g. the
    //     owner reviewing a worker's PENDING expense) see it.
    val localReceiptPath: String? = null,
    val receiptUrl: String? = null,
    // True from the moment a new receipt photo is picked until
    // ExpenseSync has uploaded it and recorded receiptUrl. Mirrors
    // InventoryItem.imagePendingUpload.
    @androidx.room.ColumnInfo(defaultValue = "0") val receiptPendingUpload: Boolean = false,
    val pendingSync: Boolean = true,
    val isDeleted: Boolean = false
)