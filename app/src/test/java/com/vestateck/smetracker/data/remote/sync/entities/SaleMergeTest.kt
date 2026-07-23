package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.remote.model.RemoteSale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleMergeTest {

    private fun remoteSale(
        id: String = "sale1",
        inventoryItemId: String? = "item1",
        quantity: Int = 2,
        amount: Double = 1000.0,
        paymentMethod: String = "CASH",
        finalReceiptNumber: String? = null,
        isDeleted: Boolean = false
    ) = RemoteSale(
        id = id,
        customerName = "Test Customer",
        description = "Test item",
        amount = amount,
        inventoryItemId = inventoryItemId,
        quantity = quantity,
        paymentMethod = paymentMethod,
        recordedBy = "+256700000000",
        finalReceiptNumber = finalReceiptNumber,
        isDeleted = isDeleted
    )

    private fun localSale(
        id: String = "sale1",
        financialsReconciled: Boolean = false,
        profit: Double = 42.0,
        costPriceSnapshot: Double = 58.0,
        provisionalReceiptNumber: String = "LOCAL-1",
        pendingSync: Boolean = true
    ) = Sale(
        id = id,
        customerName = "Test Customer",
        description = "Test item",
        amount = 1000.0,
        financialsReconciled = financialsReconciled,
        profit = profit,
        costPriceSnapshot = costPriceSnapshot,
        provisionalReceiptNumber = provisionalReceiptNumber,
        pendingSync = pendingSync
    )

    private fun item(
        costReconciled: Boolean = true,
        costPrice: Double = 300.0
    ) = InventoryItem(
        name = "Widget",
        costReconciled = costReconciled,
        costPrice = costPrice
    )

    // ── existing != null: this device's own values always win ───────

    @Test
    fun `keeps this device's own financials untouched regardless of what the remote sale carries`() {
        val existing = localSale(financialsReconciled = true, profit = 42.0, costPriceSnapshot = 58.0)
        // A linkedItem is never even looked up by the caller when existing != null,
        // but even if it were, existing's own values must still win.
        val merged = mergeIncomingSale(remoteSale(), existing, linkedItem = item())

        assertTrue(merged.financialsReconciled)
        assertEquals(42.0, merged.profit, 0.0)
        assertEquals(58.0, merged.costPriceSnapshot, 0.0)
    }

    @Test
    fun `keeps this device's own provisional receipt number and pendingSync when it already has the sale`() {
        val existing = localSale(provisionalReceiptNumber = "LOCAL-7", pendingSync = false)

        val merged = mergeIncomingSale(remoteSale(finalReceiptNumber = "INV-0009"), existing, linkedItem = null)

        assertEquals("LOCAL-7", merged.provisionalReceiptNumber)
        assertFalse(merged.pendingSync)
    }

    // ── existing == null: new sale from another device ──────────────

    @Test
    fun `a new sale with no linked inventory item is treated as reconciled with zero profit`() {
        val merged = mergeIncomingSale(remoteSale(inventoryItemId = null), existing = null, linkedItem = null)

        assertTrue(merged.financialsReconciled)
        assertEquals(0.0, merged.profit, 0.0)
        assertEquals(0.0, merged.costPriceSnapshot, 0.0)
        assertFalse(merged.pendingSync)
    }

    @Test
    fun `a new sale linked to an item with no known cost is left unreconciled`() {
        // linkedItem == null here models the case where this device hasn't
        // synced that inventory item yet either.
        val merged = mergeIncomingSale(remoteSale(inventoryItemId = "item1"), existing = null, linkedItem = null)

        assertFalse(merged.financialsReconciled)
        assertEquals(0.0, merged.profit, 0.0)
        assertEquals(0.0, merged.costPriceSnapshot, 0.0)
        assertFalse(merged.pendingSync)
    }

    @Test
    fun `derives reconciled financials locally when this device already knows the linked item's cost`() {
        val merged = mergeIncomingSale(
            remoteSale(inventoryItemId = "item1", quantity = 2, amount = 1000.0),
            existing = null,
            linkedItem = item(costReconciled = true, costPrice = 300.0)
        )

        assertTrue(merged.financialsReconciled)
        assertEquals(600.0, merged.costPriceSnapshot, 0.0) // 300 * quantity(2)
        assertEquals(400.0, merged.profit, 0.0) // amount(1000) - costPriceSnapshot(600)
        // Derived locally but never pushed as saleFinancials by the sender -
        // this device must push it itself if it's the owner (see pushPending).
        assertTrue(merged.pendingSync)
    }

    @Test
    fun `a linked item whose cost is still unreconciled does not count as known`() {
        val merged = mergeIncomingSale(
            remoteSale(inventoryItemId = "item1"),
            existing = null,
            linkedItem = item(costReconciled = false, costPrice = 300.0)
        )

        assertFalse(merged.financialsReconciled)
        assertEquals(0.0, merged.profit, 0.0)
        assertFalse(merged.pendingSync)
    }

    @Test
    fun `a linked item with a zero cost price does not count as known`() {
        val merged = mergeIncomingSale(
            remoteSale(inventoryItemId = "item1"),
            existing = null,
            linkedItem = item(costReconciled = true, costPrice = 0.0)
        )

        assertFalse(merged.financialsReconciled)
        assertEquals(0.0, merged.profit, 0.0)
        assertFalse(merged.pendingSync)
    }

    // ── provisionalReceiptNumber fallback for a brand-new sale ───────

    @Test
    fun `a new sale falls back to the remote's final receipt number when this device has never assigned one`() {
        val merged = mergeIncomingSale(
            remoteSale(finalReceiptNumber = "INV-0012"),
            existing = null,
            linkedItem = null
        )

        assertEquals("INV-0012", merged.provisionalReceiptNumber)
    }

    @Test
    fun `a new sale falls back to a blank provisional number when neither exists yet`() {
        val merged = mergeIncomingSale(
            remoteSale(finalReceiptNumber = null),
            existing = null,
            linkedItem = null
        )

        assertEquals("", merged.provisionalReceiptNumber)
    }

    // ── misc field mapping ───────────────────────────────────────────

    @Test
    fun `falls back to CASH when the remote payment method string is unrecognized`() {
        val merged = mergeIncomingSale(
            remoteSale(paymentMethod = "BOGUS_METHOD"),
            existing = null,
            linkedItem = null
        )

        assertEquals(PaymentMethod.CASH, merged.paymentMethod)
    }

    @Test
    fun `maps a valid remote payment method string correctly`() {
        val merged = mergeIncomingSale(
            remoteSale(paymentMethod = "MTN_MOMO"),
            existing = null,
            linkedItem = null
        )

        assertEquals(PaymentMethod.MTN_MOMO, merged.paymentMethod)
    }

    @Test
    fun `carries the isDeleted tombstone flag through from the remote sale`() {
        val merged = mergeIncomingSale(
            remoteSale(isDeleted = true),
            existing = null,
            linkedItem = null
        )

        assertTrue(merged.isDeleted)
    }
}