package com.vestateck.smetracker.data.remote.sync.entities

import com.vestateck.smetracker.data.entities.Sale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutGroupingTest {

    private fun sale(
        id: String,
        provisionalReceiptNumber: String,
        finalReceiptNumber: String? = null
    ) = Sale(
        id = id,
        customerName = "Test Customer",
        description = "Test item",
        amount = 1000.0,
        provisionalReceiptNumber = provisionalReceiptNumber,
        finalReceiptNumber = finalReceiptNumber
    )

    // ── groupSalesIntoCheckouts ──────────────────────────────────────

    @Test
    fun `groups sales sharing the same provisional receipt number into one checkout`() {
        val sales = listOf(
            sale(id = "s1", provisionalReceiptNumber = "LOCAL-1"),
            sale(id = "s2", provisionalReceiptNumber = "LOCAL-1"),
            sale(id = "s3", provisionalReceiptNumber = "LOCAL-1")
        )

        val checkouts = groupSalesIntoCheckouts(sales)

        assertEquals(1, checkouts.size)
        assertEquals(3, checkouts.first().size)
    }

    @Test
    fun `keeps sales with different provisional receipt numbers in separate checkouts`() {
        val sales = listOf(
            sale(id = "s1", provisionalReceiptNumber = "LOCAL-1"),
            sale(id = "s2", provisionalReceiptNumber = "LOCAL-2")
        )

        val checkouts = groupSalesIntoCheckouts(sales)

        assertEquals(2, checkouts.size)
        assertTrue(checkouts.all { it.size == 1 })
    }

    @Test
    fun `a single-item checkout falls out as a group of size one`() {
        val sales = listOf(sale(id = "s1", provisionalReceiptNumber = "LOCAL-1"))

        val checkouts = groupSalesIntoCheckouts(sales)

        assertEquals(1, checkouts.size)
        assertEquals(1, checkouts.first().size)
    }

    @Test
    fun `sales with a blank provisional receipt number each fall back to their own id-keyed group`() {
        // Shouldn't happen in production (MainActivity always wires a real
        // ReceiptNumberGenerator), but two blank-numbered sales must never be
        // merged into a single receipt just because they share the same "" key.
        val sales = listOf(
            sale(id = "s1", provisionalReceiptNumber = ""),
            sale(id = "s2", provisionalReceiptNumber = "")
        )

        val checkouts = groupSalesIntoCheckouts(sales)

        assertEquals(2, checkouts.size)
        assertTrue(checkouts.all { it.size == 1 })
    }

    @Test
    fun `empty input produces no checkouts`() {
        assertEquals(0, groupSalesIntoCheckouts(emptyList()).size)
    }

    @Test
    fun `mixes grouped and ungrouped sales correctly in the same push`() {
        val sales = listOf(
            sale(id = "s1", provisionalReceiptNumber = "LOCAL-1"),
            sale(id = "s2", provisionalReceiptNumber = "LOCAL-1"),
            sale(id = "s3", provisionalReceiptNumber = "LOCAL-2"),
            sale(id = "s4", provisionalReceiptNumber = "")
        )

        val checkouts = groupSalesIntoCheckouts(sales)

        assertEquals(3, checkouts.size)
        val sizes = checkouts.map { it.size }.sorted()
        assertEquals(listOf(1, 1, 2), sizes)
    }

    // ── alreadyClaimedReceiptNumber ──────────────────────────────────

    @Test
    fun `returns null when no member of the checkout has claimed a final receipt number`() {
        val checkout = listOf(
            sale(id = "s1", provisionalReceiptNumber = "LOCAL-1"),
            sale(id = "s2", provisionalReceiptNumber = "LOCAL-1")
        )

        assertNull(alreadyClaimedReceiptNumber(checkout))
    }

    @Test
    fun `returns the already-claimed number when one member of the checkout has it set`() {
        // Simulates a retry after a previous push claimed a number but died
        // partway through writing every row in the group.
        val checkout = listOf(
            sale(id = "s1", provisionalReceiptNumber = "LOCAL-1", finalReceiptNumber = "INV-0007"),
            sale(id = "s2", provisionalReceiptNumber = "LOCAL-1", finalReceiptNumber = null)
        )

        assertEquals("INV-0007", alreadyClaimedReceiptNumber(checkout))
    }

    @Test
    fun `returns the first claimed number found when multiple members already agree`() {
        val checkout = listOf(
            sale(id = "s1", provisionalReceiptNumber = "LOCAL-1", finalReceiptNumber = "INV-0007"),
            sale(id = "s2", provisionalReceiptNumber = "LOCAL-1", finalReceiptNumber = "INV-0007")
        )

        assertEquals("INV-0007", alreadyClaimedReceiptNumber(checkout))
    }

    @Test
    fun `returns null for an empty checkout`() {
        assertNull(alreadyClaimedReceiptNumber(emptyList()))
    }
}