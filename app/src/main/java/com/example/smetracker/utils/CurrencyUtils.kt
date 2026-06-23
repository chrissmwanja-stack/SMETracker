// utils/CurrencyUtils.kt
package com.example.smetracker.utils

import java.text.NumberFormat
import java.util.Locale

object CurrencyUtils {
    private val ugxFormatter: NumberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 0 // UGX typically doesn't use decimals
    }

    fun formatUgx(amount: Double): String {
        return "UGX ${ugxFormatter.format(amount.toLong())}"
    }
}