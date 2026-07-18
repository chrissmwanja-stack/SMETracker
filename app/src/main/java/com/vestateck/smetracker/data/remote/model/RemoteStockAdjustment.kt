package com.vestateck.smetracker.data.remote.model

import com.google.firebase.firestore.DocumentId

data class RemoteStockAdjustment(
    @DocumentId val id: String = "",
    val itemId: String = "",
    val delta: Int = 0,
    val reason: String = "INCOMING",
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val recordedBy: String = ""
)
