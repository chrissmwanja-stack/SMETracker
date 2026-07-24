package com.vestateck.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local-only offline-login credential: one row per business this device has
 * completed at least one full online phone-OTP verification for. Lets
 * SessionManager offer PIN-based login afterward with zero network at all -
 * critical for SACCO/shop-owner users in areas with GSM voice/SMS coverage
 * but no mobile data.
 *
 * Keyed by businessId (not phoneNumberE164) so one device can hold a
 * separate PIN per business it has ever logged into - e.g. an owner testing
 * both an owner and a worker account on the same phone gets two independent
 * rows here, no interference.
 *
 * Deliberately NOT part of the Firestore sync engine - no pendingSync
 * column, never touched by SyncEngine or SMEDatabase.clearSyncedDataSuspending().
 * The PIN hash+salt must never leave this device. Firestore/Firebase Auth
 * remains the real source of truth for identity; this table only ever
 * answers "does the PIN just typed match what we hashed after the last
 * online verification for this business" - nothing more.
 */
@Entity(tableName = "local_credentials")
data class LocalCredential(
    @PrimaryKey val businessId: String,
    val phoneNumberE164: String,
    /** MemberRole.name.lowercase() - see MemberRole.fromString. */
    val role: String,
    val firebaseUid: String,
    val pinHash: String,
    val pinSalt: String,
    val lastVerifiedOnlineAt: Long = System.currentTimeMillis()
)