// utils/CurrencyUtils.kt
package com.vestateck.smetracker.utils

import java.text.NumberFormat
import java.util.Locale

object CurrencyUtils {
    private val ugxFormatter: NumberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 0 // UGX typically doesn't use decimals
    }

    fun formatUgx(amount: Double): String {
        return "UGX ${ugxFormatter.format(amount.toLong())}"
    }

    /** Same grouping/rounding as [formatUgx] but without the "UGX" prefix - for
     *  table cells (e.g. a receipt's Qty/Price/Amount columns) where the
     *  currency is already established once by the surrounding context. */
    fun formatNumber(amount: Double): String {
        return ugxFormatter.format(amount.toLong())
    }
}