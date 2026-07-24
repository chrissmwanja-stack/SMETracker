// screens/BarcodeScanResolver.kt
package com.vestateck.smetracker.screens

import com.vestateck.smetracker.data.entities.InventoryItem

/**
 * Pure logic behind AddSaleScreen's scan-to-add flow - split out of the
 * Composable so it's unit-testable without a Compose runtime (same reason
 * CheckoutGrouping.kt is split out of SaleSync; see that file's doc for the
 * parallel).
 *
 * Given a scanned code and the screen's current state, decides what should
 * happen to the cart - never mutates anything itself. AddSaleScreen's
 * handleScan() applies the returned [BarcodeScanOutcome] to its
 * mutableStateListOf/mutableStateOf.
 *
 * Same "one line per item, bump quantity instead of a second line" rule as
 * picking the same item twice from the dropdown (see
 * itemAlreadyUsedElsewhere in AddSaleScreen).
 */
internal sealed interface BarcodeScanOutcome {
    /** Status text for AddSaleScreen's scanMessage - every variant has one. */
    val message: String

    /** An existing line for this item had its quantity (and amount) bumped. */
    data class BumpExistingLine(
        val index: Int,
        val updatedLine: SaleLineItem,
        override val message: String
    ) : BarcodeScanOutcome

    /** A still-blank line (e.g. the screen's default first row) was filled in. */
    data class FillBlankLine(
        val index: Int,
        val newLine: SaleLineItem,
        override val message: String
    ) : BarcodeScanOutcome

    /** No blank line was available, so a new line was appended. */
    data class AppendNewLine(
        val newLine: SaleLineItem,
        override val message: String
    ) : BarcodeScanOutcome

    /** Scan couldn't be applied - no matching SKU, or it would exceed stock. */
    data class Rejected(override val message: String) : BarcodeScanOutcome
}

internal fun resolveBarcodeScan(
    code: String,
    inventoryItems: List<InventoryItem>,
    lineItems: List<SaleLineItem>
): BarcodeScanOutcome {
    val match = inventoryItems.find { it.sku == code }
        ?: return BarcodeScanOutcome.Rejected("No item matches code \"$code\"")

    val existingIndex = lineItems.indexOfFirst { it.selectedItem?.id == match.id }
    if (existingIndex >= 0) {
        val line = lineItems[existingIndex]
        val newQty = (line.quantityInput.toIntOrNull() ?: 0) + 1
        if (newQty > match.quantity) {
            return BarcodeScanOutcome.Rejected("Only ${match.quantity} of \"${match.name}\" in stock")
        }
        val amount = if (!line.amountManuallyEdited) suggestedAmount(match, newQty) else line.amount
        val updated = line.copy(quantityInput = newQty.toString(), amount = amount)
        return BarcodeScanOutcome.BumpExistingLine(existingIndex, updated, "Added \"${match.name}\"")
    }

    if (match.quantity <= 0) {
        return BarcodeScanOutcome.Rejected("\"${match.name}\" is out of stock")
    }
    val newLine = SaleLineItem(
        selectedItem = match,
        description = match.name,
        quantityInput = "1",
        amount = suggestedAmount(match, 1)
    )
    val emptyIndex = lineItems.indexOfFirst { it.selectedItem == null && it.description.isBlank() }
    return if (emptyIndex >= 0) {
        BarcodeScanOutcome.FillBlankLine(emptyIndex, newLine, "Added \"${match.name}\"")
    } else {
        BarcodeScanOutcome.AppendNewLine(newLine, "Added \"${match.name}\"")
    }
}