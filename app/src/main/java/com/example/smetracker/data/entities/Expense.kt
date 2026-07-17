package com.example.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smetracker.utils.IdGenerator

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey
    val id: String = IdGenerator.newId(),
    val description: String,
    val amount: Double,
    val category: String = "General",
    val date: Long = System.currentTimeMillis(),
    val receiptNumber: String? = null,
    // Phone number (E.164) of whoever recorded this expense. Blank until the
    // first sync push fills it in from the current session — see SyncEngine.
    val recordedBy: String = "",
    // Mirrors ExpenseStatus on the remote side, kept as a plain String here
    // (matching how `category` is already handled) rather than a Room-mapped
    // enum: "PENDING" | "APPROVED" | "REJECTED". A worker's expense always
    // pushes as PENDING (required by the security rules); an owner's own
    // entry auto-pushes as APPROVED, since there's no approve/reject UI yet
    // for an owner to review their own submissions against.
    val status: String = "PENDING",
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val pendingSync: Boolean = true
)