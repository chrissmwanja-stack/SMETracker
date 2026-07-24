package com.vestateck.smetracker.di

import android.content.Context
import com.vestateck.smetracker.data.dao.DebtDao
import com.vestateck.smetracker.data.dao.InventoryDao
import com.vestateck.smetracker.data.dao.LocalCredentialDao
import com.vestateck.smetracker.data.dao.SaleDao
import com.vestateck.smetracker.data.dao.SMEDao
import com.vestateck.smetracker.data.database.SMEDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room database + DAOs. Mirrors what SMEDatabase.getDatabase(context) did by
 * hand in MainActivity before Hilt - single @Singleton instance for the
 * whole process, same as the old `by lazy` val.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SMEDatabase =
        SMEDatabase.getDatabase(context)

    @Provides
    fun provideSmeDao(database: SMEDatabase): SMEDao = database.smeDao()

    @Provides
    fun provideInventoryDao(database: SMEDatabase): InventoryDao = database.inventoryDao()

    @Provides
    fun provideSaleDao(database: SMEDatabase): SaleDao = database.saleDao()

    @Provides
    fun provideDebtDao(database: SMEDatabase): DebtDao = database.debtDao()

    @Provides
    fun provideLocalCredentialDao(database: SMEDatabase): LocalCredentialDao = database.localCredentialDao()
}