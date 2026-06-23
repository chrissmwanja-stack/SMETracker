// viewmodel/SMEViewModelFactory.kt
package com.example.smetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.smetracker.repository.SMERepository

class SMEViewModelFactory(
    private val repository: SMERepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SMEViewModel(repository) as T
    }
}