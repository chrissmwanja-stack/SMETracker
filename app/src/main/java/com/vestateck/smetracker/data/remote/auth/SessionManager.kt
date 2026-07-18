package com.example.smetracker.data.remote.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.smetracker.data.remote.model.MemberRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
}

class SessionManager(private val context: Context) {

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

    /** Called right after phone auth succeeds, before phoneIndex is known. */
    suspend fun savePhoneNumber(phoneNumberE164: String) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.PHONE] = phoneNumberE164
        }
    }

    /** Called once phoneIndex lookup resolves (post sign-in or post sign-up). */
    suspend fun saveBusinessMembership(businessId: String, role: MemberRole) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.BUSINESS_ID] = businessId
            prefs[Keys.ROLE] = role.name.lowercase()
        }
    }

    suspend fun clearSession() {
        context.sessionDataStore.edit { it.clear() }
    }
}