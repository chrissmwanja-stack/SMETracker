// viewmodel/SMEViewModelFactory.kt
package com.vestateck.smetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vestateck.smetracker.data.remote.auth.BusinessRepository
import com.vestateck.smetracker.data.remote.auth.SessionManager
import com.vestateck.smetracker.data.remote.sync.SyncEngine
import com.vestateck.smetracker.repository.SMERepository
import com.vestateck.smetracker.utils.ReceiptNumberGenerator

class SMEViewModelFactory(
    private val repository: SMERepository,
    // Nullable to keep this factory usable in tests/previews that don't need
    // sync - see SMEViewModel's constructor doc for the same reasoning.
    private val syncEngine: SyncEngine? = null,
    private val sessionManager: SessionManager? = null,
    private val businessRepository: BusinessRepository? = null,
    private val receiptNumberGenerator: ReceiptNumberGenerator? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SMEViewModel(repository, syncEngine, sessionManager, businessRepository, receiptNumberGenerator) as T
    }
}