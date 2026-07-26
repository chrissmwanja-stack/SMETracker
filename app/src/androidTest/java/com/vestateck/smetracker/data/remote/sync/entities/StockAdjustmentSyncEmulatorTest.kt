package com.vestateck.smetracker.data.remote.sync.entities

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.StockAdjustment
import com.vestateck.smetracker.data.entities.StockAdjustmentReason
import com.vestateck.smetracker.testutil.EmulatorBusinessSeeder
import com.vestateck.smetracker.testutil.FirebaseEmulatorRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for StockAdjustmentSync.pushPending against a real
 * Firestore emulator + real firestore.rules — specifically the create
 * rule's reason/delta-sign combinations, which no other test in this
 * project exercises against real rules: a worker may create an INCOMING
 * adjustment only with a positive delta, or a SALE adjustment only with a
 * negative delta; anything else (RECOUNT, or a mismatched sign) is
 * owner-only. Getting this wrong in either direction is a real business
 * risk - too loose and a worker can silently rewrite stock counts outside
 * the sale/restock flow; too strict and legitimate restocks/sales start
 * silently failing to sync.
 *
 * Requires `firebase emulators:start --only auth,firestore` running
 * locally, and must run on an Android emulator/AVD (10.0.2.2 host mapping).
 */
@RunWith(AndroidJUnit4::class)
class StockAdjustmentSyncEmulatorTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var db: SMEDatabase
    private val testScope = CoroutineScope(Dispatchers.IO)

    private val ownerPhone = "+256700400001"
    private val workerPhone = "+256700400002"

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SMEDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun newSync() = StockAdjustmentSync(db.inventoryDao(), emulatorRule.firestore, testScope)

    /** Every adjustment has a real local FK to inventory_items, so seed one first. */
    private suspend fun seedLocalItem(): InventoryItem {
        val item = InventoryItem(name = "Sugar 1kg", quantity = 10, pendingSync = false)
        db.inventoryDao().insert(item)
        return item
    }

    private suspend fun pushOneAdjustment(
        itemId: String,
        delta: Int,
        reason: StockAdjustmentReason,
        recordedBy: String
    ): StockAdjustment {
        val adjustment = StockAdjustment(itemId = itemId, delta = delta, reason = reason, recordedBy = recordedBy, pendingSync = true)
        db.inventoryDao().insertStockAdjustment(adjustment)
        newSync().pushPending(businessIdForTest, recordedBy)
        return adjustment
    }

    // businessIdForTest is set per-test in @Before-adjacent setup below,
    // since seeding requires an owner sign-in first (can't happen in @Before
    // itself without every test needing it, including ones that don't).
    private lateinit var businessIdForTest: String

    private suspend fun setUpBusinessWithWorker() {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        businessIdForTest = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessIdForTest, workerPhone)
    }

    @Test
    fun worker_incomingWithPositiveDelta_isAccepted() = runTest {
        setUpBusinessWithWorker()
        val item = seedLocalItem()
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        val adj = pushOneAdjustment(item.id, delta = 5, reason = StockAdjustmentReason.INCOMING, recordedBy = workerPhone)

        assertEquals(false, db.inventoryDao().getPendingSyncAdjustments().any { it.id == adj.id })
        val remote = emulatorRule.firestore
            .collection("businesses").document(businessIdForTest)
            .collection("stockAdjustments").document(adj.id).get().await()
        assertTrue(remote.exists())
    }

    @Test
    fun worker_saleWithNegativeDelta_isAccepted() = runTest {
        setUpBusinessWithWorker()
        val item = seedLocalItem()
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        val adj = pushOneAdjustment(item.id, delta = -2, reason = StockAdjustmentReason.SALE, recordedBy = workerPhone)

        val remote = emulatorRule.firestore
            .collection("businesses").document(businessIdForTest)
            .collection("stockAdjustments").document(adj.id).get().await()
        assertTrue(remote.exists())
    }

    @Test
    fun worker_incomingWithNegativeDelta_isDeniedAndStaysPending() = runTest {
        setUpBusinessWithWorker()
        val item = seedLocalItem()
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        // Wrong sign for INCOMING - should be denied, not silently accepted.
        val adj = pushOneAdjustment(item.id, delta = -5, reason = StockAdjustmentReason.INCOMING, recordedBy = workerPhone)

        assertTrue(db.inventoryDao().getPendingSyncAdjustments().any { it.id == adj.id })
        val remote = emulatorRule.firestore
            .collection("businesses").document(businessIdForTest)
            .collection("stockAdjustments").document(adj.id).get().await()
        assertTrue("a denied write must never land remotely", !remote.exists())
    }

    @Test
    fun worker_recount_isDeniedAndStaysPending() = runTest {
        setUpBusinessWithWorker()
        val item = seedLocalItem()
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        // RECOUNT is owner-only regardless of delta sign - a worker
        // shouldn't be able to unilaterally overwrite a physical stock
        // count without an owner's involvement.
        val adj = pushOneAdjustment(item.id, delta = 3, reason = StockAdjustmentReason.RECOUNT, recordedBy = workerPhone)

        assertTrue(db.inventoryDao().getPendingSyncAdjustments().any { it.id == adj.id })
        val remote = emulatorRule.firestore
            .collection("businesses").document(businessIdForTest)
            .collection("stockAdjustments").document(adj.id).get().await()
        assertTrue(!remote.exists())
    }

    @Test
    fun owner_recount_isAccepted() = runTest {
        setUpBusinessWithWorker()
        val item = seedLocalItem()
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)

        val adj = pushOneAdjustment(item.id, delta = 3, reason = StockAdjustmentReason.RECOUNT, recordedBy = ownerPhone)

        val remote = emulatorRule.firestore
            .collection("businesses").document(businessIdForTest)
            .collection("stockAdjustments").document(adj.id).get().await()
        assertTrue("an owner should be able to record any adjustment type", remote.exists())
    }
}