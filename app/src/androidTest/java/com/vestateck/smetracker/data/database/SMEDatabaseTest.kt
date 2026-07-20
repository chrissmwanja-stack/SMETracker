package com.vestateck.smetracker.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.entities.StockAdjustment
import com.vestateck.smetracker.data.entities.StockAdjustmentReason
import com.vestateck.smetracker.data.entities.Task
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises SMEDatabase.clearSyncedDataSuspending() against a real
 * in-memory Room instance (not FakeSMEDao/FakeInventoryDao) - the JVM unit
 * tests fake out the DAOs entirely, so they can't catch a mistake in the
 * actual SQL, the @Transaction wiring, or a table this method forgets to
 * touch. This is deliberately the one thing in the project that needs a
 * real Room database rather than a fake, since the method under test IS a
 * Room transaction.
 *
 * Every entity gets one synced (pendingSync = false) and one unsynced
 * (pendingSync = true) row; clearSyncedDataSuspending() should delete
 * exactly the synced ones and leave the unsynced ones untouched, across
 * all 7 tables in a single call.
 *
 * Both StockAdjustment rows are attached to the SAME (unsynced/surviving)
 * InventoryItem rather than one each - StockAdjustment has a real FK to
 * inventory_items with ON DELETE CASCADE, so an adjustment on the item
 * that gets deleted for being synced would disappear via cascade, not via
 * deleteSyncedAdjustments() - that would pass without actually exercising
 * the query this test exists to check.
 */
@RunWith(AndroidJUnit4::class)
class SMEDatabaseTest {

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
    fun clearSyncedDataSuspending_deletesOnlySyncedRows_acrossAllSevenEntities() = runTest {
        // -- Customer (no FK dependencies) ------------------------------
        db.smeDao().insertCustomer(Customer(id = "cust-synced", name = "Synced Customer", pendingSync = false))
        db.smeDao().insertCustomer(Customer(id = "cust-unsynced", name = "Unsynced Customer", pendingSync = true))

        // -- Debt (nullable FK to Customer - left null, out of scope here) --
        db.smeDao().insertDebt(Debt(id = "debt-synced", customerName = "Jane", amount = 1000.0, pendingSync = false))
        db.smeDao().insertDebt(Debt(id = "debt-unsynced", customerName = "Jane", amount = 2000.0, pendingSync = true))

        // -- Expense (no FK dependencies) -------------------------------
        db.smeDao().insertExpense(Expense(id = "exp-synced", description = "Rent", amount = 500.0, pendingSync = false))
        db.smeDao().insertExpense(Expense(id = "exp-unsynced", description = "Fuel", amount = 50.0, pendingSync = true))

        // -- Task (no FK dependencies) -----------------------------------
        db.smeDao().insertTask(Task(id = "task-synced", title = "Restock", pendingSync = false))
        db.smeDao().insertTask(Task(id = "task-unsynced", title = "Call supplier", pendingSync = true))

        // -- Sale (nullable FK to Customer - left null, out of scope here) --
        db.smeDao().insertSale(Sale(id = "sale-synced", customerName = "Walk-in", description = "Soap", amount = 3000.0, pendingSync = false))
        db.smeDao().insertSale(Sale(id = "sale-unsynced", customerName = "Walk-in", description = "Sugar", amount = 4000.0, pendingSync = true))

        // -- InventoryItem (no FK dependencies) --------------------------
        // Only ONE item, deliberately unsynced - see class doc on why both
        // StockAdjustment rows below hang off this one item rather than
        // one each.
        db.inventoryDao().insert(InventoryItem(id = "item-unsynced", name = "Sugar", pendingSync = true))

        // -- StockAdjustment (real FK to InventoryItem, CASCADE) ----------
        db.inventoryDao().insertStockAdjustment(
            StockAdjustment(id = "adj-synced", itemId = "item-unsynced", delta = 10, reason = StockAdjustmentReason.INCOMING, pendingSync = false)
        )
        db.inventoryDao().insertStockAdjustment(
            StockAdjustment(id = "adj-unsynced", itemId = "item-unsynced", delta = -2, reason = StockAdjustmentReason.SALE, pendingSync = true)
        )

        // -- Act ------------------------------------------------------------
        db.clearSyncedDataSuspending()

        // -- Assert: synced rows gone, unsynced rows survive, per entity ----
        assertEquals(listOf("cust-unsynced"), db.smeDao().getAllCustomers().first().map { it.id })
        assertEquals(listOf("debt-unsynced"), db.smeDao().getAllDebts().first().map { it.id })
        assertEquals(listOf("exp-unsynced"), db.smeDao().getAllExpenses().first().map { it.id })
        assertEquals(listOf("task-unsynced"), db.smeDao().getPendingTasks().first().map { it.id })
        assertEquals(listOf("sale-unsynced"), db.smeDao().getAllSales().first().map { it.id })
        assertEquals(listOf("item-unsynced"), db.inventoryDao().getAllItems().first().map { it.id })
        assertEquals(
            listOf("adj-unsynced"),
            db.inventoryDao().getAdjustmentsForItem("item-unsynced").first().map { it.id }
        )
    }
}
