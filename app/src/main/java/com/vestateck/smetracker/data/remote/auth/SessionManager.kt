package com.vestateck.smetracker.data.remote.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vestateck.smetracker.data.dao.LocalCredentialDao
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.entities.LocalCredential
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.utils.PinHasher
import com.vestateck.smetracker.utils.PinLockoutPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "sme_session")

/**
 * Snapshot of "who is logged in and what can they see". Every screen that
 * needs role gating (Phase 5) should collect this rather than querying
 * Firebase/Firestore directly.
 */
data class SessionState(
    val phoneNumberE164: String? = null,
    val businessId: String? = null,
    val role: MemberRole? = null
) {
    val isLoggedIn: Boolean get() = phoneNumberE164 != null
    val hasBusiness: Boolean get() = businessId != null
    val isOwner: Boolean get() = role == MemberRole.OWNER
}

private object Keys {
    val PHONE = stringPreferencesKey("phone_number_e164")
    val BUSINESS_ID = stringPreferencesKey("business_id")
    val ROLE = stringPreferencesKey("role")

    // Deliberately NOT removed by clearSession() (a normal sign-out) - this is
    // what lets AuthNavGate offer PIN-based offline login again the next time
    // the app opens, instead of forcing a full phone/OTP flow that needs a
    // data connection. Only forgetDeviceCredential() clears it, e.g. when the
    // user explicitly chooses "use a different number" or forgets their PIN.
    val DEVICE_BUSINESS_ID = stringPreferencesKey("device_business_id")

    // Offline-PIN brute-force protection (see PinLockoutPolicy). Kept in the same
    // DataStore file as the session, which is excluded from Android backup, and
    // deliberately NOT cleared by clearSession(), so signing out and back in
    // can't be used to reset the failure counter.
    // The business whose data currently sits in the Room database. Room entities
    // are not businessId-scoped, so this single marker is what keeps one business's
    // rows from being shown to, or pushed into, another business on a shared
    // device. Set by saveBusinessMembership(); deliberately NOT cleared by
    // clearSession() or forgetDeviceCredential(), because the rows outlive both.
    val DATA_BUSINESS_ID = stringPreferencesKey("data_business_id")

    val PIN_FAILURES = intPreferencesKey("pin_failures")
    val PIN_LOCKED_UNTIL = longPreferencesKey("pin_locked_until")
}

/** Result of one PIN entry attempt via [SessionManager.attemptPinLogin]. */
sealed interface PinAttemptResult {
    data object Success : PinAttemptResult
    /** Wrong PIN. [attemptsBeforeLockout] is how many more tries before a temporary lock (0 = now locked). */
    data class Wrong(val attemptsBeforeLockout: Int, val lockedForMs: Long) : PinAttemptResult
    /** Still locked from earlier failures; the PIN was not checked. */
    data class LockedOut(val remainingMs: Long) : PinAttemptResult
    /** Too many failures: the device PIN was erased and a full OTP sign-in is required. */
    data object TooManyAttempts : PinAttemptResult
}

class SessionManager(
    private val context: Context,
    private val localCredentialDao: LocalCredentialDao,
    private val database: SMEDatabase
) {

    val sessionState: Flow<SessionState> = context.sessionDataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            SessionState(
                phoneNumberE164 = prefs[Keys.PHONE],
                businessId = prefs[Keys.BUSINESS_ID],
                role = prefs[Keys.ROLE]?.let { MemberRole.fromString(it) }
            )
        }

    /**
     * businessId that owns the rows currently in Room, or null if unknown
     * (fresh install, or an install that predates this marker). See
     * [isDataOwnedBy] and [saveBusinessMembership].
     */
    val dataBusinessId: Flow<String?> = context.sessionDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.DATA_BUSINESS_ID] }

    /**
     * False only when Room is known to hold a DIFFERENT business's data.
     * SyncEngine checks this before attaching listeners or pushing, as a second
     * line of defence behind the wipe in [saveBusinessMembership]. A null marker
     * (legacy install) counts as owned, so existing users are not blocked.
     */
    suspend fun isDataOwnedBy(businessId: String): Boolean {
        val owner = dataBusinessId.first()
        return owner == null || owner == businessId
    }

    /**
     * Forgets which business owns the local data. Call it only right after wiping
     * Room (for example a future "reset this device" action); the next link then
     * wipes again, which is harmless.
     */
    suspend fun clearLocalDataOwner() {
        context.sessionDataStore.edit { it.remove(Keys.DATA_BUSINESS_ID) }
    }

    /**
     * businessId of the account this device can log into via local PIN with
     * zero network (plain GSM/SMS-only areas) - null if this device has never
     * completed a full online verification for any business, or if that
     * credential was explicitly removed via forgetDeviceCredential(). Read by
     * AuthNavGate on cold start to decide whether to show PIN entry or the
     * full phone/OTP flow.
     */
    val deviceBusinessId: Flow<String?> = context.sessionDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.DEVICE_BUSINESS_ID] }

    /** Called right after phone auth succeeds, before phoneIndex is known. */
    suspend fun savePhoneNumber(phoneNumberE164: String) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.PHONE] = phoneNumberE164
        }
    }

    /**
     * Called once phoneIndex lookup resolves (post sign-in or post sign-up).
     *
     * Room is not businessId-scoped, so before recording the new membership this
     * decides whether the rows already in Room belong to the business being linked:
     *
     *  - Same business as the last link: KEEP everything, including unsynced
     *    (pendingSync) rows. A stale Firebase session or "forgot PIN" followed by
     *    an OTP login as the same business must not destroy offline work.
     *  - A different business, or no known previous owner (first-ever link on
     *    this device): WIPE every table. Those rows belong to someone else (or to
     *    nothing) and must never be shown to, or pushed into, this business.
     *
     * The previous owner is read from [Keys.DATA_BUSINESS_ID]. Installs that
     * predate that marker fall back to the old signals (deviceBusinessId, then the
     * last session's businessId), so upgrading users keep their data when they
     * sign back in to the same business. The marker is written in the same
     * DataStore edit as the membership, so the two cannot disagree. Must run
     * before SyncEngine starts (see MainActivity's onEnterApp).
     *
     * Wiping on a business switch does discard that device's unsynced rows for
     * the OTHER business; they cannot be pushed anywhere correct from here.
     */
    suspend fun saveBusinessMembership(businessId: String, role: MemberRole) {
        val prefs = context.sessionDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        val previousOwner = prefs[Keys.DATA_BUSINESS_ID]
            ?: prefs[Keys.DEVICE_BUSINESS_ID]
            ?: prefs[Keys.BUSINESS_ID]
        if (previousOwner != businessId) {
            database.clearAllTablesSuspending()
        }
        context.sessionDataStore.edit { p ->
            p[Keys.BUSINESS_ID] = businessId
            p[Keys.ROLE] = role.name.lowercase()
            p[Keys.DATA_BUSINESS_ID] = businessId
        }
    }

    /**
     * Normal sign-out. Intentionally leaves Keys.DEVICE_BUSINESS_ID and the
     * matching local_credentials row alone - see the Keys.DEVICE_BUSINESS_ID
     * doc comment above for why. Call forgetDeviceCredential() as well if the
     * user explicitly wants this device to forget the PIN too.
     * Also leaves Keys.DATA_BUSINESS_ID alone: the local rows outlive the session.
     */
    suspend fun clearSession() {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(Keys.PHONE)
            prefs.remove(Keys.BUSINESS_ID)
            prefs.remove(Keys.ROLE)
        }
    }

    // ---- Offline PIN credential support ----

    suspend fun getLocalCredential(businessId: String): LocalCredential? =
        localCredentialDao.getByBusinessId(businessId)

    /**
     * Call right after a successful online OTP verification, once the user
     * has chosen a PIN. Stores a salted hash locally (never the raw PIN,
     * never synced to Firestore) and marks this businessId as this device's
     * offline-login target.
     */
    suspend fun savePinAfterOnlineVerification(
        businessId: String,
        phoneNumberE164: String,
        role: MemberRole,
        firebaseUid: String,
        pin: String
    ) {
        val salt = PinHasher.generateSalt()
        localCredentialDao.upsert(
            LocalCredential(
                businessId = businessId,
                phoneNumberE164 = phoneNumberE164,
                role = role.name.lowercase(),
                firebaseUid = firebaseUid,
                pinHash = PinHasher.hash(pin, salt),
                pinSalt = salt
            )
        )
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.DEVICE_BUSINESS_ID] = businessId
            prefs.remove(Keys.PIN_FAILURES)
            prefs.remove(Keys.PIN_LOCKED_UNTIL)
        }
    }

    /**
     * True if the PIN matches. Pure local hash comparison - no network I/O,
     * so this works with zero connectivity (or GSM/SMS-only signal).
     */
    suspend fun verifyPinOffline(businessId: String, enteredPin: String): Boolean {
        val cred = localCredentialDao.getByBusinessId(businessId) ?: return false
        return PinHasher.verify(enteredPin, cred.pinSalt, cred.pinHash)
    }

    /**
     * PIN entry with brute-force protection. Use this (not [verifyPinOffline])
     * from any UI that accepts a typed PIN.
     *
     * After [PinLockoutPolicy.FREE_ATTEMPTS] consecutive wrong PINs the device locks
     * for an escalating delay; after [PinLockoutPolicy.MAX_ATTEMPTS] the stored PIN
     * credential is erased, so the user must complete a full online OTP sign-in.
     * A correct PIN resets the counter. State lives in DataStore and survives
     * process death and sign-out. [now] is injectable for tests.
     */
    suspend fun attemptPinLogin(
        businessId: String,
        enteredPin: String,
        now: Long = System.currentTimeMillis()
    ): PinAttemptResult {
        val prefs = context.sessionDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        val lockedUntil = prefs[Keys.PIN_LOCKED_UNTIL] ?: 0L
        if (lockedUntil > now) {
            // Clamp so a clock set backwards can't produce an absurd wait.
            return PinAttemptResult.LockedOut(
                (lockedUntil - now).coerceAtMost(PinLockoutPolicy.MAX_LOCK_MS)
            )
        }

        if (verifyPinOffline(businessId, enteredPin)) {
            context.sessionDataStore.edit {
                it.remove(Keys.PIN_FAILURES)
                it.remove(Keys.PIN_LOCKED_UNTIL)
            }
            return PinAttemptResult.Success
        }

        val failures = (prefs[Keys.PIN_FAILURES] ?: 0) + 1
        if (PinLockoutPolicy.shouldEraseCredential(failures)) {
            forgetDeviceCredential(businessId) // also clears the counters
            return PinAttemptResult.TooManyAttempts
        }
        val lockMs = PinLockoutPolicy.lockDurationMs(failures)
        context.sessionDataStore.edit {
            it[Keys.PIN_FAILURES] = failures
            if (lockMs > 0) {
                it[Keys.PIN_LOCKED_UNTIL] = now + lockMs
            } else {
                it.remove(Keys.PIN_LOCKED_UNTIL)
            }
        }
        return PinAttemptResult.Wrong(
            attemptsBeforeLockout = PinLockoutPolicy.attemptsBeforeLockout(failures),
            lockedForMs = lockMs
        )
    }

    /** "Use a different number" / "forgot PIN" - removes offline PIN login for this business. */
    suspend fun forgetDeviceCredential(businessId: String) {
        localCredentialDao.deleteByBusinessId(businessId)
        context.sessionDataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_BUSINESS_ID] == businessId) {
                prefs.remove(Keys.DEVICE_BUSINESS_ID)
            }
            prefs.remove(Keys.PIN_FAILURES)
            prefs.remove(Keys.PIN_LOCKED_UNTIL)
        }
    }
}