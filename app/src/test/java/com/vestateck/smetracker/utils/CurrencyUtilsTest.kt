package com.vestateck.smetracker.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyUtilsTest {

    @Test
    fun `formats whole number with UGX prefix and thousands separators`() {
        assertEquals("UGX 1,500,000", CurrencyUtils.formatUgx(1_500_000.0))
    }

    @Test
    fun `formats zero correctly`() {
        assertEquals("UGX 0", CurrencyUtils.formatUgx(0.0))
    }

    @Test
    fun `drops decimal places since UGX has no subunit in everyday use`() {
        // .toLong() truncates rather than rounds — documenting the current
        // behavior here so a future change to round-instead-of-truncate is a
        // deliberate decision, not an accidental regression.
        assertEquals("UGX 100", CurrencyUtils.formatUgx(100.99))
        assertEquals("UGX 100", CurrencyUtils.formatUgx(100.01))
    }

    @Test
    fun `formats negative amounts`() {
        assertEquals("UGX -5,000", CurrencyUtils.formatUgx(-5000.0))
    }

    @Test
    fun `formats small amounts without unwanted separators`() {
        assertEquals("UGX 500", CurrencyUtils.formatUgx(500.0))
    }
}