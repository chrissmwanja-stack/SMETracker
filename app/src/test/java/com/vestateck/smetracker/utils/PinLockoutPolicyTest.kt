package com.vestateck.smetracker.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockoutPolicyTest {

    @Test
    fun noLockForFirstFourFailures() {
        for (f in 0..4) assertEquals(0L, PinLockoutPolicy.lockDurationMs(f))
    }

    @Test
    fun lockStartsAtFifthFailureAndDoubles() {
        assertEquals(30_000L, PinLockoutPolicy.lockDurationMs(5))
        assertEquals(60_000L, PinLockoutPolicy.lockDurationMs(6))
        assertEquals(120_000L, PinLockoutPolicy.lockDurationMs(7))
        assertEquals(240_000L, PinLockoutPolicy.lockDurationMs(8))
        assertEquals(480_000L, PinLockoutPolicy.lockDurationMs(9))
    }

    @Test
    fun lockIsCappedAndNeverOverflows() {
        assertEquals(PinLockoutPolicy.MAX_LOCK_MS, PinLockoutPolicy.lockDurationMs(15))
        assertEquals(PinLockoutPolicy.MAX_LOCK_MS, PinLockoutPolicy.lockDurationMs(10_000))
    }

    @Test
    fun attemptsBeforeLockoutCountsDown() {
        assertEquals(4, PinLockoutPolicy.attemptsBeforeLockout(1))
        assertEquals(1, PinLockoutPolicy.attemptsBeforeLockout(4))
        assertEquals(0, PinLockoutPolicy.attemptsBeforeLockout(5))
        assertEquals(0, PinLockoutPolicy.attemptsBeforeLockout(9))
    }

    @Test
    fun credentialErasedAtTenthFailure() {
        assertFalse(PinLockoutPolicy.shouldEraseCredential(9))
        assertTrue(PinLockoutPolicy.shouldEraseCredential(10))
        assertTrue(PinLockoutPolicy.shouldEraseCredential(11))
    }
}
