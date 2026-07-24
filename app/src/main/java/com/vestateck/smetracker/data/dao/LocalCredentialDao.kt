package com.vestateck.smetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vestateck.smetracker.data.entities.LocalCredential

@Dao
interface LocalCredentialDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(credential: LocalCredential)

    @Query("SELECT * FROM local_credentials WHERE businessId = :businessId LIMIT 1")
    suspend fun getByBusinessId(businessId: String): LocalCredential?

    @Query("DELETE FROM local_credentials WHERE businessId = :businessId")
    suspend fun deleteByBusinessId(businessId: String)
}