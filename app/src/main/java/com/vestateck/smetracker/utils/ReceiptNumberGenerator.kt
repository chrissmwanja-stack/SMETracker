package com.vestateck.smetracker.utils

import android.content.Context

/**
 * Generates a provisional (locally-scoped) receipt number the instant a sale
 * is recorded, so a receipt can be shown/shared/printed fully offline. This
 * number is NEVER the authoritative one - see Sale.finalReceiptNumber and
 * SaleSync.pushPending, which claims the real global sequence number via a
 * Firestore transaction once this device is online. Same
 * provisional-now/reconciled-later shape as costReconciled/
 * financialsReconciled elsewhere in this app.
 *
 * Format: "{last 4 digits of phone}-{6-digit local sequence}", e.g.
 * "0771-000042". Two different devices can never collide on this string
 * (different phone suffix), and a single device's own sequence only ever
 * increases, even across app restarts (SharedPreferences-backed).
 */
class ReceiptNumberGenerator(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("receipt_number_prefs", Context.MODE_PRIVATE)

    fun next(phoneE164: String): String {
        val suffix = phoneE164.takeLast(4).ifBlank { "0000" }
        val nextSeq = prefs.getLong(KEY_SEQ, 0L) + 1
        prefs.edit().putLong(KEY_SEQ, nextSeq).apply()
        return "$suffix-${nextSeq.toString().padStart(6, '0')}"
    }

    companion object {
        private const val KEY_SEQ = "local_receipt_seq"
    }
}