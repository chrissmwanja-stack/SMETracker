// viewmodel/actions/CustomerActions.kt
package com.vestateck.smetracker.viewmodel.actions

import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.IdGenerator

/**
 * Customer-domain mutations extracted out of SMEViewModel (Option A
 * restructuring). No behavior change from the original insertCustomer/
 * addCustomer/upsertCustomer/deleteCustomer functions - same repository
 * calls, same requestPush() timing.
 */
class CustomerActions(
    private val repository: SMERepository,
    private val syncEngine: SyncEngine?
) {
    suspend fun insertCustomer(customer: Customer) {
        repository.insertCustomer(customer)
        syncEngine?.requestPush()
    }

    suspend fun addCustomer(name: String, phone: String = "", email: String = "") {
        repository.insertCustomer(Customer(name = name, phone = phone, email = email))
        syncEngine?.requestPush()
    }

    // Callers that already have a persisted Customer (blank id would mean "not yet
    // saved") go to update; a blank id means the id needs generating on first insert.
    suspend fun upsertCustomer(customer: Customer) {
        if (customer.id.isBlank()) {
            repository.insertCustomer(customer.copy(id = IdGenerator.newId()))
        } else {
            repository.updateCustomer(customer)
        }
        syncEngine?.requestPush()
    }

    suspend fun deleteCustomer(customer: Customer) {
        repository.deleteCustomer(customer)
        syncEngine?.requestPush()
    }
}