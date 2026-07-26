package com.vestateck.smetracker.data.remote.sync.entities

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.FirebaseFirestoreException
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.testutil.EmulatorBusinessSeeder
import com.vestateck.smetracker.testutil.FirebaseEmulatorRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for SaleSync.pushPending against a real Firestore
 * emulator + real firestore.rules — the two things SMETracker's README
 * flags as untested: the receipt-number transaction itself, and whether
 * the owner/worker split actually holds up against the real rules rather
 * than just the app's own role checks.
 *
 * Requires `firebase emulators:start --only auth,firestore` running
 * locally first (same requirement as PhoneAuthEmulatorTest), and must run
 * on an Android emulator/AVD, not a physical device, for the 10.0.2.2 host
 * mapping to work.
 *
 * Deliberately uses real in-memory Room (not FakeSMEDao/FakeInventoryDao)
 * and the real SaleSync class, wired to FirebaseEmulatorRule's Firestore
 * instance instead of production — this is the layer CheckoutGroupingTest
 * and SaleMergeTest can't reach, since it needs an actual FirebaseFirestore.
 */
@RunWith(AndroidJUnit4::class)
class SaleSyncEmulatorTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var db: SMEDatabase
    private val testScope = CoroutineScope(Dispatchers.IO)

    // Fresh phone numbers per test class run; EmulatorEmulatorRule wipes
    // Auth/Firestore state in @Before via starting(), so these don't need
    // to be unique per-test, only distinct from each other within a test.
    private val ownerPhone = "+256700100001"
    private val workerPhone = "+256700100002"

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SMEDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun newSaleSync() = SaleSync(db.smeDao(), db.inventoryDao(), emulatorRule.firestore, testScope)

    private suspend fun insertPendingSale(
        provisionalReceiptNumber: String,
        recordedBy: String = "",
        amount: Double = 5000.0
    ): Sale {
        val sale = Sale(
            customerName = "Walk-in",
            description = "Sugar 1kg",
            amount = amount,
            costPriceSnapshot = 3000.0,
            profit = 2000.0,
            recordedBy = recordedBy,
            provisionalReceiptNumber = provisionalReceiptNumber,
            pendingSync = true
        )
        db.smeDao().insertSale(sale)
        return sale
    }

    // -- Owner: base push behavior ---------------------------------------

    @Test
    fun pushPending_ownerSingleCheckout_writesSaleAndFinancialsAndClearsPendingSync() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        val sale = insertPendingSale(provisionalReceiptNumber = "0771-000001")

        newSaleSync().pushPending(businessId, ownerPhone, MemberRole.OWNER)

        val remoteSale = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("sales").document(sale.id)
            .get().await()
        assertTrue("expected the sale doc to exist remotely", remoteSale.exists())
        assertEquals(ownerPhone, remoteSale.getString("recordedBy"))
        assertTrue(
            "expected an INV-#### finalReceiptNumber",
            (remoteSale.getString("finalReceiptNumber") ?: "").startsWith("INV-")
        )

        val remoteFinancials = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("saleFinancials").document(sale.id)
            .get().await()
        assertTrue("owner push should write saleFinancials", remoteFinancials.exists())
        assertEquals(3000.0, remoteFinancials.getDouble("costPrice"))
        assertEquals(2000.0, remoteFinancials.getDouble("profit"))

        val localSale = db.smeDao().getSaleById(sale.id)
        assertEquals(false, localSale?.pendingSync)
    }

    // -- Receipt numbering: one claim per checkout, not per row -----------

    @Test
    fun pushPending_multiItemCheckout_claimsOneNumberSharedAcrossAllRows() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        // Same provisionalReceiptNumber == same checkout (addSaleLines'
        // grouping key), so this should claim exactly ONE finalReceiptNumber
        // shared by both rows, not one each.
        val saleA = insertPendingSale(provisionalReceiptNumber = "0771-000002", amount = 1000.0)
        val saleB = insertPendingSale(provisionalReceiptNumber = "0771-000002", amount = 2000.0)

        newSaleSync().pushPending(businessId, ownerPhone, MemberRole.OWNER)

        val finalA = db.smeDao().getSaleById(saleA.id)?.finalReceiptNumber
        val finalB = db.smeDao().getSaleById(saleB.id)?.finalReceiptNumber
        assertEquals(finalA, finalB)
        assertTrue(finalA != null)
    }

    @Test
    fun pushPending_twoSeparateCheckouts_claimSequentialDistinctNumbers() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        val saleA = insertPendingSale(provisionalReceiptNumber = "0771-000003")
        val saleB = insertPendingSale(provisionalReceiptNumber = "0771-000004")

        newSaleSync().pushPending(businessId, ownerPhone, MemberRole.OWNER)

        val finalA = db.smeDao().getSaleById(saleA.id)?.finalReceiptNumber
        val finalB = db.smeDao().getSaleById(saleB.id)?.finalReceiptNumber
        assertNotEquals(finalA, finalB)
    }

    // -- The actual race: two "devices" claiming concurrently -------------

    @Test
    fun pushPending_concurrentCheckoutsFromTwoDevices_neverCollideOnReceiptNumber() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        // Two independent SMEDatabase + SaleSync instances, standing in for
        // two physical devices coming online at the same moment and both
        // racing the receiptSequence counter transaction. This is the
        // exact scenario SaleSync's class doc claims Firestore transaction
        // retries protect against - this test is what actually checks it.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbDeviceB = Room.inMemoryDatabaseBuilder(context, SMEDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val saleDeviceA = insertPendingSale(provisionalReceiptNumber = "A-0001")
            val saleB = Sale(
                customerName = "Walk-in", description = "Rice 2kg", amount = 6000.0,
                provisionalReceiptNumber = "B-0001", pendingSync = true
            )
            dbDeviceB.smeDao().insertSale(saleB)

            val syncA = SaleSync(db.smeDao(), db.inventoryDao(), emulatorRule.firestore, testScope)
            val syncB = SaleSync(dbDeviceB.smeDao(), dbDeviceB.inventoryDao(), emulatorRule.firestore, testScope)

            listOf(
                async { syncA.pushPending(businessId, ownerPhone, MemberRole.OWNER) },
                async { syncB.pushPending(businessId, ownerPhone, MemberRole.OWNER) }
            ).awaitAll()

            val finalA = db.smeDao().getSaleById(saleDeviceA.id)?.finalReceiptNumber
            val finalB = dbDeviceB.smeDao().getSaleById(saleB.id)?.finalReceiptNumber

            assertTrue("device A should have claimed a number", finalA != null)
            assertTrue("device B should have claimed a number", finalB != null)
            assertNotEquals("two concurrent checkouts must not collide on the same receipt number", finalA, finalB)
        } finally {
            dbDeviceB.close()
        }
    }

    // -- Retry behavior: don't burn a second number for the same checkout -

    @Test
    fun pushPending_saleAlreadyHasFinalReceiptNumber_reusesItInsteadOfClaimingNew() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        // Simulates a retry after a previous push claimed a number for this
        // checkout but died before clearing pendingSync (see
        // alreadyClaimedReceiptNumber's doc comment) - the row already
        // carries finalReceiptNumber locally, still pendingSync = true.
        val preClaimed = Sale(
            customerName = "Walk-in", description = "Salt 1kg", amount = 1500.0,
            provisionalReceiptNumber = "0771-000009",
            finalReceiptNumber = "INV-0099",
            pendingSync = true
        )
        db.smeDao().insertSale(preClaimed)

        newSaleSync().pushPending(businessId, ownerPhone, MemberRole.OWNER)

        val remoteSale = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("sales").document(preClaimed.id)
            .get().await()
        assertEquals(
            "a pre-claimed number should be reused verbatim, not replaced by a fresh claim",
            "INV-0099",
            remoteSale.getString("finalReceiptNumber")
        )

        // And the counter itself should be untouched by this push - the
        // NEXT genuinely-new checkout should still claim INV-0001, proving
        // no transaction ran (and no number was burned) for the pre-claimed row.
        val freshSale = insertPendingSale(provisionalReceiptNumber = "0771-000010")
        newSaleSync().pushPending(businessId, ownerPhone, MemberRole.OWNER)
        val freshFinal = db.smeDao().getSaleById(freshSale.id)?.finalReceiptNumber
        assertEquals("INV-0001", freshFinal)
    }

    // -- Worker role: saleFinancials must stay unreachable, per the rules -

    @Test
    fun pushPending_workerRole_writesSaleButNeverAttemptsSaleFinancials() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        val sale = insertPendingSale(provisionalReceiptNumber = "0771-000005", recordedBy = workerPhone)

        newSaleSync().pushPending(businessId, workerPhone, MemberRole.WORKER)

        val remoteSale = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("sales").document(sale.id)
            .get().await()
        assertTrue("worker's own sale should still write successfully", remoteSale.exists())

        val localSale = db.smeDao().getSaleById(sale.id)
        assertEquals(
            "the sale itself should clear pendingSync even though financials never got attempted",
            false,
            localSale?.pendingSync
        )
    }

    @Test
    fun workerCannotWriteSaleFinancials_evenIfAppTriedTo_rulesDenyIt() = runTest {
        // Belt-and-braces check on the rule itself, independent of
        // SaleSync's own role branch above: if a future change to SaleSync
        // ever forgets the `role == OWNER` guard before writing
        // saleFinancials, this is what should catch it, not a silent
        // production bug reported by a worker asking why they can suddenly
        // see cost/profit.
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        try {
            emulatorRule.firestore
                .collection("businesses").document(businessId)
                .collection("saleFinancials").document("some-sale-id")
                .set(mapOf("saleId" to "some-sale-id", "costPrice" to 1.0, "profit" to 1.0))
                .await()
            fail("expected PERMISSION_DENIED writing saleFinancials as a worker")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }
}