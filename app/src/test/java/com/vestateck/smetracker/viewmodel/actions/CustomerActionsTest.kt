package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.fakes.FakeInventoryDao
import com.vestateck.smetracker.fakes.FakeSMEDao
import com.vestateck.smetracker.repository.SMERepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CustomerActionsTest {

    private lateinit var smeDao: FakeSMEDao
    private lateinit var actions: CustomerActions

    @Before
    fun setUp() {
        smeDao = FakeSMEDao()
        val repository = SMERepository(smeDao, FakeInventoryDao())
        actions = CustomerActions(repository, syncEngine = null)
    }

    @Test
    fun `insertCustomer stores the customer as-is`() = runTest {
        val customer = Customer(id = "cust-1", name = "Ronnie")

        actions.insertCustomer(customer)

        assertEquals(customer, smeDao.customersFlow.value.single())
    }

    @Test
    fun `addCustomer creates a new customer with the given fields`() = runTest {
        actions.addCustomer(name = "Jane", phone = "0771234567", email = "jane@example.com")

        val customer = smeDao.customersFlow.value.single()
        assertEquals("Jane", customer.name)
        assertEquals("0771234567", customer.phone)
        assertEquals("jane@example.com", customer.email)
    }

    @Test
    fun `upsertCustomer with a blank id generates a new id and inserts`() = runTest {
        val draft = Customer(id = "", name = "Grace", phone = "0771000000")

        actions.upsertCustomer(draft)

        val inserted = smeDao.customersFlow.value.single()
        assertNotEquals("", inserted.id)
        assertEquals("Grace", inserted.name)
    }

    @Test
    fun `upsertCustomer with a non-blank id updates the existing customer`() = runTest {
        val existing = Customer(id = "cust-2", name = "Old Name")
        smeDao.customersFlow.value = listOf(existing)

        actions.upsertCustomer(existing.copy(name = "New Name"))

        assertEquals(1, smeDao.customersFlow.value.size)
        val updated = smeDao.customersFlow.value.first { it.id == "cust-2" }
        assertEquals("New Name", updated.name)
    }

    @Test
    fun `deleteCustomer soft deletes and marks pending sync`() = runTest {
        val customer = Customer(id = "cust-3", name = "Temp", isDeleted = false, pendingSync = false)
        smeDao.customersFlow.value = listOf(customer)

        actions.deleteCustomer(customer)

        val updated = smeDao.customersFlow.value.first { it.id == "cust-3" }
        assertTrue(updated.isDeleted)
        assertTrue(updated.pendingSync)
    }
}