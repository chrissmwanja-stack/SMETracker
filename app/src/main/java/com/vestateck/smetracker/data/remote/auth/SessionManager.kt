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
     * deviceBusinessId stays null until this device has completed a full
     * link for SOME business (see its doc comment above - it's only set
     * once PIN setup finishes, right after this). So null here means this
     * is that device's first-ever business link, and any local Room data
     * already sitting there predates a legitimate business relationship on
     * this device - leftover dev/test rows, or anything recorded before
     * Firebase Auth was wired up. Must be discarded before SyncEngine can
     * start (see MainActivity's onEnterApp -> syncEngine.start()), or
     * pushAllPending() would upload it into whatever business is being
     * linked now. See SMEDatabase.clearAllTablesSuspending() doc.
     *
     * A device being reassigned to a NEW business after a normal sign-out
     * from a PREVIOUS one doesn't hit this branch (deviceBusinessId is
     * already set) - that's a separate, already-accepted tradeoff, see
     * SMEDatabase.clearSyncedDataSuspending()'s doc comment.
     */
    suspend fun saveBusinessMembership(businessId: String, role: MemberRole) {
        if (deviceBusinessId.first() == null) {
            database.clearAllTablesSuspending()
        }
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.BUSINESS_ID] = businessId
            prefs[Keys.ROLE] = role.name.lowercase()
        }
    }

    /**
     * Normal sign-out. Intentionally leaves Keys.DEVICE_BUSINESS_ID and the
     * matching local_credentials row alone - see the Keys.DEVICE_BUSINESS_ID
     * doc comment above for why. Call forgetDeviceCredential() as well if the
     * user explicitly wants this device to forget the PIN too.
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