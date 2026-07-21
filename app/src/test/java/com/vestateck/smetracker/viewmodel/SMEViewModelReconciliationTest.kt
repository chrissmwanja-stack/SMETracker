package com.vestateck.smetracker.viewmodel

import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.fakes.FakeInventoryDao
import com.vestateck.smetracker.fakes.FakeSMEDao
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.IdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers SMEViewModel.reconcileSale / reconcileInventoryCost against a
 * mocked (here: hand-rolled fake) repository — the follow-up noted in the
 * DashboardAnalyticsTest/TimeUtilsTest/CurrencyUtilsTest/MemberRoleTest
 * session.
 *
 * There's no mocking library in this project's test dependencies, so
 * FakeSMEDao/FakeInventoryDao (both plain interfaces) stand in for Room.
 * SMERepository itself is used unmodified — only its two DAOs are faked.
 *
 * syncEngine/sessionManager/businessRepository are left null: neither
 * reconcileSale nor reconcileInventoryCost reads session data, and
 * syncEngine?.requestPush() safely no-ops when null.
 *
 * Not yet executed against a real JVM/Gradle build — the sandbox used to
 * write this has no javac/Android SDK access (same caveat as the earlier
 * test files). Run `.\gradlew testDebugUnitTest` locally before relying on
 * these; report back any failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SMEViewModelReconciliationTest {

    private lateinit var smeDao: FakeSMEDao
    private lateinit var inventoryDao: FakeInventoryDao
    private lateinit var viewModel: SMEViewModel

    @Before
    fun setUp() {
        // viewModelScope.launch runs on Dispatchers.Main, which has no real
        // implementation in a plain JVM unit test without this.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // IdGenerator.newId() calls FirebaseFirestore.getInstance(), which
        // throws in unit tests — provide a stable fake ID so addSale() etc.
        // can construct entities without crashing.
        IdGenerator.setTestId("test-id")
        smeDao = FakeSMEDao()
        inventoryDao = FakeInventoryDao()
        val repository = SMERepository(smeDao, inventoryDao)
        viewModel = SMEViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        IdGenerator.setTestId(null)
    }

    @Test
    fun `reconcileSale computes total cost and profit from per-unit cost price`() = runTest {
        val sale = Sale(
            id = "sale-1",
            customerName = "Jane",
            description = "3 bars of soap",
            amount = 30_000.0,
            quantity = 3,
            inventoryItemId = "item-1",
            financialsReconciled = false,
            paymentMethod = PaymentMethod.CASH
        )
        smeDao.salesFlow.value = listOf(sale)
        // unreconciledSales is a WhileSubscribed-shared StateFlow — its
        // .value stays at the stateIn initialValue (emptyList()) until
        // something actually collects it once. reconcileSale reads .value
        // directly, so the test has to trigger that same real collection
        // first, the same way a subscribed screen would.
        viewModel.unreconciledSales.first()

        viewModel.reconcileSale(saleId = "sale-1", costPricePerUnit = 5_000.0)

        val updated = smeDao.salesFlow.value.first { it.id == "sale-1" }
        // amount 30_000 - (costPricePerUnit 5_000 * quantity 3) = 15_000
        assertEquals(15_000.0, updated.costPriceSnapshot, 0.0001)
        assertEquals(15_000.0, updated.profit, 0.0001)
        assertTrue(updated.financialsReconciled)
        assertTrue(updated.pendingSync)
    }

    @Test
    fun `reconcileSale is a no-op for an unknown sale id`() = runTest {
        viewModel.unreconciledSales.first()

        viewModel.reconcileSale(saleId = "does-not-exist", costPricePerUnit = 5_000.0)

        assertTrue(smeDao.salesFlow.value.isEmpty())
    }

    @Test
    fun `reconcileSale ignores a sale that is already reconciled`() = runTest {
        val alreadyReconciled = Sale(
            id = "sale-2",
            customerName = "Ronnie",
            description = "Custom order, no linked item",
            amount = 10_000.0,
            quantity = 1,
            financialsReconciled = true // not in the unreconciled queue
        )
        smeDao.salesFlow.value = listOf(alreadyReconciled)
        viewModel.unreconciledSales.first()

        // The sale exists in the DB but isn't in unreconciledSales (see
        // reconcileSale's lookup), so this must not silently overwrite an
        // already-reconciled sale's numbers.
        viewModel.reconcileSale(saleId = "sale-2", costPricePerUnit = 999.0)

        val unchanged = smeDao.salesFlow.value.first { it.id == "sale-2" }
        assertEquals(0.0, unchanged.costPriceSnapshot, 0.0001)
        assertEquals(0.0, unchanged.profit, 0.0001)
    }

    @Test
    fun `reconcileSale with a zero per-unit cost resolves profit as the full sale amount`() = runTest {
        // Edge case: a linked item sold at quantity 1 with a per-unit cost
        // of 0 (e.g. owner genuinely gave it away at cost) should still
        // compute cleanly rather than special-casing zero.
        val sale = Sale(
            id = "sale-3",
            customerName = "Walk-in",
            description = "1 bar of soap",
            amount = 3_000.0,
            quantity = 1,
            inventoryItemId = "item-1",
            financialsReconciled = false
        )
        smeDao.salesFlow.value = listOf(sale)
        viewModel.unreconciledSales.first()

        viewModel.reconcileSale(saleId = "sale-3", costPricePerUnit = 0.0)

        val updated = smeDao.salesFlow.value.first { it.id == "sale-3" }
        assertEquals(0.0, updated.costPriceSnapshot, 0.0001)
        assertEquals(3_000.0, updated.profit, 0.0001)
        assertTrue(updated.financialsReconciled)
    }

    @Test
    fun `reconcileInventoryCost sets cost price and marks item reconciled`() = runTest {
        val item = InventoryItem(
            id = "item-1",
            name = "Bar of soap",
            quantity = 20,
            sellingPrice = 3_000.0,
            costReconciled = false
        )
        inventoryDao.itemsFlow.value = listOf(item)

        // Unlike reconcileSale, reconcileInventoryCost doesn't look the item
        // up in unreconciledInventoryItems first — it writes straight
        // through to the repository, so no forced-collection step is needed
        // here.
        viewModel.reconcileInventoryCost(itemId = "item-1", costPrice = 1_800.0)

        val updated = inventoryDao.itemsFlow.value.first { it.id == "item-1" }
        assertEquals(1_800.0, updated.costPrice, 0.0001)
        assertTrue(updated.costReconciled)
        assertTrue(updated.pendingSync)
    }

    @Test
    fun `reconcileInventoryCost is a no-op for an unknown item id`() = runTest {
        viewModel.reconcileInventoryCost(itemId = "does-not-exist", costPrice = 1_800.0)

        assertTrue(inventoryDao.itemsFlow.value.isEmpty())
    }

    // -- addSale: auto-apply a known item cost price ---------------------
    // Covers the fix for the friction Chris described: once an item's cost
    // price is reconciled, a new sale against it should be born already
    // reconciled with the right numbers - not land in the queue just to
    // have the same cost re-typed back in.

    @Test
    fun `addSale auto-reconciles when the linked item's cost price is already known`() = runTest {
        val item = InventoryItem(
            id = "item-1",
            name = "Bar of soap",
            quantity = 20,
            costPrice = 2_500.0,
            sellingPrice = 3_500.0,
            costReconciled = true
        )
        inventoryDao.itemsFlow.value = listOf(item)
        viewModel.inventoryItems.first()

        viewModel.addSale(
            customerName = "Jane",
            description = "2 bars of soap",
            amount = 7_000.0,
            paymentMethod = PaymentMethod.CASH,
            inventoryItemId = "item-1",
            quantity = 2
        )

        val sale = smeDao.salesFlow.value.single()
        assertTrue(sale.financialsReconciled)
        assertEquals(5_000.0, sale.costPriceSnapshot, 0.0001) // 2_500 * 2
        assertEquals(2_000.0, sale.profit, 0.0001) // 7_000 - 5_000
        assertTrue(viewModel.unreconciledSales.first().none { it.id == sale.id })
    }

    @Test
    fun `addSale still needs manual review when the linked item's cost price is not yet known`() = runTest {
        val item = InventoryItem(
            id = "item-2",
            name = "New item",
            quantity = 10,
            costPrice = 0.0,
            sellingPrice = 5_000.0,
            costReconciled = false
        )
        inventoryDao.itemsFlow.value = listOf(item)
        viewModel.inventoryItems.first()

        viewModel.addSale(
            customerName = "Walk-in",
            description = "1 new item",
            amount = 5_000.0,
            paymentMethod = PaymentMethod.CASH,
            inventoryItemId = "item-2",
            quantity = 1
        )

        val sale = smeDao.salesFlow.value.single()
        assertTrue(!sale.financialsReconciled)
        assertEquals(0.0, sale.costPriceSnapshot, 0.0001)
        assertTrue(viewModel.unreconciledSales.first().any { it.id == sale.id })
    }

    @Test
    fun `addSale with no linked item is reconciled by definition`() = runTest {
        viewModel.addSale(
            customerName = "Walk-in",
            description = "Custom repair job",
            amount = 15_000.0,
            paymentMethod = PaymentMethod.CASH
        )

        val sale = smeDao.salesFlow.value.single()
        assertTrue(sale.financialsReconciled)
        assertEquals(0.0, sale.profit, 0.0001)
    }

    // -- editSaleCost: revising an already-reconciled sale ----------------

    @Test
    fun `editSaleCost updates cost and profit for a sale that's already reconciled`() = runTest {
        val sale = Sale(
            id = "sale-4",
            customerName = "Grace",
            description = "2 bars of soap",
            amount = 7_000.0,
            quantity = 2,
            inventoryItemId = "item-1",
            costPriceSnapshot = 5_000.0, // auto-applied at 2_500/unit
            profit = 2_000.0,
            financialsReconciled = true
        )
        smeDao.salesFlow.value = listOf(sale)
        viewModel.sales.first()

        // Turns out this particular pair was bought at 3_000/unit, not the
        // item's usual 2_500.
        viewModel.editSaleCost(saleId = "sale-4", costPricePerUnit = 3_000.0)

        val updated = smeDao.salesFlow.value.first { it.id == "sale-4" }
        assertEquals(6_000.0, updated.costPriceSnapshot, 0.0001)
        assertEquals(1_000.0, updated.profit, 0.0001)
        assertTrue(updated.financialsReconciled)
        assertTrue(updated.pendingSync)
    }

    @Test
    fun `editSaleCost is a no-op for an unknown sale id`() = runTest {
        viewModel.sales.first()

        viewModel.editSaleCost(saleId = "does-not-exist", costPricePerUnit = 5_000.0)

        assertTrue(smeDao.salesFlow.value.isEmpty())
    }
}