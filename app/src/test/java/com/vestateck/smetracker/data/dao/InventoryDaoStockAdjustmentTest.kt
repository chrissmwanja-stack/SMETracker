package com.vestateck.smetracker.data.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.StockAdjustment
import com.vestateck.smetracker.data.entities.StockAdjustmentReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises InventoryDao.applyRemoteStockAdjustment against a real
 * in-memory Room instance rather than FakeInventoryDao — like
 * SMEDatabaseTest, this is deliberately one of the few things in the
 * project that needs real Room/SQLite, since applyRemoteStockAdjustment IS
 * a Room @Transaction and its whole job is coordinating two real UPDATE/
 * INSERT statements correctly.
 *
 * This is the regression test for the offline-oversell sync bug: quantity
 * used to be synced as a last-write-wins field on the whole InventoryItem
 * document, so two devices editing the same item's stock offline could
 * have one device's change silently overwrite the other's. Quantity is now
 * derived by replaying stock_adjustments (each its own additive doc), and
 * this is what verifies that replay actually merges correctly instead of
 * clobbering — see InventorySync's pull listener and StockAdjustmentSync
 * for the sync-layer half of this fix.
 */
@RunWith(AndroidJUnit4::class)
class InventoryDaoStockAdjustmentTest {

    private lateinit var db: SMEDatabase

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

    @Test
    fun applyRemoteStockAdjustment_newAdjustment_appliesDeltaAndLogsIt() = runTest {
        db.inventoryDao().insert(InventoryItem(id = "item-1", name = "Sugar", quantity = 10, pendingSync = false))

        db.inventoryDao().applyRemoteStockAdjustment(
            StockAdjustment(id = "adj-remote-1", itemId = "item-1", delta = -3, reason = StockAdjustmentReason.SALE, pendingSync = false)
        )

        assertEquals(7, db.inventoryDao().getItemById("item-1")!!.quantity)
        assertEquals(
            listOf("adj-remote-1"),
            db.inventoryDao().getAdjustmentsForItem("item-1").first().map { it.id }
        )
    }

    @Test
    fun applyRemoteStockAdjustment_appliedByRemote_doesNotFlagItemPendingSync() = runTest {
        // Item starts already synced - a remote-origin adjustment catching
        // this device's local quantity cache up to match shouldn't cause an
        // unnecessary echo push of the whole InventoryItem doc (see
        // adjustStockFromRemote's doc comment in InventoryDao).
        db.inventoryDao().insert(InventoryItem(id = "item-1", name = "Sugar", quantity = 10, pendingSync = false))

        db.inventoryDao().applyRemoteStockAdjustment(
            StockAdjustment(id = "adj-remote-1", itemId = "item-1", delta = -3, reason = StockAdjustmentReason.SALE, pendingSync = false)
        )

        assertTrue(db.inventoryDao().getPendingSyncItems().none { it.id == "item-1" })
    }

    @Test
    fun applyRemoteStockAdjustment_echoOfOwnLocalAdjustment_isNoOp() = runTest {
        // This is the dedupe path: a device applies an adjustment locally
        // (applyStockAdjustment, e.g. from its own sale), then Firestore's
        // snapshot listener echoes that SAME document back with the SAME
        // id. Before this fix, blindly re-inserting it would have been
        // harmless (REPLACE), but the surrounding change that made this
        // method necessary was replaying the delta on every pull - without
        // the dedupe check, this echo would double-apply the delta.
        db.inventoryDao().insert(InventoryItem(id = "item-1", name = "Sugar", quantity = 10, pendingSync = false))

        val ownAdjustment = StockAdjustment(id = "adj-local-1", itemId = "item-1", delta = -4, reason = StockAdjustmentReason.SALE)
        db.inventoryDao().applyStockAdjustment(ownAdjustment) // local sale: 10 -> 6

        // Echo-back from Firestore, same id, pendingSync flipped to false
        // (as StockAdjustmentSync's pull listener constructs it) but
        // otherwise the same adjustment.
        db.inventoryDao().applyRemoteStockAdjustment(ownAdjustment.copy(pendingSync = false))

        assertEquals(6, db.inventoryDao().getItemById("item-1")!!.quantity)
        assertEquals(1, db.inventoryDao().getAdjustmentsForItem("item-1").first().size)
    }

    @Test
    fun applyRemoteStockAdjustment_mergesWithConcurrentLocalSale_insteadOfClobbering() = runTest {
        // The actual "two offline devices, one item" scenario. Both devices
        // start from quantity = 10. THIS device sells 3 locally
        // (applyStockAdjustment). ANOTHER device, also offline, sold 4 -
        // its adjustment arrives later via sync (applyRemoteStockAdjustment).
        // Correct combined result is 10 - 3 - 4 = 3. The bug this fix
        // replaces would have had whichever device's InventoryItem doc push
        // landed last silently overwrite the other's quantity outright
        // (e.g. ending at 7 or 6, with one sale's stock effect vanishing
        // even though both Sale records exist) instead of merging both.
        db.inventoryDao().insert(InventoryItem(id = "item-1", name = "Sugar", quantity = 10, pendingSync = false))

        db.inventoryDao().applyStockAdjustment(
            StockAdjustment(id = "adj-this-device", itemId = "item-1", delta = -3, reason = StockAdjustmentReason.SALE)
        )
        db.inventoryDao().applyRemoteStockAdjustment(
            StockAdjustment(id = "adj-other-device", itemId = "item-1", delta = -4, reason = StockAdjustmentReason.SALE, pendingSync = false)
        )

        assertEquals(3, db.inventoryDao().getItemById("item-1")!!.quantity)
        assertEquals(
            setOf("adj-this-device", "adj-other-device"),
            db.inventoryDao().getAdjustmentsForItem("item-1").first().map { it.id }.toSet()
        )
    }

    @Test
    fun applyRemoteStockAdjustment_combinedOversell_leavesQuantityNegative() = runTest {
        // Both devices validly saw quantity = 5 and each sold against it
        // while offline; combined it wasn't enough. This isn't the sync bug
        // - it's the genuine oversell case the Reconciliation screen's
        // "Stock" tab now surfaces (InventoryDao.getOversoldItems). The
        // adjustments themselves should still merge correctly (additively)
        // even though the result goes negative.
        db.inventoryDao().insert(InventoryItem(id = "item-1", name = "Sugar", quantity = 5, pendingSync = false))

        db.inventoryDao().applyStockAdjustment(
            StockAdjustment(id = "adj-this-device", itemId = "item-1", delta = -3, reason = StockAdjustmentReason.SALE)
        )
        db.inventoryDao().applyRemoteStockAdjustment(
            StockAdjustment(id = "adj-other-device", itemId = "item-1", delta = -4, reason = StockAdjustmentReason.SALE, pendingSync = false)
        )

        assertEquals(-2, db.inventoryDao().getItemById("item-1")!!.quantity)
        assertEquals(listOf("item-1"), db.inventoryDao().getOversoldItems().first().map { it.id })
    }
}