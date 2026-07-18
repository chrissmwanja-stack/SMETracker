// data/entities/InventoryItem.kt
package com.vestateck.smetracker.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vestateck.smetracker.utils.IdGenerator

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
    // Photo of the physical item (e.g. a boutique's cloth print) — optional,
    // any item can go without one. Two separate fields rather than one,
    // because "have a photo" and "have it backed up to the cloud" are
    // different states that don't always agree:
    //   - localImagePath: absolute path to a resized JPEG copy in this
    //     device's internal storage (see ImageUtils.copyToInternalStorage).
    //     Always preferred for display when present — works offline, no
    //     network fetch. Null means this device has no local copy, either
    //     because no photo was ever set, or because this item/photo came
    //     from another device via sync (see InventorySync's pull listener).
    //   - imageUrl: the Firebase Storage download URL, set only after a
    //     local photo has actually finished uploading. This is what lets
    //     OTHER devices show the photo — they'll never have a
    //     localImagePath for it, only this. Falls back to display here when
    //     localImagePath is null.
    val localImagePath: String? = null,
    val imageUrl: String? = null,
    // True from the moment a new local photo is picked until InventorySync
    // has successfully uploaded it and recorded the resulting imageUrl —
    // mirrors pendingSync but specifically for the upload half, since a
    // photo can be picked offline while the rest of the item edit pushes
    // fine, or the photo can simply be large and take longer than the rest
    // of the sync. Also what tells pushPending "re-upload", vs. "already
    // uploaded, don't repeat the bandwidth cost" on every unrelated edit.
    @ColumnInfo(defaultValue = "0") val imagePendingUpload: Boolean = false,
    val pendingSync: Boolean = true
)