package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.fakes.FakeInventoryDao
import com.vestateck.smetracker.fakes.FakeSMEDao
import com.vestateck.smetracker.repository.SMERepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpenseActionsTest {

    private lateinit var smeDao: FakeSMEDao
    private lateinit var actions: ExpenseActions

    @Before
    fun setUp() {
        smeDao = FakeSMEDao()
        val repository = SMERepository(smeDao, FakeInventoryDao())
        actions = ExpenseActions(repository, syncEngine = null)
    }

    @Test
    fun `addExpense with a receipt photo is pending upload`() = runTest {
        actions.addExpense(
            description = "Fuel",
            amount = 20_000.0,
            category = "Transport",
            receiptNumber = "RCT-1",
            localReceiptPath = "/path/receipt.jpg"
        )

        val expense = smeDao.expensesFlow.value.single()
        assertEquals("Fuel", expense.description)
        assertEquals(20_000.0, expense.amount, 0.0001)
        assertEquals("Transport", expense.category)
        assertEquals("RCT-1", expense.receiptNumber)
        assertEquals("/path/receipt.jpg", expense.localReceiptPath)
        assertTrue(expense.receiptPendingUpload)
    }

    @Test
    fun `addExpense without a receipt photo defaults category and is not pending upload`() = runTest {
        actions.addExpense(description = "Airtime", amount = 5_000.0)

        val expense = smeDao.expensesFlow.value.single()
        assertEquals("General", expense.category)
        assertFalse(expense.receiptPendingUpload)
    }

    @Test
    fun `deleteExpense soft deletes and marks pending sync`() = runTest {
        val expense = Expense(id = "exp-1", description = "Old", amount = 1_000.0, isDeleted = false, pendingSync = false)
        smeDao.expensesFlow.value = listOf(expense)

        actions.deleteExpense(expense)

        val updated = smeDao.expensesFlow.value.first { it.id == "exp-1" }
        assertTrue(updated.isDeleted)
        assertTrue(updated.pendingSync)
    }
}