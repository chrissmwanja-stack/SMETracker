package com.vestateck.smetracker.utils

/**
 * A single row from a bulk inventory import (e.g. CSV).
 * Mirrors the fields of InventoryItem but allows costPrice to be null
 * (to trigger reconciliation if missing) and omits system fields like id or recordedBy.
 */
data class BulkInventoryRow(
    val name: String,
    val category: String = "",
    val quantity: Int = 0,
    val sellingPrice: Double = 0.0,
    val costPrice: Double? = null,
    val reorderLevel: Int = 5,
    val sku: String? = null
)
