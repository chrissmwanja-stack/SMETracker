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
import org.junit.Assert.assertNull
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
    fun doesNotWipeLocalDataWhenDeviceAlreadyLinkedBusiness() = runTest {
        // Simulate a device that already completed one full link + PIN setup
        // for an earlier business - deviceBusinessId is only set by this call,
        // same as the real PIN-setup flow in AuthNavGate.
        sessionManager.savePinAfterOnlineVerification(
            businessId = "biz-old",
            phoneNumberE164 = "+15555550100",
            role = MemberRole.OWNER,
            firebaseUid = "uid-old",
            pin = "1234"
        )

        db.smeDao().insertCustomer(
            Customer(id = "offline-work-1", name = "Recorded Offline", pendingSync = true)
        )

        // Reassigned to a NEW business on the same device - accepted tradeoff
        // per SMEDatabase.clearSyncedDataSuspending()'s doc, not this method's
        // job to guard against.
        sessionManager.saveBusinessMembership("biz-new", MemberRole.WORKER)

        assertEquals(listOf("offline-work-1"), db.smeDao().getAllCustomers().first().map { it.id })
    }

    @Test
    fun persistsNewBusinessIdAndRoleRegardlessOfWipe() = runTest {
        sessionManager.saveBusinessMembership("biz-new", MemberRole.OWNER)

        val state = sessionManager.sessionState.first()
        assertEquals("biz-new", state.businessId)
        assertEquals(MemberRole.OWNER, state.role)
    }
}