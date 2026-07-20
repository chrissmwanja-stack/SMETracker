package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.entities.Sale

/**
 * Pure logic behind SaleSync.pushPending's checkout-level receipt
 * numbering - split into its own file so it's unit-testable without a
 * FirebaseFirestore instance (see SaleSync's class doc for the full
 * picture; this covers just "which pending Sale rows belong to the same
 * checkout, and what number has that checkout already claimed, if any").
 *
 * Sale rows created together by SMEViewModel.addSaleLines share one
 * provisionalReceiptNumber, claimed once for the whole checkout rather
 * than once per line item. Grouping on it here reconstructs which rows
 * belong to the same checkout, so pushPending can claim one authoritative
 * finalReceiptNumber per group instead of one per row.
 *
 * A blank provisionalReceiptNumber shouldn't happen in production
 * (MainActivity always wires a real ReceiptNumberGenerator), but grouping
 * unrelated sales together under one shared "" key would wrongly merge
 * them into a single receipt number - each sale with a blank number falls
 * back to being its own one-item group (keyed by its id) instead.
 */
fun groupSalesIntoCheckouts(sales: List<Sale>): Collection<List<Sale>> =
    sales.groupBy { sale -> sale.provisionalReceiptNumber.ifBlank { sale.id } }.values

/**
 * The finalReceiptNumber a checkout should reuse, if any member of the
 * group already has one set (e.g. a previous push claimed a number for
 * this checkout but didn't finish writing every row before failing). Null
 * means no member has claimed one yet, so pushPending needs to run the
 * Firestore counter transaction to get a fresh one.
 */
fun alreadyClaimedReceiptNumber(checkout: List<Sale>): String? =
    checkout.firstNotNullOfOrNull { it.finalReceiptNumber }