package com.vestateck.smetracker.data.remote.auth

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.remote.model.MemberRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises SessionManager.saveBusinessMembership() against a real
 * in-memory Room instance AND the real Context-backed DataStore (same
 * reasoning as SMEDatabaseTest.kt: SessionManager isn't just a DAO
 * interface, it's real DataStore reads/writes plus a real
 * SMEDatabase.clearAllTablesSuspending() call, so a hand-rolled fake
 * wouldn't actually exercise the thing this test cares about - whether the
 * wipe really happens, and really only on a device's first-ever link).
 *
 * CAVEAT: context.sessionDataStore is a real, file-backed DataStore tied to
 * the instrumentation target Context, not an isolated instance per test -
 * unlike the in-memory Room db below, its state can persist across test
 * runs on the same device/emulator if the app under test isn't reinstalled
 * between runs. resetSessionState() in @Before/@After exists to guard
 * against that, using SessionManager's own public API (forgetDeviceCredential
 * / clearSession) rather than reaching into the private DataStore directly.
 */
@RunWith(AndroidJUnit4::class)
class SessionManagerTest {

    private lateinit var db: SMEDatabase
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SMEDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionManager = SessionManager(context, db.localCredentialDao(), db)
        resetSessionState()
    }

    @After
    fun tearDown() = runTest {
        resetSessionState()
        db.close()
    }

    private suspend fun resetSessionState() {
        sessionManager.deviceBusinessId.first()?.let { sessionManager.forgetDeviceCredential(it) }
        sessionManager.clearSession()
        sessionManager.clearLocalDataOwner()
    }

    @Test
    fun wipesLocalRoomDataOnFirstEverBusinessLink() = runTest {
        db.smeDao().insertCustomer(
            Customer(id = "leftover-1", name = "Leftover Test Customer", pendingSync = true)
        )
        assertNull(sessionManager.deviceBusinessId.first()) // sanity: genuinely first-ever

        sessionManager.saveBusinessMembership("biz-new", MemberRole.OWNER)

        assertEquals(emptyList<Customer>(), db.smeDao().getAllCustomers().first())
    }

    @Test
    fun wipesLocalDataWhenADifferentBusinessLinks() = runTest {
        sessionManager.saveBusinessMembership("biz-old", MemberRole.OWNER)
        db.smeDao().insertCustomer(
            Customer(id = "old-biz-customer", name = "Business A", pendingSync = true)
        )

        sessionManager.saveBusinessMembership("biz-new", MemberRole.WORKER)

        assertEquals(emptyList<Customer>(), db.smeDao().getAllCustomers().first())
        assertEquals("biz-new", sessionManager.dataBusinessId.first())
    }

    @Test
    fun keepsUnsyncedDataWhenTheSameBusinessSignsBackIn() = runTest {
        sessionManager.savePinAfterOnlineVerification(
            businessId = "biz-old",
            phoneNumberE164 = "+15555550100",
            role = MemberRole.WORKER,
            firebaseUid = "uid-old",
            pin = "1234"
        )
        sessionManager.saveBusinessMembership("biz-old", MemberRole.WORKER)
        db.smeDao().insertCustomer(
            Customer(id = "offline-work-1", name = "Recorded Offline", pendingSync = true)
        )

        // Stale Firebase session / forgot PIN: the device credential is dropped and
        // the user signs back in by OTP as the SAME business.
        sessionManager.clearSession()
        sessionManager.forgetDeviceCredential("biz-old")
        sessionManager.saveBusinessMembership("biz-old", MemberRole.WORKER)

        assertEquals(listOf("offline-work-1"), db.smeDao().getAllCustomers().first().map { it.id })
    }

    @Test
    fun isDataOwnedByIsFalseOnlyForADifferentBusiness() = runTest {
        assertTrue(sessionManager.isDataOwnedBy("anything")) // no marker yet (legacy/fresh)

        sessionManager.saveBusinessMembership("biz-a", MemberRole.OWNER)

        assertTrue(sessionManager.isDataOwnedBy("biz-a"))
        assertFalse(sessionManager.isDataOwnedBy("biz-b"))
    }

    @Test
    fun firstEverLinkOnAnUpgradedInstallWithoutMarkerKeepsSameBusinessData() = runTest {
        // An install from before the marker existed: deviceBusinessId is set, the
        // marker is not. Signing back in to that same business must not wipe.
        sessionManager.savePinAfterOnlineVerification(
            businessId = "biz-legacy",
            phoneNumberE164 = "+15555550100",
            role = MemberRole.OWNER,
            firebaseUid = "uid-legacy",
            pin = "1234"
        )
        db.smeDao().insertCustomer(
            Customer(id = "legacy-1", name = "Legacy", pendingSync = true)
        )

        sessionManager.saveBusinessMembership("biz-legacy", MemberRole.OWNER)

        assertEquals(listOf("legacy-1"), db.smeDao().getAllCustomers().first().map { it.id })
    }

    @Test
    fun persistsNewBusinessIdAndRoleRegardlessOfWipe() = runTest {
        sessionManager.saveBusinessMembership("biz-new", MemberRole.OWNER)

        val state = sessionManager.sessionState.first()
        assertEquals("biz-new", state.businessId)
        assertEquals(MemberRole.OWNER, state.role)
    }

    // ---- PIN lockout (attemptPinLogin) ----

    private suspend fun savePin(pin: String = "1234") {
        sessionManager.savePinAfterOnlineVerification(
            businessId = "biz-pin",
            phoneNumberE164 = "+15555550101",
            role = MemberRole.OWNER,
            firebaseUid = "uid-pin",
            pin = pin
        )
    }

    @Test
    fun correctPinSucceedsAndResetsFailureCount() = runTest {
        savePin()
        val t0 = 1_000_000L
        repeat(3) { assertTrue(sessionManager.attemptPinLogin("biz-pin", "0000", t0) is PinAttemptResult.Wrong) }

        assertEquals(PinAttemptResult.Success, sessionManager.attemptPinLogin("biz-pin", "1234", t0))

        // Counter was reset, so four more wrong tries still don't lock.
        repeat(4) {
            val r = sessionManager.attemptPinLogin("biz-pin", "0000", t0)
            assertTrue(r is PinAttemptResult.Wrong && r.lockedForMs == 0L)
        }
    }

    @Test
    fun fifthWrongPinLocksAndBlocksEvenTheCorrectPin() = runTest {
        savePin()
        val t0 = 1_000_000L
        repeat(4) { sessionManager.attemptPinLogin("biz-pin", "0000", t0) }

        val fifth = sessionManager.attemptPinLogin("biz-pin", "0000", t0)
        assertTrue(fifth is PinAttemptResult.Wrong && fifth.lockedForMs == 30_000L)

        val duringLock = sessionManager.attemptPinLogin("biz-pin", "1234", t0 + 10_000L)
        assertTrue(duringLock is PinAttemptResult.LockedOut)

        val afterLock = sessionManager.attemptPinLogin("biz-pin", "1234", t0 + 31_000L)
        assertEquals(PinAttemptResult.Success, afterLock)
    }

    @Test
    fun tenthWrongPinErasesTheStoredCredential() = runTest {
        savePin()
        var now = 1_000_000L
        var last: PinAttemptResult = PinAttemptResult.Success
        repeat(10) {
            now += 20 * 60_000L // always past any lock window
            last = sessionManager.attemptPinLogin("biz-pin", "0000", now)
        }

        assertEquals(PinAttemptResult.TooManyAttempts, last)
        assertNull(sessionManager.getLocalCredential("biz-pin"))
        assertNull(sessionManager.deviceBusinessId.first())
    }
}
