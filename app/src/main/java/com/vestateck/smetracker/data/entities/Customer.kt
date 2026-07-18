package com.vestateck.smetracker.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vestateck.smetracker.utils.IdGenerator

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey val id: String = IdGenerator.newId(),
    val name: String,
    val phone: String = "",
    val email: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val pendingSync: Boolean = true
)