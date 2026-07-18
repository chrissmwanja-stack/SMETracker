package com.example.smetracker.data.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtDao {
    @Query("SELECT IFNULL(SUM(amount), 0.0) FROM debts WHERE isPaid = 0")
    fun getUnpaidDebtsTotal(): Flow<Double>
}