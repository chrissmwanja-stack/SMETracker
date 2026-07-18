// viewmodel/SMEViewModelFactory.kt
package com.example.smetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.smetracker.data.remote.auth.SessionManager
import com.example.smetracker.data.remote.sync.SyncEngine
import com.example.smetracker.repository.SMERepository

class SMEViewModelFactory(
    private val repository: SMERepository,
    // Nullable to keep this factory usable in tests/previews that don't need
    // sync — see SMEViewModel's constructor doc for the same reasoning.
    private val syncEngine: SyncEngine? = null,
    private val sessionManager: SessionManager? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SMEViewModel(repository, syncEngine, sessionManager) as T
    }
}