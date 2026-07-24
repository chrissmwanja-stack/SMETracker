package com.vestateck.smetracker.screens

import com.vestateck.smetracker.data.entities.InventoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeScanResolverTest {

    private fun item(
        id: String = "item-1",
        name: String = "Test Item",
        quantity: Int = 10,
        sellingPrice: Double = 1000.0,
        sku: String? = "SKU-1"
    ) = InventoryItem(
        id = id,
        name = name,
        quantity = quantity,
        sellingPrice = sellingPrice,
        sku = sku
    )

    private fun line(
        selectedItem: InventoryItem? = null,
        description: String = "",
        quantityInput: String = "1",
        amount: String = "",
        amountManuallyEdited: Boolean = false
    ) = SaleLineItem(
        selectedItem = selectedItem,
        description = description,
        quantityInput = quantityInput,
        amount = amount,
        amountManuallyEdited = amountManuallyEdited
    )

    // ── no match ──────────────────────────────────────────────────────

    @Test
    fun `rejects a code that matches no inventory item's sku`() {
        val outcome = resolveBarcodeScan(
            code = "UNKNOWN",
            inventoryItems = listOf(item(sku = "SKU-1")),
            lineItems = listOf(line())
        )

        assertTrue(outcome is BarcodeScanOutcome.Rejected)
        assertEquals("No item matches code \"UNKNOWN\"", outcome.message)
    }

    // ── new line, blank row available ────────────────────────────────

    @Test
    fun `fills a still-blank line instead of appending a new one`() {
        val match = item(sku = "SKU-1", quantity = 5, sellingPrice = 2500.0)
        val outcome = resolveBarcodeScan(
            code = "SKU-1",
            inventoryItems = listOf(match),
            lineItems = listOf(line()) // default blank first row
        )

        assertTrue(outcome is BarcodeScanOutcome.FillBlankLine)
        val fill = outcome as BarcodeScanOutcome.FillBlankLine
        assertEquals(0, fill.index)
        assertEquals(match, fill.newLine.selectedItem)
        assertEquals("1", fill.newLine.quantityInput)
        assertEquals("2500", fill.newLine.amount)
        assertEquals("Added \"${match.name}\"", fill.message)
    }

    @Test
    fun `fills the first blank line when several rows exist`() {
        val other = item(id = "item-2", sku = "SKU-2")
        val match = item(id = "item-1", sku = "SKU-1")
        val outcome = resolveBarcodeScan(
            code = "SKU-1",
            inventoryItems = listOf(match, other),
            lineItems = listOf(
                line(selectedItem = other, description = "Other Item"),
                line() // blank - index 1
            )
        )

        assertTrue(outcome is BarcodeScanOutcome.FillBlankLine)
        assertEquals(1, (outcome as BarcodeScanOutcome.FillBlankLine).index)
    }

    // ── new line, no blank row available ─────────────────────────────

    @Test
    fun `appends a new line when no blank row is available`() {
        val other = item(id = "item-2", sku = "SKU-2")
        val match = item(id = "item-1", sku = "SKU-1")
        val outcome = resolveBarcodeScan(
            code = "SKU-1",
            inventoryItems = listOf(match, other),
            lineItems = listOf(line(selectedItem = other, description = "Other Item"))
        )

        assertTrue(outcome is BarcodeScanOutcome.AppendNewLine)
        assertEquals(match, (outcome as BarcodeScanOutcome.AppendNewLine).newLine.selectedItem)
    }

    // ── new line, out of stock ───────────────────────────────────────

    @Test
    fun `rejects a scan for a new line when the item is out of stock`() {
        val match = item(sku = "SKU-1", quantity = 0)
        val outcome = resolveBarcodeScan(
            code = "SKU-1",
            inventoryItems = listOf(match),
            lineItems = listOf(line())
        )

        assertTrue(outcome is BarcodeScanOutcome.Rejected)
        assertEquals("\"${match.name}\" is out of stock", outcome.message)
    }

    // ── bumping an existing line ─────────────────────────────────────

    @Test
    fun `bumps the quantity of an existing line for the same item`() {
        val match = item(sku = "SKU-1", quantity = 10, sellingPrice = 1000.0)
        val outcome = resolveBarcodeScan(
            code = "SKU-1",
            inventoryItems = listOf(match),
            lineItems = listOf(line(selectedItem = match, quantityInput = "2", amount = "2000"))
        )

        assertTrue(outcome is BarcodeScanOutcome.BumpExistingLine)
        val bump = outcome as BarcodeScanOutcome.BumpExistingLine
        assertEquals(0, bump.index)
        assertEquals("3", bump.updatedLine.quantityInput)
        assertEquals("3000", bump.updatedLine.amount)
        assertEquals("Added \"${match.name}\"", bump.message)
    }

    @Test
    fun `recalculates the bumped line's index correctly among several lines`() {
        val other = item(id = "item-2", sku = "SKU-2")
        val match = item(id = "item-1", sku = "SKU-1", quantity = 10)
        val outcome = resolveBarcodeScan(
            code = "SKU-1",
            inventoryItems = listOf(match, other),
            lineItems = listOf(
                line(selectedItem = other, quantityInput = "1", amount = "1000"),
                line(selectedItem = match, quantityInput = "1", amount = "1000")
            )
        )

        assertTrue(outcome is BarcodeScanOutcome.BumpExistingLine)
        assertEquals(1, (outcome as BarcodeScanOutcome.BumpExistingLine).index)
    }

    @Test
    fun `preserves a manually-edited amount when bumping quantity`() {
        val match = item(sku = "SKU-1", quantity = 10, sellingPrice = 1000.0)
        val outcome = resolveBarcodeScan(
            code = "SKU-1",
            inventoryItems = listOf(match),
            lineItems = listOf(
                line(
                    selectedItem = match,
                    quantityInput = "1",
                    amount = "1500", // manually discounted, not the suggested 1000
                    amountManuallyEdited = true
                )
            )
        )

        assertTrue(outcome is BarcodeScanOutcome.BumpExistingLine)
        val bump = outcome as BarcodeScanOutcome.BumpExistingLine
        assertEquals("2", bump.updatedLine.quantityInput)
        assertEquals("1500", bump.updatedLine.amount) // unchanged, not re-suggested
    }

    @Test
    fun `rejects bumping an existing line past available stock`() {
        val match = item(sku = "SKU-1", quantity = 3)
        val outcome = resolveBarcodeScan(
            code = "SKU-1",
            inventoryItems = listOf(match),
            lineItems = listOf(line(selectedItem = match, quantityInput = "3", amount = "3000"))
        )

        assertTrue(outcome is BarcodeScanOutcome.Rejected)
        assertEquals("Only 3 of \"${match.name}\" in stock", outcome.message)
    }
}