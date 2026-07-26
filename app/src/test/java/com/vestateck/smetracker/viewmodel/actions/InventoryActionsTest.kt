package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.fakes.FakeInventoryDao
import com.vestateck.smetracker.fakes.FakeSMEDao
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.BulkInventoryRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InventoryActionsTest {

    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var actions: InventoryActions

    private val ownerSession: suspend () -> Pair<String, Boolean> = { "256772000111" to true }
    private val workerSession: suspend () -> Pair<String, Boolean> = { "256772000222" to false }

    private fun buildActions(session: suspend () -> Pair<String, Boolean>): InventoryActions {
        inventoryDao = FakeInventoryDao()
        val repository = SMERepository(FakeSMEDao(), inventoryDao)
        return InventoryActions(repository, syncEngine = null, currentSession = session)
    }

    @Before
    fun setUp() {
        actions = buildActions(ownerSession)
    }

    @Test
    fun `upsertInventoryItem with a blank id inserts as owner-reconciled and logs initial stock`() = runTest {
        val draft = InventoryItem(id = "", name = "Bar of soap", quantity = 20, sellingPrice = 3_000.0, costPrice = 2_000.0)

        actions.upsertInventoryItem(draft)

        val inserted = inventoryDao.itemsFlow.value.single()
        assertNotEquals("", inserted.id)
        assertEquals("256772000111", inserted.recordedBy)
        assertTrue(inserted.costReconciled)
        assertEquals(1, inventoryDao.adjustmentsFlow.value.size)
        assertEquals("Initial stock", inventoryDao.adjustmentsFlow.value.single().note)
    }

    @Test
    fun `upsertInventoryItem with a blank id from a worker is not cost-reconciled`() = runTest {
        actions = buildActions(workerSession)
        val draft = InventoryItem(id = "", name = "New item", quantity = 5, sellingPrice = 1_000.0)

        actions.upsertInventoryItem(draft)

        val inserted = inventoryDao.itemsFlow.value.single()
        assertFalse(inserted.costReconciled)
    }

    @Test
    fun `upsertInventoryItem editing an existing item as owner marks it cost-reconciled`() = runTest {
        val existing = InventoryItem(id = "item-1", name = "Soap", quantity = 10, costReconciled = false)
        inventoryDao.itemsFlow.value = listOf(existing)

        actions.upsertInventoryItem(existing.copy(costPrice = 1_500.0))

        val updated = inventoryDao.itemsFlow.value.first { it.id == "item-1" }
        assertTrue(updated.costReconciled)
        assertEquals(1_500.0, updated.costPrice, 0.0001)
    }

    @Test
    fun `upsertInventoryItem editing an existing item as a worker leaves cost-reconciled unchanged`() = runTest {
        actions = buildActions(workerSession)
        val existing = InventoryItem(id = "item-2", name = "Soap", quantity = 10, costReconciled = false)
        inventoryDao.itemsFlow.value = listOf(existing)

        actions.upsertInventoryItem(existing.copy(quantity = 12))

        val updated = inventoryDao.itemsFlow.value.first { it.id == "item-2" }
        assertFalse(updated.costReconciled)
        assertEquals(12, updated.quantity)
    }

    @Test
    fun `addInventoryItem creates a new item with the given fields and logs initial stock`() = runTest {
        actions.addInventoryItem(name = "Cooking oil", quantity = 15, sellingPrice = 12_000.0, costPrice = 9_000.0, sku = "OIL-1")

        val item = inventoryDao.itemsFlow.value.single()
        assertEquals("Cooking oil", item.name)
        assertEquals(15, item.quantity)
        assertEquals("OIL-1", item.sku)
        assertTrue(item.costReconciled)
        assertEquals(1, inventoryDao.adjustmentsFlow.value.size)
    }

    @Test
    fun `addInventoryItemsBulk applies owner cost only when a row has one`() = runTest {
        val rows = listOf(
            BulkInventoryRow(name = "Item A", quantity = 5, sellingPrice = 1_000.0, costPrice = 600.0),
            BulkInventoryRow(name = "Item B", quantity = 3, sellingPrice = 2_000.0, costPrice = null)
        )

        actions.addInventoryItemsBulk(rows)

        val items = inventoryDao.itemsFlow.value.sortedBy { it.name }
        assertEquals(600.0, items[0].costPrice, 0.0001)
        assertTrue(items[0].costReconciled)
        assertEquals(0.0, items[1].costPrice, 0.0001)
        assertFalse(items[1].costReconciled)
    }

    @Test
    fun `addInventoryItemsBulk from a worker never trusts a costPrice cell`() = runTest {
        actions = buildActions(workerSession)
        val rows = listOf(BulkInventoryRow(name = "Item C", quantity = 2, sellingPrice = 500.0, costPrice = 300.0))

        actions.addInventoryItemsBulk(rows)

        val item = inventoryDao.itemsFlow.value.single()
        assertEquals(0.0, item.costPrice, 0.0001)
        assertFalse(item.costReconciled)
    }

    @Test
    fun `deleteInventoryItem soft deletes and marks pending sync`() = runTest {
        val item = InventoryItem(id = "item-3", name = "Old item", isDeleted = false, pendingSync = false)
        inventoryDao.itemsFlow.value = listOf(item)

        actions.deleteInventoryItem(item)

        val updated = inventoryDao.itemsFlow.value.first { it.id == "item-3" }
        assertTrue(updated.isDeleted)
        assertTrue(updated.pendingSync)
    }

    @Test
    fun `receiveStock increases quantity and logs an incoming adjustment`() = runTest {
        val item = InventoryItem(id = "item-4", name = "Stock item", quantity = 10)
        inventoryDao.itemsFlow.value = listOf(item)

        actions.receiveStock("item-4", 5, note = "Restock")

        val updated = inventoryDao.itemsFlow.value.first { it.id == "item-4" }
        assertEquals(15, updated.quantity)
        assertEquals(1, inventoryDao.adjustmentsFlow.value.size)
    }

    @Test
    fun `recountStock adjusts quantity to match a physical count`() = runTest {
        val item = InventoryItem(id = "item-5", name = "Count item", quantity = 10)
        inventoryDao.itemsFlow.value = listOf(item)

        actions.recountStock("item-5", newQuantity = 7, note = "Physical count")

        val updated = inventoryDao.itemsFlow.value.first { it.id == "item-5" }
        assertEquals(7, updated.quantity)
        assertEquals(1, inventoryDao.adjustmentsFlow.value.size)
    }

    @Test
    fun `recountStock is a no-op when the count matches current quantity`() = runTest {
        val item = InventoryItem(id = "item-6", name = "Same item", quantity = 10)
        inventoryDao.itemsFlow.value = listOf(item)

        actions.recountStock("item-6", newQuantity = 10, note = "No change")

        assertTrue(inventoryDao.adjustmentsFlow.value.isEmpty())
    }

    @Test
    fun `getAdjustmentsForItem forwards to the repository`() = runTest {
        val item = InventoryItem(id = "item-7", name = "Item", quantity = 5)
        inventoryDao.itemsFlow.value = listOf(item)
        actions.receiveStock("item-7", 3)

        val adjustments = actions.getAdjustmentsForItem("item-7").first()
        assertEquals(1, adjustments.size)
    }
}