package com.vestateck.smetracker.data.remote.sync.entities

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.remote.model.MemberRole
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
 * Integration tests for InventorySync.pushPending against a real Firestore
 * emulator + real firestore.rules. Mirrors SaleSyncEmulatorTest's owner/
 * worker split, applied to inventoryCosts instead of saleFinancials, plus
 * the one rule this entity has that Sale doesn't: a worker may push a
 * quantity increase but not a decrease on the shared InventoryItem doc
 * (see firestore.rules' inventory update rule) — legacy protection from
 * before quantity moved to being derived from stock_adjustments (see
 * InventorySync's class doc), but still enforced, so still worth locking
 * down with a test.
 *
 * Requires `firebase emulators:start --only auth,firestore` running
 * locally, and must run on an Android emulator/AVD (10.0.2.2 host mapping).
 */
@RunWith(AndroidJUnit4::class)
class InventorySyncEmulatorTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var db: SMEDatabase
    private val testScope = CoroutineScope(Dispatchers.IO)

    private val ownerPhone = "+256700200001"
    private val workerPhone = "+256700200002"

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

    private fun newInventorySync() = InventorySync(db.inventoryDao(), emulatorRule.firestore, testScope)

    @Test
    fun pushPending_owner_writesItemAndInventoryCost() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        val item = InventoryItem(name = "Sugar 1kg", quantity = 10, costPrice = 2500.0, sellingPrice = 3500.0, pendingSync = true)
        db.inventoryDao().insert(item)

        newInventorySync().pushPending(businessId, ownerPhone, MemberRole.OWNER)

        val remoteItem = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("inventory").document(item.id).get().await()
        assertTrue(remoteItem.exists())

        val remoteCost = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("inventoryCosts").document(item.id).get().await()
        assertTrue("owner push should write inventoryCosts", remoteCost.exists())
        assertEquals(2500.0, remoteCost.getDouble("costPrice"))

        assertEquals(false, db.inventoryDao().getItemById(item.id)?.pendingSync)
    }

    @Test
    fun pushPending_worker_neverAttemptsInventoryCosts() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        // A worker's Add Item dialog never lets them enter a cost price (see
        // InventoryItem's doc comment), but even if costPrice were somehow
        // non-zero locally, the push must never attempt to write it.
        val item = InventoryItem(name = "Rice 2kg", quantity = 5, costPrice = 0.0, sellingPrice = 8000.0, pendingSync = true)
        db.inventoryDao().insert(item)

        newInventorySync().pushPending(businessId, workerPhone, MemberRole.WORKER)

        val remoteItem = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("inventory").document(item.id).get().await()
        assertTrue("worker's new item should still write successfully", remoteItem.exists())

        val remoteCost = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("inventoryCosts").document(item.id).get().await()
        assertTrue("worker push must never create an inventoryCosts doc", !remoteCost.exists())

        assertEquals(false, db.inventoryDao().getItemById(item.id)?.pendingSync)
    }

    @Test
    fun pushPending_workerIncreasingQuantity_isAccepted() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)

        // Owner creates the item remotely first (quantity = 10), so there's
        // an existing document for the worker's update to be checked against.
        val item = InventoryItem(name = "Flour 1kg", quantity = 10, pendingSync = true)
        db.inventoryDao().insert(item)
        newInventorySync().pushPending(businessId, ownerPhone, MemberRole.OWNER)

        // Now simulate the worker restocking (a legitimate quantity increase)
        // on their own device's local copy.
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)
        val dbWorker = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SMEDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            dbWorker.inventoryDao().insert(item.copy(quantity = 15, pendingSync = true))
            InventorySync(dbWorker.inventoryDao(), emulatorRule.firestore, testScope)
                .pushPending(businessId, workerPhone, MemberRole.WORKER)

            assertEquals(false, dbWorker.inventoryDao().getItemById(item.id)?.pendingSync)
            val remoteItem = emulatorRule.firestore
                .collection("businesses").document(businessId)
                .collection("inventory").document(item.id).get().await()
            assertEquals(15L, remoteItem.getLong("quantity"))
        } finally {
            dbWorker.close()
        }
    }

    @Test
    fun pushPending_workerDecreasingQuantity_isDeniedAndStaysPending() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)

        val item = InventoryItem(name = "Salt 1kg", quantity = 10, pendingSync = true)
        db.inventoryDao().insert(item)
        newInventorySync().pushPending(businessId, ownerPhone, MemberRole.OWNER)

        // A worker's local copy somehow ends up with a LOWER quantity than
        // what's remote (this shouldn't normally happen now that quantity is
        // derived from stock_adjustments — see the class doc — but the rule
        // is still live as a backstop, so it's still worth verifying it
        // actually holds).
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)
        val dbWorker = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SMEDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            dbWorker.inventoryDao().insert(item.copy(quantity = 3, pendingSync = true))
            InventorySync(dbWorker.inventoryDao(), emulatorRule.firestore, testScope)
                .pushPending(businessId, workerPhone, MemberRole.WORKER)

            // Denied by the rule -> caught by pushPending's try/catch ->
            // left pendingSync = true, remote quantity untouched.
            assertEquals(true, dbWorker.inventoryDao().getItemById(item.id)?.pendingSync)
            val remoteItem = emulatorRule.firestore
                .collection("businesses").document(businessId)
                .collection("inventory").document(item.id).get().await()
            assertEquals(10L, remoteItem.getLong("quantity"))
        } finally {
            dbWorker.close()
        }
    }
}