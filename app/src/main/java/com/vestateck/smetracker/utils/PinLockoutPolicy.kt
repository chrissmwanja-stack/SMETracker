package com.vestateck.smetracker.utils

/**
 * Pure rules for offline-PIN brute-force protection (see
 * SessionManager.attemptPinLogin). Kept free of Android/DataStore types so the
 * schedule is unit-testable on the JVM.
 *
 * Schedule, by consecutive failed attempts:
 *   1-4   no delay
 *   5     30 s lock
 *   6     1 min, 7 -> 2 min, 8 -> 4 min, 9 -> 8 min   (doubling, capped at 15 min)
 *   10    stored PIN erased; full online OTP sign-in required
 *
 * That caps a UI-driven guesser at 10 tries total, against a 4-6 digit PIN.
 * This protects the PIN screen only. It does not protect the stored hash if
 * someone obtains the database file; that is why the database is also excluded
 * from Android backup.
 */
object PinLockoutPolicy {
    const val FREE_ATTEMPTS = 5
    const val MAX_ATTEMPTS = 10
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 15 * 60_000L

    /** Lock to apply after [failures] consecutive wrong PINs (0 = no lock). */
    fun lockDurationMs(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(20)
        return (BASE_LOCK_MS shl doublings).coerceAtMost(MAX_LOCK_MS)
    }

    /** Tries left before the next lock starts (0 once locked). */
    fun attemptsBeforeLockout(failures: Int): Int =
        if (failures >= FREE_ATTEMPTS) 0 else FREE_ATTEMPTS - failures

    fun shouldEraseCredential(failures: Int): Boolean = failures >= MAX_ATTEMPTS
}
