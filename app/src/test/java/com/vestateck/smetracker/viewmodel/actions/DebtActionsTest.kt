package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.fakes.FakeInventoryDao
import com.vestateck.smetracker.fakes.FakeSMEDao
import com.vestateck.smetracker.repository.SMERepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebtActionsTest {

    private lateinit var smeDao: FakeSMEDao
    private lateinit var actions: DebtActions

    @Before
    fun setUp() {
        smeDao = FakeSMEDao()
        val repository = SMERepository(smeDao, FakeInventoryDao())
        actions = DebtActions(repository, syncEngine = null)
    }

    @Test
    fun `insertDebt stores the debt as-is`() = runTest {
        val debt = Debt(id = "debt-1", customerName = "Jane", amount = 50_000.0)

        actions.insertDebt(debt)

        assertEquals(debt, smeDao.debtsFlow.value.single())
    }

    @Test
    fun `markDebtAsPaid marks the debt paid and pending sync`() = runTest {
        val debt = Debt(id = "debt-2", customerName = "Ronnie", amount = 10_000.0, isPaid = false, pendingSync = false)
        smeDao.debtsFlow.value = listOf(debt)

        actions.markDebtAsPaid("debt-2")

        val updated = smeDao.debtsFlow.value.first { it.id == "debt-2" }
        assertTrue(updated.isPaid)
        assertTrue(updated.pendingSync)
    }

    @Test
    fun `markDebtAsPaid is a no-op for an unknown debt id`() = runTest {
        actions.markDebtAsPaid("does-not-exist")

        assertTrue(smeDao.debtsFlow.value.isEmpty())
    }
}